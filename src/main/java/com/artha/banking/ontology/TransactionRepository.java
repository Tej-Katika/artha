package com.artha.banking.ontology;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Repository
public interface TransactionRepository extends JpaRepository<Transaction, UUID> {

    // All transactions for a user, newest first
    List<Transaction> findByUserIdOrderByPostDateDesc(UUID userId);

    // Transactions within a date range
    List<Transaction> findByUserIdAndPostDateBetweenOrderByPostDateDesc(
        UUID userId, Instant startDate, Instant endDate
    );

    // Transactions by type (DEBIT / CREDIT / TRANSFER)
    List<Transaction> findByUserIdAndTransactionTypeOrderByPostDateDesc(
        UUID userId, String transactionType
    );

    // Spending grouped by merchant
    @Query("""
        SELECT t.merchantName, SUM(t.amount) as total
        FROM Transaction t
        WHERE t.userId = :userId
          AND t.postDate BETWEEN :startDate AND :endDate
          AND t.transactionType = 'DEBIT'
        GROUP BY t.merchantName
        ORDER BY total DESC
        """)
    List<Object[]> sumByMerchant(
        @Param("userId") UUID userId,
        @Param("startDate") Instant startDate,
        @Param("endDate") Instant endDate
    );

    // Total spent (debits) in date range
    @Query("""
        SELECT COALESCE(SUM(t.amount), 0)
        FROM Transaction t
        WHERE t.userId = :userId
          AND t.postDate BETWEEN :startDate AND :endDate
          AND t.transactionType = 'DEBIT'
        """)
    BigDecimal totalSpent(
        @Param("userId") UUID userId,
        @Param("startDate") Instant startDate,
        @Param("endDate") Instant endDate
    );

    // Total received (income) in date range
    @Query("""
        SELECT COALESCE(SUM(t.amount), 0)
        FROM Transaction t
        WHERE t.userId = :userId
          AND t.postDate BETWEEN :startDate AND :endDate
          AND t.transactionType = 'CREDIT'
        """)
    BigDecimal totalReceived(
        @Param("userId") UUID userId,
        @Param("startDate") Instant startDate,
        @Param("endDate") Instant endDate
    );

    // Duplicate detection â€” used by TransactionController on import
    boolean existsByReferenceId(String referenceId);
}