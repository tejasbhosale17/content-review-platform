package com.tejas.contentreview.caseservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tejas.contentreview.caseservice.content.ContentInfo;
import com.tejas.contentreview.caseservice.content.ContentLookup;
import com.tejas.contentreview.contracts.events.EventCodec;
import com.tejas.contentreview.contracts.events.ReviewRequestedPayload;
import com.tejas.contentreview.contracts.events.Topics;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@Import(InfraTestConfig.class)
class SecurityTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper mapper;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    MeterRegistry meters;

    @MockitoBean
    ContentLookup contentLookup;

    @BeforeEach
    void stubContent() {
        when(contentLookup.find(anyString())).thenAnswer(inv ->
                Optional.of(new ContentInfo(inv.getArgument(0), "creator-1")));
    }

    private String createCase(String correlationId) throws Exception {
        var request = post("/api/cases").with(user("reporter-1").roles("REPORTER"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"contentId\":\"" + UUID.randomUUID() + "\",\"reason\":\"r\"}");
        if (correlationId != null) {
            request.header("X-Correlation-Id", correlationId);
        }
        String body = mvc.perform(request).andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        return mapper.readTree(body).get("caseId").asText();
    }

    @Test
    void unauthenticatedAndWrongRoleRequestsAreRejected() throws Exception {
        String json = "{\"contentId\":\"c\",\"reason\":\"r\"}";
        mvc.perform(post("/api/cases").contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.status").value(401));
        mvc.perform(post("/api/cases").with(user("creator-1").roles("CREATOR"))
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.status").value(403));
        mvc.perform(post("/api/cases/x/appeals").with(user("reporter-1").roles("REPORTER"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"statement\":\"s\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void httpBasicWithConfiguredAccountWorks() throws Exception {
        mvc.perform(post("/api/cases").with(httpBasic("reporter", "test-reporter-pw"))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"contentId\":\"" + UUID.randomUUID() + "\",\"reason\":\"r\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.reporterId").value("reporter"));
        mvc.perform(post("/api/cases").with(httpBasic("reporter", "nope"))
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void caseIsVisibleOnlyToItsPartiesAndAdmins() throws Exception {
        String caseId = createCase(null);
        mvc.perform(get("/api/cases/" + caseId).with(user("reporter-1").roles("REPORTER"))).andExpect(status().isOk());
        mvc.perform(get("/api/cases/" + caseId).with(user("creator-1").roles("CREATOR"))).andExpect(status().isOk());
        mvc.perform(get("/api/cases/" + caseId).with(user("admin").roles("ADMIN"))).andExpect(status().isOk());
        mvc.perform(get("/api/cases/" + caseId).with(user("reporter-2").roles("REPORTER")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/cases/" + caseId).with(user("creator-2").roles("CREATOR")))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/cases/" + caseId)).andExpect(status().isUnauthorized());
    }

    @Test
    void onlyTheContentCreatorMayAppeal() throws Exception {
        String caseId = createCase(null);
        mvc.perform(post("/api/cases/" + caseId + "/appeals").with(user("creator-2").roles("CREATOR"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"statement\":\"mine\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/cases/missing/appeals").with(user("creator-1").roles("CREATOR"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"statement\":\"mine\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void operationalEndpointsAreAdminOnlyAndHealthIsPublic() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mvc.perform(get("/actuator/info")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/info").with(user("reporter-1").roles("REPORTER"))).andExpect(status().isForbidden());
        mvc.perform(post("/internal/dlt/replay").with(user("reporter-1").roles("REPORTER")))
                .andExpect(status().isForbidden());
        mvc.perform(post("/internal/dlt/replay")).andExpect(status().isUnauthorized());
    }

    @Test
    void correlationIdFromTheRequestReachesTheOutboxEventAndComplaintsAreCounted() throws Exception {
        double before = meters.counter("complaints.accepted").count();
        String caseId = createCase("corr-" + UUID.randomUUID());
        String payload = jdbc.queryForObject("SELECT payload FROM outbox_events WHERE topic = ? AND message_key = ?",
                String.class, Topics.REVIEW_REQUESTED, caseId);
        var event = EventCodec.fromJson(payload, ReviewRequestedPayload.class);
        assertThat(event.correlationId()).startsWith("corr-");
        assertThat(meters.counter("complaints.accepted").count()).isEqualTo(before + 1);
    }

    @Test
    void generatedCorrelationIdIsUsedWhenNoHeaderIsSent() throws Exception {
        String caseId = createCase(null);
        String payload = jdbc.queryForObject("SELECT payload FROM outbox_events WHERE topic = ? AND message_key = ?",
                String.class, Topics.REVIEW_REQUESTED, caseId);
        assertThat(EventCodec.fromJson(payload, ReviewRequestedPayload.class).correlationId()).isNotBlank();
    }
}
