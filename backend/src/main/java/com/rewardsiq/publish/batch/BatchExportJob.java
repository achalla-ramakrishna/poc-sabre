package com.rewardsiq.publish.batch;

import com.rewardsiq.rule.RedemptionRule;
import com.rewardsiq.rule.RuleRepository;
import com.rewardsiq.rule.RuleStatus;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * UC-07: every rewards.batch.cron, write one snapshot per airline of all rules in force, plus a
 * .sha256 file. Consumers replace their whole rule set with each file.
 */
@Component
@ConditionalOnProperty(prefix = "rewards.batch", name = "enabled", havingValue = "true")
public class BatchExportJob {

    private static final Logger log = LoggerFactory.getLogger(BatchExportJob.class);
    private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmm'Z'")
            .withZone(ZoneOffset.UTC);

    private final RuleRepository rules;
    private final FileSink sink;
    private final Clock clock;
    private int consecutiveFailures;

    public BatchExportJob(RuleRepository rules, FileSink sink, Clock clock) {
        this.rules = rules;
        this.sink = sink;
        this.clock = clock;
    }

    @Scheduled(cron = "${rewards.batch.cron}", zone = "UTC")
    public void scheduled() {
        try {
            exportAll();
            consecutiveFailures = 0;
        } catch (RuntimeException ex) {
            consecutiveFailures++;
            if (consecutiveFailures >= 2) {
                log.error("Batch export failed {} times in a row", consecutiveFailures, ex);
            } else {
                log.warn("Batch export failed; will retry at the next run", ex);
            }
        }
    }

    /** @return file names written */
    public List<String> exportAll() {
        Instant now = clock.instant();
        Map<String, List<RedemptionRule>> byAirline = rules
                .findByStatusIn(EnumSet.of(RuleStatus.PUBLISHED, RuleStatus.RETIRE_PENDING)).stream()
                .filter(r -> r.getStatus() == RuleStatus.PUBLISHED)
                .collect(Collectors.groupingBy(RedemptionRule::getAirline));
        return byAirline.entrySet().stream().map(e -> {
            String name = "rewardsiq_" + e.getKey() + "_rules_" + STAMP.format(now) + ".csv";
            byte[] content = CsvRuleWriter.write(e.getValue(), now).getBytes(StandardCharsets.UTF_8);
            sink.write(name, content);
            sink.write(name + ".sha256", (sha256(content) + "  " + name + "\n").getBytes(StandardCharsets.UTF_8));
            log.info("Batch export {}: {} rule version(s)", name, e.getValue().size());
            return name;
        }).toList();
    }

    static String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
