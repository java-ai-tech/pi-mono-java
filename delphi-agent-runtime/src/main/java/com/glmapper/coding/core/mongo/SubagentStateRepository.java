package com.glmapper.coding.core.mongo;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;

public interface SubagentStateRepository extends MongoRepository<SubagentStateDocument, String> {

    List<SubagentStateDocument> findByNamespaceAndParentRunId(String namespace, String parentRunId);

    List<SubagentStateDocument> findByNamespaceAndSessionIdAndStatus(String namespace, String sessionId, String status);

    List<SubagentStateDocument> findByNamespaceAndParentRunIdAndStatus(String namespace, String parentRunId, String status);

    long countByTenantIdAndStatus(String tenantId, String status);
}
