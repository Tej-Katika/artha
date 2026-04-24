package com.artha.agent.domain;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Maps to the 'bank_accounts' table you created in PostgreSQL.
 *
 * Table columns:
 *   id, user_id, institution_name, account_name, account_type,
 *   mask, current_balance, available_balance, currency_code,
 *   is_active, last_synced_at, provider_source, external_account_id,
 *   created_at, updated_at
 */
@Data
@NoArgsConstructor
@Entity
@Table(name = "bank_accounts")
public class BankAccount {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "id")
    private UUID id;

    // FK to users.id
    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "institution_name", nullable = false, length = 100)
    private String institutionName;       // e.g. "Chase", "Bank of America"

    @Column(name = "account_name", length = 100)
    private String accountName;           // e.g. "Total Checking"

    @Column(name = "account_type", length = 50)
    private String accountType;           // checking | savings | credit_card

    @Column(name = "mask", nullable = false, length = 4)
    private String mask;                  // Last 4 digits of account number

    @Column(name = "current_balance", precision = 19, scale = 4)
    private BigDecimal currentBalance;

    @Column(name = "available_balance", precision = 19, scale = 4)
    private BigDecimal availableBalance;

    @Column(name = "currency_code", length = 3)
    private String currencyCode = "USD";

    @Column(name = "is_active")
    private Boolean isActive = true;

    @Column(name = "last_synced_at")
    private Instant lastSyncedAt;

    @Column(name = "provider_source", length = 50)
    private String providerSource;        // plaid | manual | statement

    @Column(name = "external_account_id", length = 255)
    private String externalAccountId;     // Plaid's account ID

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
