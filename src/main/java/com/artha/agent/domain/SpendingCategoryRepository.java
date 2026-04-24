package com.artha.agent.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SpendingCategoryRepository extends JpaRepository<SpendingCategory, UUID> {

    List<SpendingCategory> findByUserId(UUID userId);

    Optional<SpendingCategory> findByUserIdAndName(UUID userId, String name);

    Optional<SpendingCategory> findByUserIdAndMerchantTypeId(UUID userId, UUID merchantTypeId);
}