package com.rewardsiq.publish;

import java.time.Instant;
import java.util.List;
import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface PublicationRepository extends MongoRepository<RulePublication, String> {

    List<RulePublication> findByStatusAndNextAttemptAtLessThanEqualOrderByNextAttemptAt(
            PublicationStatus status, Instant now, Pageable page);

    List<RulePublication> findByAirlineAndRuleIdOrderByCreatedAtDesc(String airline, String ruleId);
}
