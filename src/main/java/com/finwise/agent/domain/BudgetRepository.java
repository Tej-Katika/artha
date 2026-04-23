package com.finwise.agent.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface BudgetRepository extends JpaRepository<Budget, UUID> {

    /**
     * Find the active budget for a user+category combination.
     * A budget is active if effectiveTo is null or in the future.
     */
    @Query("SELECT b FROM Budget b " +
           "WHERE b.userId = :userId " +
           "AND b.spendingCategory.id = :categoryId " +
           "AND b.effectiveFrom <= :today " +
           "AND (b.effectiveTo IS NULL OR b.effectiveTo >= :today)")
    Optional<Budget> findActiveBudget(UUID userId, UUID categoryId, LocalDate today);

    List<Budget> findByUserId(UUID userId);
}