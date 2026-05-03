package com.artha.investments.ontology;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Round-trip sanity test for the Week-7 investments ontology JPA layer.
 *
 * Inserts one Security, one DailyPrice (composite-key), and one
 * RiskFreeRate row through their Spring Data repositories, then reads
 * them back and asserts every column round-trips intact. With the V4
 * schema applied to local Postgres and {@code ddl-auto=none},
 * a column-name or precision mismatch surfaces here as a Hibernate
 * mapping or SQL exception — not at app-startup time when it would
 * be noisier to bisect.
 *
 * Uses sentinel ticker "TST_RT" + sentinel rate_date
 * 1900-01-02 so re-runs don't collide with real eval data.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class InvestmentsOntologyJpaTest {

    private static final String    TEST_TICKER = "TST_RT";
    private static final LocalDate TEST_DATE   = LocalDate.of(1900, 1, 2);

    @Autowired private SecurityRepository      securityRepo;
    @Autowired private DailyPriceRepository    priceRepo;
    @Autowired private RiskFreeRateRepository  rateRepo;

    @AfterEach
    void cleanup() {
        priceRepo.findAll().stream()
            .filter(p -> TEST_DATE.equals(p.getPriceDate()))
            .forEach(priceRepo::delete);
        securityRepo.findByTicker(TEST_TICKER).ifPresent(securityRepo::delete);
        rateRepo.findById(TEST_DATE).ifPresent(rateRepo::delete);
    }

    @Test
    void securityRoundTripsAllColumns() {
        Security s = new Security();
        s.setTicker(TEST_TICKER);
        s.setName("Test Roundtrip Security");
        s.setAssetClass("EQUITY");
        s.setSector("Technology");
        s.setMarketCapBucket("LARGE");
        Security saved = securityRepo.save(s);

        Optional<Security> fetched = securityRepo.findByTicker(TEST_TICKER);
        assertThat(fetched).isPresent();
        Security f = fetched.get();
        assertThat(f.getId()).isEqualTo(saved.getId());
        assertThat(f.getTicker()).isEqualTo(TEST_TICKER);
        assertThat(f.getName()).isEqualTo("Test Roundtrip Security");
        assertThat(f.getAssetClass()).isEqualTo("EQUITY");
        assertThat(f.getSector()).isEqualTo("Technology");
        assertThat(f.getMarketCapBucket()).isEqualTo("LARGE");
        assertThat(f.getCreatedAt())
            .as("@PrePersist set createdAt")
            .isNotNull();
    }

    @Test
    void dailyPriceCompositeKeyRoundTrips() {
        Security s = new Security();
        s.setTicker(TEST_TICKER);
        s.setName("Test Roundtrip Security");
        s.setAssetClass("EQUITY");
        Security saved = securityRepo.save(s);

        DailyPrice p = new DailyPrice();
        p.setSecurityId(saved.getId());
        p.setPriceDate(TEST_DATE);
        p.setOpenPrice(new BigDecimal("100.0000"));
        p.setHighPrice(new BigDecimal("105.5000"));
        p.setLowPrice(new BigDecimal("99.2500"));
        p.setClosePrice(new BigDecimal("104.0000"));
        p.setVolume(1_234_567L);
        p.setAdjClose(new BigDecimal("103.7500"));
        priceRepo.save(p);

        Optional<DailyPrice> fetched = priceRepo.findBySecurityIdAndPriceDate(
            saved.getId(), TEST_DATE);
        assertThat(fetched).isPresent();
        DailyPrice f = fetched.get();
        assertThat(f.getClosePrice())
            .as("close_price round-trips through NUMERIC(19,4)")
            .isEqualByComparingTo(new BigDecimal("104.0000"));
        assertThat(f.getVolume()).isEqualTo(1_234_567L);
        assertThat(f.getAdjClose())
            .isEqualByComparingTo(new BigDecimal("103.7500"));
    }

    @Test
    void riskFreeRateRoundTrips() {
        RiskFreeRate r = new RiskFreeRate();
        r.setRateDate(TEST_DATE);
        r.setDgs10Pct(new BigDecimal("4.2500"));
        rateRepo.save(r);

        Optional<RiskFreeRate> fetched = rateRepo.findById(TEST_DATE);
        assertThat(fetched).isPresent();
        assertThat(fetched.get().getDgs10Pct())
            .isEqualByComparingTo(new BigDecimal("4.2500"));
    }
}
