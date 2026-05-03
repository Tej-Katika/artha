package com.artha.investments.ontology;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface LotRepository extends JpaRepository<Lot, UUID> {

    List<Lot> findByPositionIdOrderByAcquiredAtAsc(UUID positionId);

    List<Lot> findByPositionIdAndClosedAtIsNullOrderByAcquiredAtAsc(UUID positionId);
}
