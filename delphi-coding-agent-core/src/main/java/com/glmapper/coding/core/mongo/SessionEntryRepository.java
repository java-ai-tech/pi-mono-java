package com.glmapper.coding.core.mongo;

import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface SessionEntryRepository extends MongoRepository<SessionEntryDocument, String> {
    List<SessionEntryDocument> findBySessionIdOrderByTimestampAsc(String sessionId);

    Optional<SessionEntryDocument> findBySessionIdAndEntryId(String sessionId, String entryId);
}
