package com.rewardsiq.audit;

import java.util.List;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface AuditRepository extends MongoRepository<AuditEvent, String> {

    List<AuditEvent> findByAirlineAndEntityIdOrderByAtDesc(String airline, String entityId);
}
