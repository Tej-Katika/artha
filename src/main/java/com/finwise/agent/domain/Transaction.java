package com.finwise.agent.domain;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;

import java.util.Map;
import java.util.UUID;

/**
 * Maps to the 'transactions' table you created in PostgreSQL.
 *
 * Table columns:
 *   id, user_id, bank_account_id, transaction_type, post_date,
 *   description, merchant_name, amount, balance, payment_method,
 *   reference_id, metadata, created_at, updated_at
 */
@Data
@NoArgsConstructor
@Entity
@Table(name = "transactions")
public class Transaction {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    private UUID id;

    // FK to users.id
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    // FK to bank_accounts.id (nullable — a transaction may not yet be linked to an account)
    @Column(name = "bank_account_id")
    private UUID bankAccountId;

    @Column(name = "transaction_type", nullable = false, length = 20)
    private String transactionType;       // DEBIT | CREDIT | TRANSFER

    @Column(name = "post_date", nullable = false)
    private Instant postDate;

    @Column(name = "description", nullable = false, columnDefinition = "text")
    private String description;

    @Column(name = "merchant_name", length = 255)
    private String merchantName;

    @Column(name = "amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "balance", nullable = false, precision = 19, scale = 4)
    private BigDecimal balance;           // Running balance after this transaction

    @Column(name = "payment_method", length = 50)
    private String paymentMethod;         // ACH | ZELLE | CHECK | CARD | etc.

    @Column(name = "reference_id", unique = true, length = 100)
    private String referenceId;           // Prevents duplicate imports

    // JSONB column — stores extra data like Plaid category, location, tags
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "metadata", columnDefinition = "jsonb")
    private Map<String, Object> metadata;

    @Column(name = "created_at", updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @PrePersist
    public void prePersist() {
        Instant now = Instant.now();
        this.createdAt = now;
        this.updatedAt = now;
    }

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = Instant.now();
    }
}
