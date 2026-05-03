package com.artha.investments.ontology;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SecurityRepository extends JpaRepository<Security, UUID> {

    Optional<Security> findByTicker(String ticker);

    List<Security> findByAssetClass(String assetClass);

    List<Security> findByAssetClassAndSector(String assetClass, String sector);
}
