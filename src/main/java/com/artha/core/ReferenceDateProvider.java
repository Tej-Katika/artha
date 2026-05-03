package com.artha.core;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * Returns the "reference date" used by tool query windows (e.g., "last 30 days",
 * "this month"). When {@code artha.eval.reference-date} is set to an ISO-8601
 * date like {@code 2024-12-25}, every call returns values anchored to that
 * date; otherwise falls back to the real wall-clock.
 *
 * Lets benchmark runs target a fixed-range synthetic dataset without
 * mutating data or shifting timestamps. Do not use for audit timestamps
 * (createdAt/updatedAt) — those must stay on wall-clock.
 */
@Slf4j
@Component
public class ReferenceDateProvider {

    private static final ZoneId ZONE = ZoneOffset.UTC;

    private final LocalDate override;

    public ReferenceDateProvider(
        @Value("${artha.eval.reference-date:}") String refDate
    ) {
        if (refDate == null || refDate.isBlank()) {
            this.override = null;
        } else {
            this.override = LocalDate.parse(refDate.trim());
            log.warn("ReferenceDateProvider: eval override active â€” treating 'today' as {}",
                this.override);
        }
    }

    public LocalDate today() {
        return override != null ? override : LocalDate.now(ZONE);
    }

    public Instant now() {
        return override != null
            ? override.atStartOfDay(ZONE).toInstant()
            : Instant.now();
    }

    public YearMonth yearMonth() {
        return override != null ? YearMonth.from(override) : YearMonth.now(ZONE);
    }
}
