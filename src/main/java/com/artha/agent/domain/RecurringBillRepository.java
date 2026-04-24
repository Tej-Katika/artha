package com.artha.agent.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface RecurringBillRepository extends JpaRepository<RecurringBill, UUID> {

    List<RecurringBill> findByUserIdAndIsActiveTrue(UUID userId);

    Optional<RecurringBill> findByUserIdAndMerchantProfileId(
        UUID userId, UUID merchantProfileId);

    /**
     * Find all recurring bills for a user ordered by amount descending.
     * Used to show subscription costs ranked by expense.
     */
    @Query("SELECT r FROM RecurringBill r " +
           "WHERE r.userId = :userId " +
           "AND r.isActive = true " +
           "ORDER BY r.expectedAmount DESC")
    List<RecurringBill> findActiveByUserIdOrderByAmountDesc(UUID userId);

    /**
     * Total monthly subscription cost for a user.
     * Annual bills are divided by 12 for monthly equivalent.
     */
    @Query(value =
        "SELECT COALESCE(SUM(" +
        "  CASE WHEN billing_cycle = 'ANNUAL' " +
        "       THEN expected_amount / 12 " +
        "       ELSE expected_amount END" +
        "), 0) " +
        "FROM recurring_bills " +
        "WHERE user_id = CAST(:userId AS uuid) " +
        "AND is_active = true",
        nativeQuery = true)
    BigDecimal totalMonthlySubscriptionCost(@Param("userId") UUID userId);

    @Modifying
    @Transactional
    long deleteByUserId(UUID userId);
}