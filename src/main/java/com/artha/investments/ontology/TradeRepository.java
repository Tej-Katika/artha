package com.artha.investments.ontology;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface TradeRepository extends JpaRepository<Trade, UUID> {

    List<Trade> findByPortfolioIdOrderByExecutedAtDesc(UUID portfolioId);

    List<Trade> findByPortfolioIdAndExecutedAtBetweenOrderByExecutedAtDesc(
        UUID portfolioId, Instant from, Instant to);

    List<Trade> findBySecurityIdOrderByExecutedAtDesc(UUID securityId);
}
