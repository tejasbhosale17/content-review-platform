package com.tejas.contentreview.caseservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tejas.contentreview.caseservice.cases.CaseRepository;
import com.tejas.contentreview.caseservice.cases.CaseStatus;
import com.tejas.contentreview.caseservice.content.ContentInfo;
import com.tejas.contentreview.caseservice.content.ContentLookup;
import com.tejas.contentreview.contracts.events.EventCodec;
import com.tejas.contentreview.contracts.events.EventEnvelope;
import com.tejas.contentreview.contracts.events.ReviewCompletedPayload;
import com.tejas.contentreview.contracts.events.ReviewCompletedPayload.Decision;
import com.tejas.contentreview.contracts.events.ReviewRequestedPayload;
import com.tejas.contentreview.contracts.events.Topics;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
@Import(InfraTestConfig.class)
class AppealTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper mapper;
    @Autowired
    CaseRepository cases;
    @Autowired
    KafkaTemplate<String, String> kafka;
    @Autowired
    JdbcTemplate jdbc;

    @MockitoBean
    ContentLookup contentLookup;

    private record Created(String caseId, String contentId) {
    }

    private Created createCase() throws Exception {
        String contentId = UUID.randomUUID().toString();
        when(contentLookup.find(anyString())).thenAnswer(inv ->
                Optional.of(new ContentInfo(inv.getArgument(0), "creator-1")));
        String body = mvc.perform(post("/api/cases").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Actor-Id", "reporter-1")
                        .content("{\"contentId\":\"" + contentId + "\",\"reason\":\"test\"}"))
                .andReturn().getResponse().getContentAsString();
        return new Created(mapper.readTree(body).get("caseId").asText(), contentId);
    }

    private Created restrictedCase() throws Exception {
        Created c = createCase();
        sendResult(c, 1, Decision.RESTRICT);
        awaitStatus(c.caseId(), CaseStatus.RESTRICTED);
        return c;
    }

    private void sendResult(Created c, int round, Decision decision) {
        var event = new EventEnvelope<>(UUID.randomUUID(), "ReviewCompleted", 1, Instant.now(), c.caseId(), c.contentId(),
                round, "corr", new ReviewCompletedPayload(decision, "demo-v1", "test"));
        kafka.send(Topics.REVIEW_COMPLETED, c.caseId(), EventCodec.toJson(event));
    }

    private void awaitStatus(String caseId, CaseStatus expected) {
        await().atMost(Duration.ofSeconds(45)).pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> assertThat(cases.findById(caseId).orElseThrow().getStatus()).isEqualTo(expected));
    }

    private MvcResult appeal(String caseId, String key, String statement) throws Exception {
        var request = post("/api/cases/" + caseId + "/appeals").contentType(MediaType.APPLICATION_JSON)
                .header("X-Actor-Id", "creator-1")
                .content("{\"statement\":\"" + statement + "\"}");
        if (key != null) {
            request.header("Idempotency-Key", key);
        }
        return mvc.perform(request).andReturn();
    }

    private int roundTwoRequests(String caseId) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events WHERE topic = ? AND message_key = ? "
                + "AND payload LIKE '%\"reviewRound\":2%'", Integer.class, Topics.REVIEW_REQUESTED, caseId);
    }

    @Test
    void appealOnRestrictedCaseStartsRoundTwoAndPublishesAppealRequest() throws Exception {
        Created c = restrictedCase();

        MvcResult result = appeal(c.caseId(), null, "Here is my license");
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        JsonNode body = mapper.readTree(result.getResponse().getContentAsString());
        assertThat(body.get("caseStatus").asText()).isEqualTo("APPEAL_PENDING");
        assertThat(body.get("reviewRound").asInt()).isEqualTo(2);
        assertThat(body.get("appealId").asText()).isNotBlank();

        mvc.perform(get("/api/cases/" + c.caseId()))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.status").value("APPEAL_PENDING"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.reviewRound").value(2));

        String payload = jdbc.queryForObject("SELECT payload FROM outbox_events WHERE topic = ? AND message_key = ? "
                + "AND payload LIKE '%\"reviewRound\":2%'", String.class, Topics.REVIEW_REQUESTED, c.caseId());
        var event = EventCodec.fromJson(payload, ReviewRequestedPayload.class);
        assertThat(event.reviewRound()).isEqualTo(2);
        assertThat(event.payload().appeal()).isTrue();
        assertThat(event.payload().reason()).isEqualTo("Here is my license");
    }

    @Test
    void appealIsRejectedUnlessCaseIsRestricted() throws Exception {
        Created pending = createCase();
        assertThat(appeal(pending.caseId(), null, "x").getResponse().getStatus()).isEqualTo(409);

        Created dismissed = createCase();
        sendResult(dismissed, 1, Decision.ALLOW);
        awaitStatus(dismissed.caseId(), CaseStatus.DISMISSED);
        assertThat(appeal(dismissed.caseId(), null, "x").getResponse().getStatus()).isEqualTo(409);

        assertThat(appeal(UUID.randomUUID().toString(), null, "x").getResponse().getStatus()).isEqualTo(404);
        assertThat(appeal(pending.caseId(), null, " ").getResponse().getStatus()).isEqualTo(400);
        assertThat(roundTwoRequests(pending.caseId()) + roundTwoRequests(dismissed.caseId())).isZero();
    }

    @Test
    void secondAppealIsRejectedAndNoSecondRequestIsPublished() throws Exception {
        Created c = restrictedCase();
        assertThat(appeal(c.caseId(), null, "first").getResponse().getStatus()).isEqualTo(201);
        assertThat(appeal(c.caseId(), null, "second").getResponse().getStatus()).isEqualTo(409);

        sendResult(c, 2, Decision.RESTRICT);
        awaitStatus(c.caseId(), CaseStatus.RESTRICTION_CONFIRMED);
        assertThat(appeal(c.caseId(), null, "third").getResponse().getStatus()).isEqualTo(409);

        assertThat(roundTwoRequests(c.caseId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM appeals WHERE case_id = ?", Integer.class, c.caseId())).isEqualTo(1);
    }

    @Test
    void roundTwoAllowRestoresAndRoundTwoRestrictConfirms() throws Exception {
        Created allowed = restrictedCase();
        appeal(allowed.caseId(), null, "evidence");
        sendResult(allowed, 2, Decision.ALLOW);
        awaitStatus(allowed.caseId(), CaseStatus.RESTORED);

        Created confirmed = restrictedCase();
        appeal(confirmed.caseId(), null, "evidence");
        sendResult(confirmed, 2, Decision.RESTRICT);
        awaitStatus(confirmed.caseId(), CaseStatus.RESTRICTION_CONFIRMED);
    }

    @Test
    void lateRoundOneResultCannotOverwriteAnAppealInProgressOrItsOutcome() throws Exception {
        Created c = restrictedCase();
        appeal(c.caseId(), null, "evidence");

        // A delayed or replayed round-1 result arrives while the appeal is pending, then round 2 restores the case.
        sendResult(c, 1, Decision.ALLOW);
        sendResult(c, 1, Decision.RESTRICT);
        sendResult(c, 2, Decision.ALLOW);
        awaitStatus(c.caseId(), CaseStatus.RESTORED);

        sendResult(c, 1, Decision.RESTRICT);
        sendResult(c, 2, Decision.RESTRICT);
        Created marker = createCase();
        sendResult(marker, 1, Decision.ALLOW);
        awaitStatus(marker.caseId(), CaseStatus.DISMISSED);

        assertThat(cases.findById(c.caseId()).orElseThrow().getStatus()).isEqualTo(CaseStatus.RESTORED);
    }

    @Test
    void sameIdempotencyKeyReplaysAndDifferentPayloadConflicts() throws Exception {
        Created c = restrictedCase();
        String key = "appeal-" + UUID.randomUUID();

        MvcResult first = appeal(c.caseId(), key, "same");
        MvcResult replay = appeal(c.caseId(), key, "same");
        MvcResult changed = appeal(c.caseId(), key, "different");

        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        assertThat(replay.getResponse().getStatus()).isEqualTo(201);
        assertThat(replay.getResponse().getHeader("Idempotency-Replayed")).isEqualTo("true");
        assertThat(mapper.readTree(replay.getResponse().getContentAsString()).get("appealId").asText())
                .isEqualTo(mapper.readTree(first.getResponse().getContentAsString()).get("appealId").asText());
        assertThat(changed.getResponse().getStatus()).isEqualTo(409);
        assertThat(roundTwoRequests(c.caseId())).isEqualTo(1);
    }

    @Test
    void concurrentAppealsProduceExactlyOneWinner() throws Exception {
        Created c = restrictedCase();
        int threads = 8;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            String key = "k-" + i + "-" + UUID.randomUUID();
            futures.add(pool.submit(() -> {
                start.await();
                return appeal(c.caseId(), key, "racing").getResponse().getStatus();
            }));
        }
        start.countDown();
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> f : futures) {
            statuses.add(f.get());
        }
        pool.shutdown();

        assertThat(statuses.stream().filter(s -> s == 201)).hasSize(1);
        assertThat(statuses.stream().filter(s -> s == 409)).hasSize(threads - 1);
        assertThat(roundTwoRequests(c.caseId())).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM appeals WHERE case_id = ?", Integer.class, c.caseId())).isEqualTo(1);
    }
}
