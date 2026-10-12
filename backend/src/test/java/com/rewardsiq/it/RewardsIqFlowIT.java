package com.rewardsiq.it;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rewardsiq.pricing.OfferQuery;
import com.rewardsiq.pricing.PointsResult;
import com.rewardsiq.pricing.PricingService;
import com.rewardsiq.publish.PublicationRepository;
import com.rewardsiq.publish.PublicationStatus;
import com.rewardsiq.publish.PublisherWorker;
import com.rewardsiq.publish.batch.BatchExportJob;
import com.rewardsiq.rule.Cabin;
import com.rewardsiq.rule.RuleRepository;
import com.rewardsiq.rule.RuleStatus;
import com.rewardsiq.security.CurrentUser;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.OptionalLong;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.http.MediaType;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * The whole publish path against a real MongoDB replica set (transactions) and the stub DPE over
 * real gRPC: create -> submit -> approve -> worker publishes -> cache -> pricing -> batch file.
 * Runs in `mvn verify`; skipped automatically where Docker isn't available.
 */
@SpringBootTest(properties = {
        "rewards.grpc.enabled=false",
        "rewards.dpe.stub-port=19191",
        "rewards.dpe.target=localhost:19191",
        "rewards.publish.poll-interval=PT1H",
        "rewards.cache.refresh-interval=PT1H"})
@AutoConfigureMockMvc
@ActiveProfiles("dev")
@Testcontainers(disabledWithoutDocker = true)
class RewardsIqFlowIT {

    @Container
    static final MongoDBContainer MONGO = new MongoDBContainer("mongo:7.0");
    static Path exportDir;

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) throws Exception {
        exportDir = Files.createTempDirectory("rewardsiq-export");
        registry.add("spring.data.mongodb.uri", () -> MONGO.getReplicaSetUrl("rewardsiq-it"));
        registry.add("rewards.batch.directory", () -> exportDir.toString());
    }

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired MongoTemplate mongo;
    @Autowired PublisherWorker worker;
    @Autowired PublicationRepository publications;
    @Autowired RuleRepository rules;
    @Autowired PricingService pricing;
    @Autowired BatchExportJob batch;

    @BeforeEach
    void clean() {
        mongo.getDb().getCollection("redemptionRules").deleteMany(new org.bson.Document());
        mongo.getDb().getCollection("rulePublications").deleteMany(new org.bson.Document());
        mongo.getDb().getCollection("auditEvents").deleteMany(new org.bson.Document());
    }

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder req, String subject, String role) {
        List<GrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("ROLE_" + role));
        return req.with(jwt().jwt(j -> j.subject(subject).claim(CurrentUser.AIRLINE_CLAIM, "VA")
                .claim(CurrentUser.ROLES_CLAIM, List.of(role))).authorities(authorities));
    }

    @Test
    void rule_goes_from_draft_to_priced_offer() throws Exception {
        String body = """
                { "name": "SYD-PER Economy Choice",
                  "scope": { "origins": ["SYD"], "destinations": ["PER"], "cabins": ["ECONOMY"],
                             "fareBrands": ["CHOICE"], "pointsOfSale": ["AU"] },
                  "rates": [ { "fareBrand": "CHOICE", "pointsPerUnit": "47.8", "currency": "AUD", "roundingStep": 100 } ],
                  "limits": { "maxRedeemPercent": 70, "minPoints": 5000 },
                  "effectiveFrom": "2026-01-01" }""";

        MvcResult created = mvc.perform(as(post("/api/v1/rules"), "u-analyst", "ANALYST")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        JsonNode rule = json.readTree(created.getResponse().getContentAsString());
        String ruleId = rule.get("ruleId").asText();
        String etag = created.getResponse().getHeader("ETag");

        MvcResult submitted = mvc.perform(as(post("/api/v1/rules/" + ruleId + "/submit"), "u-analyst", "ANALYST")
                        .header("If-Match", etag))
                .andExpect(jsonPath("$.status").value("PENDING_APPROVAL")).andReturn();

        // Maker-checker: the analyst can't approve, a different approver can.
        mvc.perform(as(post("/api/v1/rules/" + ruleId + "/approve"), "u-analyst", "ANALYST")
                .header("If-Match", submitted.getResponse().getHeader("ETag"))).andExpect(status().isForbidden());
        mvc.perform(as(post("/api/v1/rules/" + ruleId + "/approve"), "u-approver", "APPROVER")
                        .header("If-Match", submitted.getResponse().getHeader("ETag")))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("APPROVED"));
        assertThat(publications.findAll()).singleElement()
                .satisfies(p -> assertThat(p.getStatus()).isEqualTo(PublicationStatus.PENDING));

        worker.drain(); // delivers to the stub DPE over gRPC

        assertThat(publications.findAll()).singleElement().satisfies(p -> {
            assertThat(p.getStatus()).isEqualTo(PublicationStatus.ACKED);
            assertThat(p.getDpeReference()).startsWith("dpe-");
        });
        assertThat(rules.findFirstByAirlineAndRuleIdOrderByVersionDesc("VA", ruleId))
                .hasValueSatisfying(r -> assertThat(r.getStatus()).isEqualTo(RuleStatus.PUBLISHED));

        List<PointsResult> priced = pricing.price("VA", List.of(new OfferQuery("of-1", "SYD", "PER", Cabin.ECONOMY,
                "CHOICE", null, "AU", new BigDecimal("900.00"), "AUD")), OptionalLong.of(100_000));
        assertThat(priced).singleElement().satisfies(p -> {
            assertThat(p.pointsRequired()).isEqualTo(43_100);
            assertThat(p.pointsUsable()).isEqualTo(43_100);
        });

        List<String> files = batch.exportAll();
        assertThat(files).singleElement();
        assertThat(Files.readString(exportDir.resolve(files.get(0)))).contains(ruleId + ",1,PUBLISHED,SYD,PER");
    }
}
