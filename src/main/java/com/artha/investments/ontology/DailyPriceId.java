package com.artha.investments.ontology;

import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Composite primary key for {@link DailyPrice} — (security_id, price_date).
 * Required by JPA's @IdClass to bind multi-column PKs.
 */
@Getter @Setter
@NoArgsConstructor @AllArgsConstructor
@EqualsAndHashCode
public class DailyPriceId implements Serializable {
    private UUID      securityId;
    private LocalDate priceDate;
}
