package com.artha.investments.ontology;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface RiskFreeRateRepository extends JpaRepository<RiskFreeRate, LocalDate> {

    Optional<RiskFreeRate> findFirstByOrderByRateDateDesc();

    List<RiskFreeRate> findByRateDateBetweenOrderByRateDateAsc(LocalDate from, LocalDate to);
}
