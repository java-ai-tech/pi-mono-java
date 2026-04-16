package com.glmapper.coding.core.mongo;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface UsageMetricsRepository extends MongoRepository<UsageMetricsDocument, String> {
    List<UsageMetricsDocument> findByNamespaceAndDateBetweenOrderByDateDesc(
            String namespace, LocalDate from, LocalDate to);
    Optional<UsageMetricsDocument> findByNamespaceAndDate(String namespace, LocalDate date);
}
