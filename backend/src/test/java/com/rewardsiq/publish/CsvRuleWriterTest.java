package com.rewardsiq.publish;

import static org.assertj.core.api.Assertions.assertThat;

import com.rewardsiq.TestRules;
import com.rewardsiq.publish.batch.CsvRuleWriter;
import java.util.List;
import org.junit.jupiter.api.Test;

class CsvRuleWriterTest {

    @Test
    void writes_header_and_one_row_per_rate_matching_the_spec_layout() {
        String csv = CsvRuleWriter.write(List.of(TestRules.published("rul_A", TestRules.sydPerEconomyChoice())),
                TestRules.NOW);

        List<String> lines = csv.lines().toList();
        assertThat(lines).hasSize(2);
        assertThat(lines.get(0)).startsWith("airline,rule_id,version,status,origins");
        assertThat(lines.get(1)).isEqualTo(
                "VA,rul_A,1,PUBLISHED,SYD,PER,ECONOMY,CHOICE,,AU,CHOICE,47.8,AUD,100,70,5000,2026-10-01,2027-03-31,5,"
                        + "2026-10-09T05:30:00Z");
    }
}
