package com.artha.investments.ontology;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface BenchmarkRepository extends JpaRepository<Benchmark, UUID> {

    Optional<Benchmark> findByTicker(String ticker);

    List<Benchmark> findByCategory(String category);
}
