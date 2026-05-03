package com.artha.investments.ontology;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface DailyPriceRepository extends JpaRepository<DailyPrice, DailyPriceId> {

    Optional<DailyPrice> findBySecurityIdAndPriceDate(UUID securityId, LocalDate priceDate);

    List<DailyPrice> findBySecurityIdAndPriceDateBetweenOrderByPriceDateAsc(
        UUID securityId, LocalDate from, LocalDate to);

    Optional<DailyPrice> findFirstBySecurityIdOrderByPriceDateDesc(UUID securityId);
}
