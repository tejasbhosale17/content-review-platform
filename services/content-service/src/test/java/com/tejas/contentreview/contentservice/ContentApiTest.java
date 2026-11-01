package com.tejas.contentreview.contentservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@Import(InfraTestConfig.class)
class ContentApiTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper mapper;

    private static final String VALID = """
            {"title":"My video","licenseValid":false,"licenseNote":"expired"}""";

    @Test
    void healthEndpointIsPublic() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void createsContentOwnedByTheAuthenticatedCreator() throws Exception {
        String body = """
                {"creatorId":"someone-else","title":"My video","licenseValid":false,"licenseNote":"expired"}""";
        String response = mvc.perform(post("/api/contents").with(user("creator-1").roles("CREATOR"))
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.creatorId").value("creator-1"))
                .andExpect(jsonPath("$.visibility").value("VISIBLE"))
                .andExpect(jsonPath("$.appliedReviewRound").value(0))
                .andReturn().getResponse().getContentAsString();
        JsonNode created = mapper.readTree(response);
        String id = created.get("contentId").asText();
        assertThat(id).isNotBlank();

        mvc.perform(get("/api/contents/" + id).with(user("reporter-1").roles("REPORTER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.creatorId").value("creator-1"))
                .andExpect(jsonPath("$.licenseValid").value(false))
                .andExpect(jsonPath("$.licenseNote").value("expired"));
    }

    @Test
    void rejectsInvalidInput() throws Exception {
        mvc.perform(post("/api/contents").with(user("creator-1").roles("CREATOR"))
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details").isArray())
                .andExpect(jsonPath("$.details.length()").value(2));
        mvc.perform(post("/api/contents").with(user("creator-1").roles("CREATOR"))
                        .contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unknownContentIsNotFound() throws Exception {
        mvc.perform(get("/api/contents/does-not-exist").with(user("creator-1").roles("CREATOR")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void unauthenticatedRequestsGet401WithJsonError() throws Exception {
        mvc.perform(post("/api/contents").contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
        mvc.perform(get("/api/contents/x")).andExpect(status().isUnauthorized());
    }

    @Test
    void onlyCreatorsMayPublishContent() throws Exception {
        mvc.perform(post("/api/contents").with(user("reporter-1").roles("REPORTER"))
                        .contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403));
    }

    @Test
    void httpBasicWithConfiguredCredentialsWorksAndWrongPasswordIsRejected() throws Exception {
        mvc.perform(post("/api/contents").with(httpBasic("creator", "test-creator-pw"))
                        .contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.creatorId").value("creator"));
        mvc.perform(post("/api/contents").with(httpBasic("creator", "wrong"))
                        .contentType(MediaType.APPLICATION_JSON).content(VALID))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void operationalEndpointsAreAdminOnly() throws Exception {
        mvc.perform(get("/actuator/info")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/info").with(user("creator-1").roles("CREATOR"))).andExpect(status().isForbidden());
        mvc.perform(get("/actuator/info").with(user("admin").roles("ADMIN"))).andExpect(status().isOk());
        mvc.perform(post("/internal/dlt/replay").with(user("creator-1").roles("CREATOR")))
                .andExpect(status().isForbidden());
    }

    @Test
    void correlationIdIsEchoedOrGenerated() throws Exception {
        mvc.perform(get("/actuator/health").header("X-Correlation-Id", "abc-123"))
                .andExpect(header().string("X-Correlation-Id", "abc-123"));
        String generated = mvc.perform(get("/actuator/health").header("X-Correlation-Id", "bad value!"))
                .andReturn().getResponse().getHeader("X-Correlation-Id");
        assertThat(generated).isNotBlank().isNotEqualTo("bad value!");
    }
}
