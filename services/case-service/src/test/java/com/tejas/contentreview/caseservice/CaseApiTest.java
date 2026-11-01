package com.tejas.contentreview.caseservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tejas.contentreview.caseservice.cases.CaseRepository;
import com.tejas.contentreview.caseservice.content.ContentInfo;
import com.tejas.contentreview.caseservice.content.ContentLookup;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

@SpringBootTest
@AutoConfigureMockMvc
@Import(InfraTestConfig.class)
class CaseApiTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper mapper;
    @Autowired
    CaseRepository cases;

    // Content service is replaced only here; the real HTTP client is exercised against a stub server separately.
    @MockitoBean
    ContentLookup contentLookup;

    @BeforeEach
    void stubContent() {
        when(contentLookup.find(anyString())).thenAnswer(inv ->
                inv.getArgument(0).toString().startsWith("missing") ? Optional.empty()
                        : Optional.of(new ContentInfo(inv.getArgument(0), "creator-1")));
    }

    private String newContentId() {
        return UUID.randomUUID().toString();
    }

    private MockHttpServletRequestBuilder complaint(String actor, String key, String contentId, String reason) {
        MockHttpServletRequestBuilder b = post("/api/cases").contentType(MediaType.APPLICATION_JSON)
                .content("{\"contentId\":\"" + contentId + "\",\"reason\":\"" + reason + "\"}");
        if (actor != null) {
            b.with(user(actor).roles("REPORTER"));
        }
        if (key != null) {
            b.header("Idempotency-Key", key);
        }
        return b;
    }

    @Test
    void createsComplaintInPendingReviewRoundOne() throws Exception {
        String contentId = newContentId();
        String body = mvc.perform(complaint("reporter-1", null, contentId, "stolen footage"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING_REVIEW"))
                .andExpect(jsonPath("$.reviewRound").value(1))
                .andExpect(jsonPath("$.contentId").value(contentId))
                .andExpect(jsonPath("$.reporterId").value("reporter-1"))
                .andReturn().getResponse().getContentAsString();
        String caseId = mapper.readTree(body).get("caseId").asText();

        mvc.perform(get("/api/cases/" + caseId).with(user("reporter-1").roles("REPORTER")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.caseId").value(caseId))
                .andExpect(jsonPath("$.status").value("PENDING_REVIEW"));
    }

    @Test
    void rejectsInvalidInputAndUnknownIds() throws Exception {
        mvc.perform(complaint("reporter-1", null, " ", "")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.details.length()").value(2));
        mvc.perform(complaint(null, null, newContentId(), "x")).andExpect(status().isUnauthorized());
        mvc.perform(complaint("reporter-1", " ", newContentId(), "x")).andExpect(status().isBadRequest());
        mvc.perform(complaint("reporter-1", "k".repeat(129), newContentId(), "x")).andExpect(status().isBadRequest());
        mvc.perform(complaint("reporter-1", null, "missing-1", "x")).andExpect(status().isNotFound());
        mvc.perform(get("/api/cases/unknown").with(user("admin").roles("ADMIN"))).andExpect(status().isNotFound());
    }

    @Test
    void contentServiceOutageYields503() throws Exception {
        when(contentLookup.find("down-1")).thenThrow(new ContentLookup.ContentUnavailableException("boom", null));
        mvc.perform(complaint("reporter-1", null, "down-1", "x")).andExpect(status().isServiceUnavailable());
        assertThat(cases.findAll().stream().filter(c -> c.getContentId().equals("down-1"))).isEmpty();
    }

    @Test
    void secondComplaintForSameContentIsConflict() throws Exception {
        String contentId = newContentId();
        mvc.perform(complaint("reporter-1", null, contentId, "a")).andExpect(status().isCreated());
        mvc.perform(complaint("reporter-2", null, contentId, "b")).andExpect(status().isConflict());
    }

    @Test
    void sameKeySamePayloadReturnsOriginalResult() throws Exception {
        String contentId = newContentId();
        String key = UUID.randomUUID().toString();
        String first = mvc.perform(complaint("reporter-1", key, contentId, "dup"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        MvcResult second = mvc.perform(complaint("reporter-1", key, contentId, "dup"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotency-Replayed", "true")).andReturn();
        assertThat(mapper.readTree(second.getResponse().getContentAsString()))
                .isEqualTo(mapper.readTree(first));
        assertThat(cases.findAll().stream().filter(c -> c.getContentId().equals(contentId))).hasSize(1);
    }

    @Test
    void sameKeyDifferentPayloadIsConflict() throws Exception {
        String key = UUID.randomUUID().toString();
        mvc.perform(complaint("reporter-1", key, newContentId(), "one")).andExpect(status().isCreated());
        mvc.perform(complaint("reporter-1", key, newContentId(), "two")).andExpect(status().isConflict());
    }

    @Test
    void concurrentSubmissionsWithSameKeyCreateOneCase() throws Exception {
        String contentId = newContentId();
        String key = UUID.randomUUID().toString();
        List<MvcResult> results = runConcurrently(8, i -> mvc.perform(complaint("reporter-1", key, contentId, "race")).andReturn());
        List<String> caseIds = new ArrayList<>();
        for (MvcResult r : results) {
            assertThat(r.getResponse().getStatus()).isEqualTo(201);
            caseIds.add(mapper.readTree(r.getResponse().getContentAsString()).get("caseId").asText());
        }
        assertThat(caseIds).hasSize(8).containsOnly(caseIds.get(0));
        assertThat(cases.findAll().stream().filter(c -> c.getContentId().equals(contentId))).hasSize(1);
    }

    @Test
    void concurrentSubmissionsWithDifferentKeysCreateOneCase() throws Exception {
        String contentId = newContentId();
        List<MvcResult> results = runConcurrently(8,
                i -> mvc.perform(complaint("reporter-" + i, UUID.randomUUID().toString(), contentId, "race")).andReturn());
        long created = results.stream().filter(r -> r.getResponse().getStatus() == 201).count();
        long conflicts = results.stream().filter(r -> r.getResponse().getStatus() == 409).count();
        assertThat(created).isEqualTo(1);
        assertThat(conflicts).isEqualTo(7);
        assertThat(cases.findAll().stream().filter(c -> c.getContentId().equals(contentId))).hasSize(1);
    }

    private <T> List<T> runConcurrently(int threads, ThrowingFunction<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                int n = i;
                Callable<T> call = () -> {
                    start.await();
                    return task.apply(n);
                };
                futures.add(pool.submit(call));
            }
            start.countDown();
            List<T> out = new ArrayList<>();
            for (Future<T> f : futures) {
                out.add(f.get(30, TimeUnit.SECONDS));
            }
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    @FunctionalInterface
    interface ThrowingFunction<T> {
        T apply(int i) throws Exception;
    }

    @Test
    void healthEndpointIsUp() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("UP"));
    }
}
