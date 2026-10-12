package com.rewardsiq.audit;

import java.time.Instant;
import java.util.Map;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.mapping.Document;

/** Append-only audit record (NFR-S7). Never updated or deleted by the application. */
@Document("auditEvents")
@CompoundIndex(name = "history", def = "{'airline': 1, 'entityId': 1, 'at': -1}")
public record AuditEvent(
        @Id String id,
        String airline,
        String entity,
        String entityId,
        Integer version,
        String action,
        String actor,
        Map<String, Object> before,
        Map<String, Object> after,
        String correlationId,
        Instant at) {
}
