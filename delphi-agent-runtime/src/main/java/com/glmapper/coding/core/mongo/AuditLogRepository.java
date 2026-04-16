package com.glmapper.coding.core.mongo;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.time.Instant;
import java.util.List;

public interface AuditLogRepository extends MongoRepository<AuditLogDocument, String> {
    List<AuditLogDocument> findByNamespaceAndTimestampBetweenOrderByTimestampDesc(
            String namespace, Instant from, Instant to);
}
