package com.glmapper.coding.core.mongo;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface SessionRepository extends MongoRepository<SessionDocument, String> {
    List<SessionDocument> findByNamespaceAndProjectKeyOrderByUpdatedAtDesc(String namespace, String projectKey);
    Optional<SessionDocument> findByIdAndNamespace(String id, String namespace);
}
