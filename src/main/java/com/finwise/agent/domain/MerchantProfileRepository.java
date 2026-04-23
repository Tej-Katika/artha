package com.finwise.agent.domain;

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
     * Uses PostgreSQL array contains operator (@>).
     */
    @Query(value = "SELECT * FROM merchant_profiles " +
                   "WHERE name_variants @> ARRAY[UPPER(:name)]::text[] " +
                   "LIMIT 1",
           nativeQuery = true)
    Optional<MerchantProfile> findByNameVariant(String name);
}