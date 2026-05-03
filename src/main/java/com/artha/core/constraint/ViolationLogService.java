package com.artha.core.constraint;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Persistence helper for {@link ViolationLog} rows. All writes use
 * {@code REQUIRES_NEW} so that a violation row is durable even if the
 * surrounding agent call is rolled back, retried, or crashes mid-turn.
 *
 * Telemetry on every constraint firing is load-bearing for the
 * downstream catch-rate / false-positive-rate metrics; we deliberately
 * decouple it from caller-side error handling.
 */
@Service
@RequiredArgsConstructor
public class ViolationLogService {

    private final ViolationLogRepository repo;

    /**
     * Persist a single violation with {@code repaired=null} (no terminal
     * state observed yet). Returns the new row's id so the caller can
     * update it once the retry loop terminates.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UUID persist(ConstraintChecker.Violation v,
                        String domain,
                        UUID userId,
                        String sessionId) {
        ViolationLog row = new ViolationLog();
        row.setConstraintName(v.constraintName());
        row.setDomain(domain);
        row.setGrade(v.grade());
        row.setUserId(userId);
        row.setSessionId(sessionId);
        row.setMessage(v.message());
        row.setRepairHint(v.repairHint());
        return repo.save(row).getId();
    }

    /**
     * Set {@code repaired} on a batch of violation rows once the retry
     * loop terminates. Pass {@code true} when the final response
     * satisfied all constraints; {@code false} when the K-retry budget
     * was exhausted with violations remaining.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markRepaired(List<UUID> ids, boolean repaired) {
        if (ids == null || ids.isEmpty()) return;
        for (UUID id : ids) {
            repo.findById(id).ifPresent(row -> {
                row.setRepaired(repaired);
                repo.save(row);
            });
        }
    }
}
