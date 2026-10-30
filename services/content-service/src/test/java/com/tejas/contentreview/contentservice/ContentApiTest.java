package com.tejas.contentreview.contentservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

    @Test
    void healthEndpointIsUp() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void createsAndFetchesContent() throws Exception {
        String body = """
                {"creatorId":"creator-1","title":"My video","licenseValid":false,"licenseNote":"expired"}""";
        String response = mvc.perform(post("/api/contents").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.visibility").value("VISIBLE"))
                .andExpect(jsonPath("$.appliedReviewRound").value(0))
                .andReturn().getResponse().getContentAsString();
        JsonNode created = mapper.readTree(response);
        String id = created.get("contentId").asText();
        assertThat(id).isNotBlank();

        mvc.perform(get("/api/contents/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.creatorId").value("creator-1"))
                .andExpect(jsonPath("$.licenseValid").value(false))
                .andExpect(jsonPath("$.licenseNote").value("expired"));
    }

    @Test
    void rejectsInvalidInput() throws Exception {
        mvc.perform(post("/api/contents").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"creatorId\":\" \",\"title\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details").isArray())
                .andExpect(jsonPath("$.details.length()").value(3));
        mvc.perform(post("/api/contents").contentType(MediaType.APPLICATION_JSON).content("{not json"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unknownContentIsNotFound() throws Exception {
        mvc.perform(get("/api/contents/does-not-exist"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.status").value(404));
    }
}
