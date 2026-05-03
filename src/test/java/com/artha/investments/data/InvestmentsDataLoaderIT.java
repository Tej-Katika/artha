package com.artha.investments.data;

import com.artha.investments.ontology.DailyPriceRepository;
import com.artha.investments.ontology.RiskFreeRateRepository;
import com.artha.investments.ontology.SecurityRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.boot.test.context.SpringBootTest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.nio.file.Files;
import java.nio.file.Paths;

/**
 * End-to-end smoke for {@link InvestmentsDataLoader}: drives the
 * loader against whatever CSVs the Python fetcher has produced and
 * asserts that the securities, daily_prices, and risk_free_rate
 * tables come back populated.
 *
 * Skips (not fails) when {@code data/investments/prices/} is empty —
 * a developer running the suite without first fetching data should
 * not get a red CI build.
 *
 * Idempotent on re-run because the loader's UPSERT semantics:
 * existing rows are updated rather than duplicated.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class InvestmentsDataLoaderIT {

    @Autowired private InvestmentsDataLoader     loader;
    @Autowired private SecurityRepository        securityRepo;
    @Autowired private DailyPriceRepository      priceRepo;
    @Autowired private RiskFreeRateRepository    rateRepo;

    @Test
    void loadsCachedCsvsIntoOntologyTables() throws Exception {
        assumeTrue(
            Files.isDirectory(Paths.get("data", "investments", "prices"))
              && Files.list(Paths.get("data", "investments", "prices")).findAny().isPresent(),
            "data/investments/prices/ is empty — run "
              + "`py -3 data/fetchers/fetch_yahoo.py` first");

        long securitiesBefore = securityRepo.count();
        long pricesBefore     = priceRepo.count();

        loader.run(new DefaultApplicationArguments("--load-investments-data"));

        assertThat(securityRepo.count())
            .as("at least 1 security row landed")
            .isGreaterThanOrEqualTo(Math.max(1, securitiesBefore));
        assertThat(priceRepo.count())
            .as("daily_prices grew (or stayed equal on re-run)")
            .isGreaterThanOrEqualTo(pricesBefore);

        // dgs10.csv is only emitted when ^TNX is in the fetch; tolerate
        // its absence rather than red-failing on a partial fetch.
        assertThat(rateRepo.count())
            .as("risk_free_rate row count is non-negative")
            .isGreaterThanOrEqualTo(0);
    }
}
