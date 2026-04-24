package com.artha.agent.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface TransactionEnrichmentRepository
        extends JpaRepository<TransactionEnrichment, UUID> {

    Optional<TransactionEnrichment> findByTransactionId(UUID transactionId);

    boolean existsByTransactionId(UUID transactionId);

    @Query("SELECT e FROM TransactionEnrichment e " +
           "JOIN Transaction t ON t.id = e.transactionId " +
           "WHERE t.userId = :userId " +
           "AND t.postDate BETWEEN :from AND :to")
    List<TransactionEnrichment> findByUserIdAndDateRange(
        @Param("userId") UUID userId,
        @Param("from")   Instant from,
        @Param("to")     Instant to);

    /**
     * Native query — explicit ::uuid cast required for PostgreSQL
     * to accept UUID parameters via JDBC.
     */
    @Query(value =
        "SELECT COALESCE(SUM(t.amount), 0) " +
        "FROM transaction_enrichments e " +
        "JOIN transactions t ON t.id = e.transaction_id " +
        "WHERE t.user_id = CAST(:userId AS uuid) " +
        "AND e.spending_category_id = CAST(:categoryId AS uuid) " +
        "AND t.post_date BETWEEN :from AND :to " +
        "AND t.transaction_type = 'DEBIT'",
        nativeQuery = true)
    BigDecimal sumSpentInCategory(
        @Param("userId")     UUID userId,
        @Param("categoryId") UUID categoryId,
        @Param("from")       Instant from,
        @Param("to")         Instant to);

    List<TransactionEnrichment> findByIsAnomalyTrue();
}