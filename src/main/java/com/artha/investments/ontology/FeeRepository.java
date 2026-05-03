package com.artha.investments.ontology;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface FeeRepository extends JpaRepository<Fee, UUID> {

    List<Fee> findByPortfolioId(UUID portfolioId);

    List<Fee> findByPortfolioIdAndKind(UUID portfolioId, String kind);
}
