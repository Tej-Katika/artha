package com.artha.banking.enrichment;

import com.artha.banking.ontology.MerchantType;
import com.artha.banking.ontology.SpendingCategory;
import com.artha.banking.ontology.SpendingCategoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Separate bean so @Transactional(REQUIRES_NEW) works correctly.
 * Spring AOP self-invocation bypass is avoided by keeping this
 * in its own class.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SpendingCategoryFactory {

    private final SpendingCategoryRepository spendingCategoryRepository;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public SpendingCategory findOrCreate(UUID userId, MerchantType merchantType) {
        // Check again inside this new transaction to avoid duplicates
        return spendingCategoryRepository
            .findByUserIdAndMerchantTypeId(userId, merchantType.getId())
            .orElseGet(() -> {
                SpendingCategory category = new SpendingCategory();
                category.setUserId(userId);
                category.setName(merchantType.getName());
                category.setMerchantType(merchantType);
                category.setIsSystem(true);
                category.setColorHex(colorFor(merchantType.getName()));
                category.setIcon(iconFor(merchantType.getName()));
                SpendingCategory saved = spendingCategoryRepository.save(category);
                log.info("Auto-created SpendingCategory '{}' for user {}",
                    merchantType.getName(), userId);
                return saved;
            });
    }

    private String colorFor(String name) {
        return switch (name) {
            case "Food & Dining", "Grocery", "Restaurant" -> "#4CAF50";
            case "Transportation", "Gas Station"           -> "#2196F3";
            case "Entertainment", "Streaming"              -> "#9C27B0";
            case "Healthcare", "Pharmacy"                  -> "#F44336";
            case "Shopping"                                -> "#FF9800";
            case "Utilities"                               -> "#607D8B";
            case "Income", "Salary"                        -> "#00BCD4";
            default                                        -> "#9E9E9E";
        };
    }

    private String iconFor(String name) {
        return switch (name) {
            case "Food & Dining", "Grocery" -> "shopping-cart";
            case "Restaurant"               -> "utensils";
            case "Transportation",
                 "Gas Station"              -> "car";
            case "Entertainment",
                 "Streaming"               -> "tv";
            case "Healthcare"               -> "heart";
            case "Shopping"                 -> "bag";
            case "Utilities"                -> "zap";
            case "Income", "Salary"         -> "trending-up";
            default                         -> "circle";
        };
    }
}