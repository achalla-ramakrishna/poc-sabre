package com.rewardsiq.rule;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface RuleRepository extends MongoRepository<RedemptionRule, String> {

    Optional<RedemptionRule> findByAirlineAndRuleIdAndVersion(String airline, String ruleId, int version);

    Optional<RedemptionRule> findFirstByAirlineAndRuleIdOrderByVersionDesc(String airline, String ruleId);

    List<RedemptionRule> findByAirlineAndRuleIdOrderByVersionDesc(String airline, String ruleId);

    List<RedemptionRule> findByAirlineAndStatusIn(String airline, Collection<RuleStatus> statuses);

    List<RedemptionRule> findByStatusIn(Collection<RuleStatus> statuses);

    List<RedemptionRule> findByAirline(String airline);

    boolean existsByAirlineAndNameIgnoreCaseAndRuleIdNot(String airline, String name, String ruleId);
}
