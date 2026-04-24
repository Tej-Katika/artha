package com.artha.agent.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.UUID;

@Repository
public interface ClassificationRuleRepository extends JpaRepository<ClassificationRule, UUID> {

    /**
     * Load all active rules ordered by priority (lowest number = highest priority).
     * Called once at startup and cached by the enrichment engine.
     */
    @Query("SELECT r FROM ClassificationRule r " +
           "LEFT JOIN FETCH r.merchantProfile mp " +
           "LEFT JOIN FETCH mp.merchantType " +
           "LEFT JOIN FETCH r.spendingCategory " +
           "WHERE r.isActive = true " +
           "ORDER BY r.priority ASC")
    List<ClassificationRule> findAllActiveOrderByPriority();
}