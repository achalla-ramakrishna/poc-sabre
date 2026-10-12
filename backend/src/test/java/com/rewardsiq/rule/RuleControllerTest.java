package com.rewardsiq.rule;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.rewardsiq.TestRules;
import com.rewardsiq.audit.AuditService;
import com.rewardsiq.common.config.RewardsIqProperties;
import com.rewardsiq.common.error.RuleValidationException;
import com.rewardsiq.common.web.CorrelationIdFilter;
import com.rewardsiq.publish.PublicationRepository;
import com.rewardsiq.security.CurrentUser;
import com.rewardsiq.security.SecurityConfig;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@WebMvcTest(RuleController.class)
@Import({SecurityConfig.class, CorrelationIdFilter.class})
@EnableConfigurationProperties(RewardsIqProperties.class)
@TestPropertySource(properties = "rewards.security.dev-secret=cmV3YXJkcy1pcS1sb2NhbC1kZXYtb25seS1zZWNyZXQtMzI=")
class RuleControllerTest {

    private static final String BODY = """
            {
              "name": "SYD-PER Economy Choice",
              "scope": { "origins": ["SYD"], "destinations": ["PER"], "cabins": ["ECONOMY"],
                         "fareBrands": ["CHOICE"], "bundles": [], "pointsOfSale": ["AU"] },
              "rates": [ { "fareBrand": "CHOICE", "pointsPerUnit": "47.8", "currency": "AUD", "roundingStep": 100 } ],
              "limits": { "maxRedeemPercent": 70, "minPoints": 5000 },
              "effectiveFrom": "2026-11-01",
              "effectiveTo": "2027-03-31"
            }""";

    @Autowired MockMvc mvc;
    @MockBean RuleService service;
    @MockBean AuditService audit;
    @MockBean PublicationRepository publications;

    private static MockHttpServletRequestBuilder as(MockHttpServletRequestBuilder request, String... roles) {
        return request.with(jwt()
                .jwt(j -> j.subject("u-48213").claim(CurrentUser.AIRLINE_CLAIM, "VA")
                        .claim(CurrentUser.ROLES_CLAIM, List.of(roles)))
                .authorities(java.util.Arrays.stream(roles)
                        .map(r -> (org.springframework.security.core.GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + r))
                        .toList()));
    }

    @Test
    void analyst_creates_a_rule_and_gets_location_and_etag() throws Exception {
        RedemptionRule saved = TestRules.withRevision(TestRules.draft("rul_01JA3K7Q2M", TestRules.sydPerEconomyChoice()), 0L);
        when(service.create(any(), any())).thenReturn(saved);

        mvc.perform(as(post("/api/v1/rules"), "ANALYST").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/v1/rules/rul_01JA3K7Q2M"))
                .andExpect(header().string("ETag", "\"0\""))
                .andExpect(header().exists(CorrelationIdFilter.HEADER))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.rates[0].pointsPerUnit").value("47.8"));
    }

    @Test
    void business_rule_errors_come_back_as_problem_details() throws Exception {
        when(service.create(any(), any())).thenThrow(new RuleValidationException("RULE_INVALID", "invalid",
                Map.of("limits.maxRedeemPercent", "must be between 0 and 100")));

        mvc.perform(as(post("/api/v1/rules"), "ANALYST").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(header().string("Content-Type", "application/problem+json"))
                .andExpect(jsonPath("$.errorCode").value("RULE_INVALID"))
                .andExpect(jsonPath("$.fieldErrors['limits.maxRedeemPercent']").exists())
                .andExpect(jsonPath("$.correlationId").exists());
    }

    @Test
    void missing_required_fields_are_400() throws Exception {
        mvc.perform(as(post("/api/v1/rules"), "ANALYST").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errorCode").value("VALIDATION_FAILED"));
    }

    @Test
    void no_token_is_401_and_viewer_cannot_create() throws Exception {
        mvc.perform(get("/api/v1/rules"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.errorCode").value("UNAUTHORIZED"));
        mvc.perform(as(post("/api/v1/rules"), "VIEWER").contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isForbidden());
        // Refused before the (invalid) body is even validated
        mvc.perform(as(post("/api/v1/rules"), "VIEWER").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isForbidden());
        mvc.perform(as(post("/api/v1/rules/rul_A/approve"), "ANALYST").header("If-Match", "\"1\""))
                .andExpect(status().isForbidden());
    }

    @Test
    void approve_requires_if_match_and_returns_202() throws Exception {
        RedemptionRule approved = TestRules.withRevision(TestRules.draft("rul_A", TestRules.sydPerEconomyChoice()), 4L);
        when(service.approve(eq("rul_A"), eq(3L), any())).thenReturn(approved);

        mvc.perform(as(post("/api/v1/rules/rul_A/approve"), "APPROVER"))
                .andExpect(status().isPreconditionRequired())
                .andExpect(jsonPath("$.errorCode").value("IF_MATCH_REQUIRED"));
        mvc.perform(as(post("/api/v1/rules/rul_A/approve"), "APPROVER").header("If-Match", "\"3\""))
                .andExpect(status().isAccepted())
                .andExpect(header().string("ETag", "\"4\""));
    }
}
