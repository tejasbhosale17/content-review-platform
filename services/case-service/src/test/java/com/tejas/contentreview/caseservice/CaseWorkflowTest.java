package com.tejas.contentreview.caseservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tejas.contentreview.caseservice.cases.CaseEntity;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.kafka.KafkaContainer;

@SpringBootTest
@AutoConfigureMockMvc
@Import(InfraTestConfig.class)
class CaseWorkflowTest {

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper mapper;
    @Autowired
    CaseRepository cases;
    @Autowired
    KafkaTemplate<String, String> kafka;
    @Autowired
    KafkaContainer kafkaContainer;

    @MockitoBean
    ContentLookup contentLookup;

    private String createCase(String contentId) throws Exception {
        when(contentLookup.find(anyString())).thenAnswer(inv ->
                Optional.of(new ContentInfo(inv.getArgument(0), "creator-1")));
        String body = mvc.perform(post("/api/cases").contentType(MediaType.APPLICATION_JSON)
                        .header("X-Actor-Id", "reporter-1")
                        .content("{\"contentId\":\"" + contentId + "\",\"reason\":\"test\"}"))
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(body).get("caseId").asText();
    }

    private void sendResult(String caseId, String contentId, int round, Decision decision, UUID eventId) {
        var event = new EventEnvelope<>(eventId, "ReviewCompleted", 1, Instant.now(), caseId, contentId, round,
                "corr", new ReviewCompletedPayload(decision, "demo-v1", "test"));
        kafka.send(Topics.REVIEW_COMPLETED, caseId, EventCodec.toJson(event));
    }

    private CaseEntity reload(String caseId) {
        return cases.findById(caseId).orElseThrow();
    }

    private void awaitStatus(String caseId, CaseStatus expected) {
        await().atMost(Duration.ofSeconds(45)).pollInterval(Duration.ofMillis(200))
                .untilAsserted(() -> assertThat(reload(caseId).getStatus()).isEqualTo(expected));
    }

    @Test
    void newComplaintPublishesReviewRequestKeyedByCaseId() throws Exception {
        String contentId = UUID.randomUUID().toString();
        String caseId = createCase(contentId);

        try (var consumer = new KafkaConsumer<>(Map.<String, Object>of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaContainer.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"), new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of(Topics.REVIEW_REQUESTED));
            var found = new java.util.concurrent.atomic.AtomicReference<ConsumerRecord<String, String>>();
            await().atMost(Duration.ofSeconds(45)).untilAsserted(() -> {
                consumer.poll(Duration.ofMillis(200)).forEach(r -> {
                    if (r.key().equals(caseId)) {
                        found.set(r);
                    }
                });
                assertThat(found.get()).isNotNull();
            });
            var event = EventCodec.fromJson(found.get().value(), ReviewRequestedPayload.class);
            assertThat(event.caseId()).isEqualTo(caseId);
            assertThat(event.contentId()).isEqualTo(contentId);
            assertThat(event.reviewRound()).isEqualTo(1);
            assertThat(event.eventType()).isEqualTo("ReviewRequested");
            assertThat(event.schemaVersion()).isEqualTo(1);
            assertThat(event.correlationId()).isNotBlank();
        }
    }

    @Test
    void allowResultDismissesCase() throws Exception {
        String contentId = UUID.randomUUID().toString();
        String caseId = createCase(contentId);
        sendResult(caseId, contentId, 1, Decision.ALLOW, UUID.randomUUID());
        awaitStatus(caseId, CaseStatus.DISMISSED);
    }

    @Test
    void restrictResultRestrictsCase() throws Exception {
        String contentId = UUID.randomUUID().toString();
        String caseId = createCase(contentId);
        sendResult(caseId, contentId, 1, Decision.RESTRICT, UUID.randomUUID());
        awaitStatus(caseId, CaseStatus.RESTRICTED);
    }

    @Test
    void duplicateAndConflictingLaterResultsDoNotChangeAnAppliedOutcome() throws Exception {
        String contentId = UUID.randomUUID().toString();
        String caseId = createCase(contentId);
        UUID eventId = UUID.randomUUID();
        sendResult(caseId, contentId, 1, Decision.RESTRICT, eventId);
        awaitStatus(caseId, CaseStatus.RESTRICTED);
        Instant updatedAt = reload(caseId).getUpdatedAt();

        sendResult(caseId, contentId, 1, Decision.RESTRICT, eventId);
        sendResult(caseId, contentId, 1, Decision.ALLOW, UUID.randomUUID());
        awaitProjectionCaughtUp();

        assertThat(reload(caseId).getStatus()).isEqualTo(CaseStatus.RESTRICTED);
        assertThat(reload(caseId).getUpdatedAt()).isEqualTo(updatedAt);
    }

    /** Waits until the case-projection group has committed every record currently in the topic. */
    private void awaitProjectionCaughtUp() throws Exception {
        String bootstrap = kafkaContainer.getBootstrapServers();
        try (var admin = org.apache.kafka.clients.admin.AdminClient.create(
                Map.<String, Object>of("bootstrap.servers", bootstrap));
             var consumer = new KafkaConsumer<>(Map.<String, Object>of(
                     ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap,
                     ConsumerConfig.GROUP_ID_CONFIG, "probe-" + UUID.randomUUID()),
                     new StringDeserializer(), new StringDeserializer())) {
            var partitions = consumer.partitionsFor(Topics.REVIEW_COMPLETED).stream()
                    .map(p -> new org.apache.kafka.common.TopicPartition(p.topic(), p.partition())).toList();
            var end = consumer.endOffsets(partitions);
            await().atMost(Duration.ofSeconds(45)).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> {
                var committed = admin.listConsumerGroupOffsets(Topics.GROUP_CASE_PROJECTION)
                        .partitionsToOffsetAndMetadata().get();
                end.forEach((tp, endOffset) -> {
                    long done = committed.containsKey(tp) ? committed.get(tp).offset() : 0;
                    assertThat(done).as("committed offset for %s", tp).isGreaterThanOrEqualTo(endOffset);
                });
            });
        }
    }

    @Test
    void roundTwoResultIsIgnoredWhileNoAppealIsPending() throws Exception {
        String contentId = UUID.randomUUID().toString();
        String caseId = createCase(contentId);
        sendResult(caseId, contentId, 2, Decision.ALLOW, UUID.randomUUID());
        sendResult(caseId, contentId, 1, Decision.RESTRICT, UUID.randomUUID());
        awaitStatus(caseId, CaseStatus.RESTRICTED);
    }

    @Test
    void resultForUnknownCaseIsIgnoredWithoutBlockingOthers() throws Exception {
        sendResult(UUID.randomUUID().toString(), UUID.randomUUID().toString(), 1, Decision.ALLOW, UUID.randomUUID());
        String contentId = UUID.randomUUID().toString();
        String caseId = createCase(contentId);
        sendResult(caseId, contentId, 1, Decision.ALLOW, UUID.randomUUID());
        awaitStatus(caseId, CaseStatus.DISMISSED);
    }
}
