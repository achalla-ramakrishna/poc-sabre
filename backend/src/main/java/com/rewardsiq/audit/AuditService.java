package com.rewardsiq.audit;

import com.rewardsiq.common.web.CorrelationIdFilter;
import com.rewardsiq.rule.RedemptionRule;
import java.time.Clock;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

@Service
public class AuditService {

    private final AuditRepository events;
    private final Clock clock;

    public AuditService(AuditRepository events, Clock clock) {
        this.events = events;
        this.clock = clock;
    }

    /** Records a rule action with the status before and after, plus any extra detail. */
    public void ruleAction(RedemptionRule rule, String action, String actor, Object statusBefore,
                           Map<String, Object> extra) {
        Map<String, Object> before = new HashMap<>();
        before.put("status", statusBefore == null ? null : statusBefore.toString());
        Map<String, Object> after = new HashMap<>(extra);
        after.put("status", rule.getStatus().name());
        events.insert(new AuditEvent(null, rule.getAirline(), "RULE", rule.getRuleId(), rule.getVersion(), action,
                actor, before, after, MDC.get(CorrelationIdFilter.MDC_KEY), clock.instant()));
    }

    public List<AuditEvent> history(String airline, String ruleId) {
        return events.findByAirlineAndEntityIdOrderByAtDesc(airline, ruleId);
    }
}
