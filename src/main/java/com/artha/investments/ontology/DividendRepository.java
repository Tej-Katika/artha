package com.artha.investments.ontology;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface DividendRepository extends JpaRepository<Dividend, UUID> {

    List<Dividend> findByPositionIdOrderByPaidAtDesc(UUID positionId);

    List<Dividend> findByPositionIdAndPaidAtBetweenOrderByPaidAtDesc(
        UUID positionId, Instant from, Instant to);
}
