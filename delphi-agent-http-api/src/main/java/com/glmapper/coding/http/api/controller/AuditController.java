package com.glmapper.coding.http.api.controller;

import com.glmapper.coding.core.mongo.AuditLogDocument;
import com.glmapper.coding.core.mongo.AuditLogRepository;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

@RestController
@RequestMapping("/api/audit")
public class AuditController {

    private final AuditLogRepository auditLogRepository;

    public AuditController(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    @GetMapping
    public List<AuditLogDocument> queryAuditLogs(
            @RequestParam String namespace,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "100") int limit) {

        if (from == null) {
            from = Instant.now().minus(7, ChronoUnit.DAYS);
        }
        if (to == null) {
            to = Instant.now();
        }

        List<AuditLogDocument> logs = auditLogRepository
                .findByNamespaceAndTimestampBetweenOrderByTimestampDesc(namespace, from, to);

        if (logs.size() > limit) {
            return logs.subList(0, limit);
        }
        return logs;
    }
}
