package com.artha.banking.ontology;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface MerchantProfileRepository extends JpaRepository<MerchantProfile, UUID> {

    Optional<MerchantProfile> findByCanonicalName(String canonicalName);

    /**
     * Find merchant by checking if the input name matches any known name variant.
     * Uses PostgreSQL array contains operator (@>). Cast is via CAST(...) rather
     * than the {@code ::text[]} sugar — Spring's named-parameter parser reads the
     * latter's leading colon as a parameter prefix and fails to bind.
     */
    @Query(value = "SELECT * FROM merchant_profiles " +
                   "WHERE name_variants @> CAST(ARRAY[UPPER(:name)] AS text[]) " +
                   "LIMIT 1",
           nativeQuery = true)
    Optional<MerchantProfile> findByNameVariant(String name);
}