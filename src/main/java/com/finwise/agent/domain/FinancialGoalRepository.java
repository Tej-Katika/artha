package com.finwise.agent.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.UUID;

@Repository
public interface FinancialGoalRepository extends JpaRepository<FinancialGoal, UUID> {

    List<FinancialGoal> findByUserIdAndStatus(UUID userId, String status);

    List<FinancialGoal> findByUserIdOrderByPriorityAsc(UUID userId);
}