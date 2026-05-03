package com.artha.investments.data;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * One-shot loader: hydrate the investments-domain reference tables
 * (securities, daily_prices, risk_free_rate) from CSVs produced by
 * {@code data/fetchers/fetch_yahoo.py}.
 *
 * Triggered by the {@code --load-investments-data} CLI argument:
 * <pre>
 *   mvn spring-boot:run "-Dspring-boot.run.arguments=--load-investments-data"
 * </pre>
 *
 * Idempotent on re-run: every upsert uses Postgres
 * {@code INSERT ... ON CONFLICT DO UPDATE}, so re-fetching new data
 * and re-running this loader extends or refreshes existing rows
 * without duplicate-key errors.
 *
 * Sources:
 * <ul>
 *   <li>{@code classpath:investments/tickers.json} — security metadata
 *       (ticker, name, asset class, sector, market-cap bucket)</li>
 *   <li>{@code data/investments/prices/<TICKER>.csv} — daily OHLCV</li>
 *   <li>{@code data/investments/macro/dgs10.csv} — 10-year T-yield series</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class InvestmentsDataLoader implements ApplicationRunner {

    private static final String CLI_FLAG     = "--load-investments-data";
    private static final Path   PRICES_DIR   = Paths.get("data", "investments", "prices");
    private static final Path   MACRO_FILE   = Paths.get("data", "investments", "macro", "dgs10.csv");
    private static final String TICKERS_RES  = "investments/tickers.json";
    private static final int    BATCH_SIZE   = 1_000;

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    @Override
    public void run(ApplicationArguments args) throws Exception {
        boolean trigger = args.getNonOptionArgs().contains(CLI_FLAG)
                       || args.containsOption(CLI_FLAG.substring(2));
        if (!trigger) return;

        log.info("InvestmentsDataLoader — starting load");
        Map<String, UUID> tickerToId = loadSecurities();
        loadPrices(tickerToId);
        loadRiskFreeRate();
        log.info("InvestmentsDataLoader — done");
    }

    // ── securities ─────────────────────────────────────────────────

    private Map<String, UUID> loadSecurities() throws IOException {
        JsonNode arr = objectMapper.readTree(
            new ClassPathResource(TICKERS_RES).getInputStream());
        if (!arr.isArray()) {
            throw new IllegalStateException(TICKERS_RES + " must be a JSON array");
        }

        String sql =
            "INSERT INTO securities (id, ticker, name, asset_class, sector, market_cap_bucket) "
          + "VALUES (?, ?, ?, ?, ?, ?) "
          + "ON CONFLICT (ticker) DO UPDATE SET "
          + "    name = EXCLUDED.name, "
          + "    asset_class = EXCLUDED.asset_class, "
          + "    sector = EXCLUDED.sector, "
          + "    market_cap_bucket = EXCLUDED.market_cap_bucket";

        Map<String, UUID> tickerToId = new HashMap<>();
        for (JsonNode entry : arr) {
            UUID id = UUID.randomUUID();
            String ticker = entry.path("ticker").asText();
            String name   = entry.path("name").asText();
            String klass  = entry.path("asset_class").asText();
            String sector = nullable(entry.path("sector"));
            String bucket = nullable(entry.path("market_cap_bucket"));
            jdbc.update(sql, id, ticker, name, klass, sector, bucket);
            tickerToId.put(ticker, lookupSecurityId(ticker));
        }
        log.info("InvestmentsDataLoader — securities upserted: {}", tickerToId.size());
        return tickerToId;
    }

    private UUID lookupSecurityId(String ticker) {
        return jdbc.queryForObject(
            "SELECT id FROM securities WHERE ticker = ?", UUID.class, ticker);
    }

    private static String nullable(JsonNode n) {
        return (n == null || n.isNull()) ? null : n.asText(null);
    }

    // ── daily_prices ───────────────────────────────────────────────

    private void loadPrices(Map<String, UUID> tickerToId) throws IOException {
        if (!Files.isDirectory(PRICES_DIR)) {
            log.warn("InvestmentsDataLoader — no prices directory at {}, skipping", PRICES_DIR);
            return;
        }

        String sql =
            "INSERT INTO daily_prices ("
          + "    security_id, price_date, open_price, high_price, low_price, "
          + "    close_price, volume, adj_close) "
          + "VALUES (?, ?, ?, ?, ?, ?, ?, ?) "
          + "ON CONFLICT (security_id, price_date) DO UPDATE SET "
          + "    open_price  = EXCLUDED.open_price, "
          + "    high_price  = EXCLUDED.high_price, "
          + "    low_price   = EXCLUDED.low_price, "
          + "    close_price = EXCLUDED.close_price, "
          + "    volume      = EXCLUDED.volume, "
          + "    adj_close   = EXCLUDED.adj_close";

        long totalRows = 0;
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(PRICES_DIR, "*.csv")) {
            for (Path csv : stream) {
                String stem = csv.getFileName().toString().replaceFirst("\\.csv$", "");
                UUID securityId = resolveSecurityId(stem, tickerToId);
                if (securityId == null) {
                    log.warn("InvestmentsDataLoader — {} has no securities row, skipping", stem);
                    continue;
                }
                List<PriceRow> rows = readPriceCsv(csv);
                batchUpsertPrices(sql, securityId, rows);
                totalRows += rows.size();
                log.info("InvestmentsDataLoader — {} : {} rows", stem, rows.size());
            }
        }
        log.info("InvestmentsDataLoader — daily_prices upserted: {} rows", totalRows);
    }

    /** Resolve "TNX" CSV stem to "^TNX" ticker (the Python fetcher
     *  strips '^' for filesystem safety). */
    private static UUID resolveSecurityId(String stem, Map<String, UUID> tickerToId) {
        UUID id = tickerToId.get(stem);
        if (id != null) return id;
        return tickerToId.get("^" + stem);
    }

    private static List<PriceRow> readPriceCsv(Path csv) throws IOException {
        List<PriceRow> rows = new ArrayList<>();
        try (BufferedReader r = Files.newBufferedReader(csv, StandardCharsets.UTF_8)) {
            String header = r.readLine();
            if (header == null) return rows;
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] f = line.split(",", -1);
                if (f.length < 7) continue;
                try {
                    rows.add(new PriceRow(
                        LocalDate.parse(f[0]),
                        decimal(f[1]), decimal(f[2]), decimal(f[3]),
                        decimal(f[4]), decimal(f[5]),
                        f[6].isBlank() ? null : Long.parseLong(f[6].trim().split("\\.")[0])));
                } catch (RuntimeException ex) {
                    // Skip rows yfinance leaves blank around weekends/holidays
                    // for ETFs that didn't trade. Logged at debug elsewhere.
                }
            }
        }
        return rows;
    }

    private static BigDecimal decimal(String raw) {
        return (raw == null || raw.isBlank()) ? null : new BigDecimal(raw.trim());
    }

    private void batchUpsertPrices(String sql, UUID securityId, List<PriceRow> rows) {
        for (int i = 0; i < rows.size(); i += BATCH_SIZE) {
            List<PriceRow> chunk = rows.subList(i, Math.min(i + BATCH_SIZE, rows.size()));
            jdbc.batchUpdate(sql, new BatchPreparedStatementSetter() {
                @Override
                public void setValues(PreparedStatement ps, int idx) throws SQLException {
                    PriceRow p = chunk.get(idx);
                    ps.setObject(1, securityId);
                    ps.setObject(2, Date.valueOf(p.date()));
                    setDecimal(ps, 3, p.open());
                    setDecimal(ps, 4, p.high());
                    setDecimal(ps, 5, p.low());
                    setDecimal(ps, 6, p.close());
                    if (p.volume() == null) ps.setNull(7, java.sql.Types.BIGINT);
                    else                    ps.setLong(7, p.volume());
                    setDecimal(ps, 8, p.adjClose());
                }
                @Override public int getBatchSize() { return chunk.size(); }
            });
        }
    }

    private static void setDecimal(PreparedStatement ps, int idx, BigDecimal v) throws SQLException {
        if (v == null) ps.setNull(idx, java.sql.Types.NUMERIC);
        else           ps.setBigDecimal(idx, v);
    }

    private record PriceRow(LocalDate date, BigDecimal open, BigDecimal high,
                            BigDecimal low, BigDecimal close, BigDecimal adjClose,
                            Long volume) {}

    // ── risk_free_rate ─────────────────────────────────────────────

    private void loadRiskFreeRate() throws IOException {
        if (!Files.isRegularFile(MACRO_FILE)) {
            log.warn("InvestmentsDataLoader — no dgs10.csv at {}, skipping", MACRO_FILE);
            return;
        }

        String sql =
            "INSERT INTO risk_free_rate (rate_date, dgs10_pct) VALUES (?, ?) "
          + "ON CONFLICT (rate_date) DO UPDATE SET dgs10_pct = EXCLUDED.dgs10_pct";

        List<Object[]> batch = new ArrayList<>();
        try (BufferedReader r = Files.newBufferedReader(MACRO_FILE, StandardCharsets.UTF_8)) {
            r.readLine();   // header
            String line;
            while ((line = r.readLine()) != null) {
                if (line.isBlank()) continue;
                String[] f = line.split(",", -1);
                if (f.length < 2 || f[1].isBlank()) continue;
                try {
                    batch.add(new Object[] {
                        Date.valueOf(LocalDate.parse(f[0])),
                        new BigDecimal(f[1].trim())
                    });
                } catch (RuntimeException ex) {
                    // skip malformed rows
                }
            }
        }
        jdbc.batchUpdate(sql, batch);
        log.info("InvestmentsDataLoader — risk_free_rate upserted: {} rows", batch.size());
    }
}
