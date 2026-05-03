package com.artha.investments.ontology;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface PositionRepository extends JpaRepository<Position, UUID> {

    List<Position> findByPortfolioId(UUID portfolioId);

    Optional<Position> findByPortfolioIdAndSecurityId(UUID portfolioId, UUID securityId);

    List<Position> findBySecurityId(UUID securityId);
}
