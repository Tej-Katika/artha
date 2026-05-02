package com.artha.core.constraint;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ViolationLogRepository extends JpaRepository<ViolationLog, UUID> {

    List<ViolationLog> findByDomainAndObservedAtAfter(String domain, Instant since);

    List<ViolationLog> findByConstraintNameAndDomain(String constraintName, String domain);

    List<ViolationLog> findByUserIdOrderByObservedAtDesc(UUID userId);
}
