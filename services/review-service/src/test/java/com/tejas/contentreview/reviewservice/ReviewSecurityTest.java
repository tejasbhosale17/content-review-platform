package com.tejas.contentreview.reviewservice;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
@Import(InfraTestConfig.class)
class ReviewSecurityTest {

    @Autowired
    MockMvc mvc;

    @Test
    void healthIsPublicEverythingElseNeedsAdmin() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mvc.perform(get("/actuator/info")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401));
        mvc.perform(get("/actuator/info").with(user("someone").roles("CREATOR"))).andExpect(status().isForbidden());
        mvc.perform(get("/actuator/info").with(httpBasic("admin", "test-admin-pw"))).andExpect(status().isOk());
        mvc.perform(get("/actuator/info").with(httpBasic("admin", "wrong"))).andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/dlt/replay")).andExpect(status().isUnauthorized());
        mvc.perform(post("/internal/dlt/replay").with(user("someone").roles("REPORTER")))
                .andExpect(status().isForbidden());
    }
}
