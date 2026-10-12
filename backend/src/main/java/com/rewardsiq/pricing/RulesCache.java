package com.rewardsiq.pricing;

import com.rewardsiq.common.error.ApiException;
import com.rewardsiq.rule.RedemptionRule;
import com.rewardsiq.rule.RuleRepository;
import com.rewardsiq.rule.RuleStatus;
import java.util.EnumSet;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Live rules held in memory so shopping-time pricing never touches MongoDB (NFR-P1).
 * Reloaded on startup, immediately after a publish on this instance, and every
 * rewards.cache.refresh-interval so other instances catch up too. The swap is a single
 * atomic reference write: readers always see a complete index.
 *
 * Also a health indicator: the instance reports DOWN (not ready) until the first load succeeds,
 * so it receives no shopping traffic with an empty cache (NFR-R6).
 */
@Component("rulesCache")
public class RulesCache implements HealthIndicator {

    private static final Logger log = LoggerFactory.getLogger(RulesCache.class);
    /** RETIRE_PENDING stays in force until DPE acknowledges the retirement (BR-10). */
    static final EnumSet<RuleStatus> IN_FORCE = EnumSet.of(RuleStatus.PUBLISHED, RuleStatus.RETIRE_PENDING);

    private final RuleRepository rules;
    private final AtomicReference<RuleIndex> index = new AtomicReference<>();

    public RulesCache(RuleRepository rules) {
        this.rules = rules;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStartup() {
        reload();
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onRulesChanged(RulesChangedEvent event) {
        log.debug("Reloading rules cache after change to {} {}", event.airline(), event.ruleId());
        reload();
    }

    @Scheduled(fixedDelayString = "${rewards.cache.refresh-interval}", initialDelayString = "${rewards.cache.refresh-interval}")
    public void scheduledRefresh() {
        reload();
    }

    public synchronized void reload() {
        try {
            List<RedemptionRule> live = rules.findByStatusIn(IN_FORCE);
            index.set(RuleIndex.of(live));
            log.info("Rules cache loaded: {} rule version(s) in force", live.size());
        } catch (RuntimeException ex) {
            // Keep serving the previous index; readiness stays DOWN only if we never loaded.
            log.error("Rules cache reload failed; keeping previous index", ex);
        }
    }

    /** @throws CacheNotReadyException before the first successful load */
    public RuleIndex current() {
        RuleIndex current = index.get();
        if (current == null) {
            throw new CacheNotReadyException();
        }
        return current;
    }

    public boolean isLoaded() {
        return index.get() != null;
    }

    @Override
    public Health health() {
        RuleIndex current = index.get();
        return current == null
                ? Health.down().withDetail("reason", "rules not loaded yet").build()
                : Health.up().withDetail("rulesInForce", current.size()).build();
    }

    public static class CacheNotReadyException extends ApiException {
        public CacheNotReadyException() {
            super("NOT_READY", HttpStatus.SERVICE_UNAVAILABLE, "Rules cache not loaded yet; retry shortly");
        }
    }
}
