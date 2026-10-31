package com.tejas.contentreview.caseservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tejas.contentreview.caseservice.cases.CaseRepository;
import com.tejas.contentreview.caseservice.cases.CaseStatus;
import com.tejas.contentreview.caseservice.content.ContentInfo;
import com.tejas.contentreview.caseservice.content.ContentLookup;
import com.tejas.contentreview.caseservice.projection.CaseProjectionService;
import com.tejas.contentreview.caseservice.reliability.DltReplayService;
import com.tejas.contentreview.contracts.events.EventCodec;
import com.tejas.contentreview.contracts.events.EventEnvelope;
import com.tejas.contentreview.contracts.events.ReviewCompletedPayload;
import com.tejas.contentreview.contracts.events.ReviewCompletedPayload.Decision;
import com.tejas.contentreview.contracts.events.Topics;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.kafka.KafkaContainer;

@SpringBootTest
@AutoConfigureMockMvc
@Import(InfraTestConfig.class)
class CaseReliabilityTest {

    private static final String DLT = Topics.deadLetter(Topics.REVIEW_COMPLETED, Topics.GROUP_CASE_PROJECTION);

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper mapper;
    @Autowired
    CaseRepository cases;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    KafkaTemplate<String, String> kafka;
    @Autowired
    KafkaContainer kafkaContainer;
    @Autowired
    DltReplayService replayService;
    @Autowired
    MeterRegistry registry;

    @MockitoBean
    ContentLookup contentLookup;
    @MockitoSpyBean
    CaseProjectionService projection;

    private String createCase() throws Exception {
        String contentId = UUID.randomUUID().toString();
        when(contentLookup.find(anyString())).thenAnswer(inv ->
                Optional.of(new ContentInfo(inv.getArgument(0), "creator-1")));
        String body = mvc.perform(post("/api/cases").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Actor-Id", "reporter-1")
                        .content("{\"contentId\":\"" + contentId + "\",\"reason\":\"test\"}"))
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(body).get("caseId").asText();
    }

    private static EventEnvelope<ReviewCompletedPayload> result(String caseId, Decision decision) {
        return new EventEnvelope<>(UUID.randomUUID(), "ReviewCompleted", 1, Instant.now(), caseId,
                UUID.randomUUID().toString(), 1, "corr", new ReviewCompletedPayload(decision, "demo-v1", "test"));
    }

    private ConsumerRecord<String, String> awaitDeadLetter(String key) {
        List<ConsumerRecord<String, String>> found = new ArrayList<>();
        try (var consumer = new KafkaConsumer<>(Map.<String, Object>of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaContainer.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "dlt-test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"), new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of(DLT));
            await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> {
                consumer.poll(Duration.ofMillis(200)).forEach(r -> {
                    if (key.equals(r.key())) {
                        found.add(r);
                    }
                });
                assertThat(found).isNotEmpty();
            });
        }
        return found.get(0);
    }

    @Test
    void caseAndReviewRequestOutboxRowAreCreatedTogetherAndThenPublished() throws Exception {
        String caseId = createCase();

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events WHERE message_key = ?", Integer.class, caseId))
                .isEqualTo(1);
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(200)).untilAsserted(() ->
                assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events WHERE message_key = ? AND published_at IS NOT NULL",
                        Integer.class, caseId)).isEqualTo(1));
    }

    @Test
    void receiptBlocksReprocessingOfTheSameEventIdEvenWhenStateWouldAllowIt() throws Exception {
        String caseId = createCase();
        var event = result(caseId, Decision.ALLOW);

        assertThat(projection.apply(event)).isTrue();
        assertThat(cases.findById(caseId).orElseThrow().getStatus()).isEqualTo(CaseStatus.DISMISSED);

        jdbc.update("UPDATE review_cases SET status = 'PENDING_REVIEW' WHERE case_id = ?", caseId);
        assertThat(projection.apply(event)).as("same event ID is a duplicate").isFalse();
        assertThat(cases.findById(caseId).orElseThrow().getStatus()).isEqualTo(CaseStatus.PENDING_REVIEW);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM processed_events WHERE consumer_group = ? AND event_id = ?",
                Integer.class, Topics.GROUP_CASE_PROJECTION, event.eventId().toString())).isEqualTo(1);

        assertThat(projection.apply(result(caseId, Decision.ALLOW))).as("a new event ID is applied").isTrue();
    }

    @Test
    void failedProjectionRollsBackItsReceiptSoRetryCanApply() throws Exception {
        String caseId = createCase();
        var event = result(caseId, Decision.RESTRICT);
        doThrow(new IllegalStateException("injected failure")).when(projection).apply(any());
        try {
            projection.apply(event);
        } catch (IllegalStateException expected) {
            // injected
        }
        Mockito.reset(projection);

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM processed_events WHERE event_id = ?", Integer.class,
                event.eventId().toString())).isZero();
        assertThat(projection.apply(event)).isTrue();
        assertThat(cases.findById(caseId).orElseThrow().getStatus()).isEqualTo(CaseStatus.RESTRICTED);
    }

    @Test
    void malformedResultIsDeadLetteredWithOriginalPayloadAndHeaders() {
        String key = UUID.randomUUID().toString();
        kafka.send(Topics.REVIEW_COMPLETED, key, "{broken");

        var dead = awaitDeadLetter(key);
        assertThat(dead.value()).isEqualTo("{broken");
        assertThat(new String(dead.headers().lastHeader(KafkaHeaders.DLT_ORIGINAL_TOPIC).value()))
                .isEqualTo(Topics.REVIEW_COMPLETED);
    }

    @Test
    void projectionFailureIsRetriedDeadLetteredAndReplayedAfterRecovery() throws Exception {
        String caseId = createCase();
        double deadBefore = registry.counter("kafka.consumer.dead_lettered", "group", Topics.GROUP_CASE_PROJECTION,
                "topic", Topics.REVIEW_COMPLETED).count();
        doThrow(new IllegalStateException("database down")).when(projection).apply(any());
        kafka.send(Topics.REVIEW_COMPLETED, caseId, EventCodec.toJson(result(caseId, Decision.ALLOW)));

        awaitDeadLetter(caseId);
        assertThat(cases.findById(caseId).orElseThrow().getStatus()).isEqualTo(CaseStatus.PENDING_REVIEW);
        assertThat(registry.counter("kafka.consumer.dead_lettered", "group", Topics.GROUP_CASE_PROJECTION,
                "topic", Topics.REVIEW_COMPLETED).count()).isEqualTo(deadBefore + 1);

        Mockito.reset(projection);
        assertThat(replayService.replay(DLT, Topics.GROUP_CASE_PROJECTION, 1000)).isGreaterThanOrEqualTo(1);
        await().atMost(Duration.ofSeconds(45)).pollInterval(Duration.ofMillis(200)).untilAsserted(() ->
                assertThat(cases.findById(caseId).orElseThrow().getStatus()).isEqualTo(CaseStatus.DISMISSED));
    }
}
