package com.tejas.contentreview.reviewservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import com.tejas.contentreview.contracts.events.EventCodec;
import com.tejas.contentreview.contracts.events.EventEnvelope;
import com.tejas.contentreview.contracts.events.ReviewCompletedPayload;
import com.tejas.contentreview.contracts.events.ReviewCompletedPayload.Decision;
import com.tejas.contentreview.contracts.events.ReviewRequestedPayload;
import com.tejas.contentreview.contracts.events.Topics;
import com.tejas.contentreview.reviewservice.content.ContentMetadata;
import com.tejas.contentreview.reviewservice.content.ContentMetadataClient;
import com.tejas.contentreview.reviewservice.reliability.DltReplayService;
import com.tejas.contentreview.reviewservice.review.ReviewDecisionRepository;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.support.KafkaHeaders;
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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.kafka.KafkaContainer;

@SpringBootTest
@Import(InfraTestConfig.class)
class ReviewWorkflowTest {

    @Autowired
    KafkaTemplate<String, String> kafka;
    @Autowired
    KafkaContainer kafkaContainer;
    @Autowired
    ReviewDecisionRepository decisions;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    MeterRegistry registry;
    @Autowired
    DltReplayService replayService;
    @Autowired
    org.springframework.kafka.config.KafkaListenerEndpointRegistry listenerRegistry;

    @MockitoBean
    ContentMetadataClient metadataClient;

    KafkaConsumer<String, String> resultConsumer;
    final List<ConsumerRecord<String, String>> results = new ArrayList<>();

    @BeforeEach
    void startResultConsumer() {
        resultConsumer = new KafkaConsumer<>(Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaContainer.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"),
                new StringDeserializer(), new StringDeserializer());
        resultConsumer.subscribe(List.of(Topics.REVIEW_COMPLETED));
    }

    @AfterEach
    void stopResultConsumer() {
        resultConsumer.close(Duration.ofSeconds(2));
    }

    private void stubContent(String contentId, boolean licenseValid, String note) {
        when(metadataClient.get(contentId))
                .thenReturn(Optional.of(new ContentMetadata(contentId, "creator-1", licenseValid, note)));
    }

    private String send(String caseId, String contentId, int round, UUID eventId) {
        var event = new EventEnvelope<>(eventId, "ReviewRequested", 1, Instant.now(), caseId, contentId, round,
                "corr-" + caseId, new ReviewRequestedPayload("test", round == 2));
        kafka.send(Topics.REVIEW_REQUESTED, caseId, EventCodec.toJson(event));
        return caseId;
    }

    private List<ConsumerRecord<String, String>> awaitResults(String caseId, int expected) {
        await().atMost(Duration.ofSeconds(45)).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> {
            resultConsumer.poll(Duration.ofMillis(200)).forEach(results::add);
            assertThat(results.stream().filter(r -> r.key().equals(caseId))).hasSize(expected);
        });
        return results.stream().filter(r -> r.key().equals(caseId)).toList();
    }

    private static EventEnvelope<ReviewCompletedPayload> parse(ConsumerRecord<String, String> r) {
        return EventCodec.fromJson(r.value(), ReviewCompletedPayload.class);
    }

    @Test
    void validLicenseProducesAllow() {
        String caseId = UUID.randomUUID().toString();
        String contentId = UUID.randomUUID().toString();
        stubContent(contentId, true, null);
        send(caseId, contentId, 1, UUID.randomUUID());

        var result = parse(awaitResults(caseId, 1).get(0));
        assertThat(result.payload().decision()).isEqualTo(Decision.ALLOW);
        assertThat(result.reviewRound()).isEqualTo(1);
        assertThat(result.contentId()).isEqualTo(contentId);
        assertThat(result.correlationId()).isEqualTo("corr-" + caseId);
        assertThat(result.eventType()).isEqualTo("ReviewCompleted");
        assertThat(decisions.findByCaseIdAndReviewRound(caseId, 1)).isPresent();
    }

    @Test
    void invalidLicenseProducesRestrict() {
        String caseId = UUID.randomUUID().toString();
        String contentId = UUID.randomUUID().toString();
        stubContent(contentId, false, null);
        double completedBefore = registry.counter("reviews.completed", "decision", "RESTRICT", "round", "1").count();
        long processedBefore = registry.timer("kafka.consumer.processing", "group", Topics.GROUP_REVIEW_WORKERS).count();
        send(caseId, contentId, 1, UUID.randomUUID());

        assertThat(parse(awaitResults(caseId, 1).get(0)).payload().decision()).isEqualTo(Decision.RESTRICT);
        assertThat(registry.counter("reviews.completed", "decision", "RESTRICT", "round", "1").count())
                .isEqualTo(completedBefore + 1);
        assertThat(registry.timer("kafka.consumer.processing", "group", Topics.GROUP_REVIEW_WORKERS).count())
                .isGreaterThan(processedBefore);
    }

    @Test
    void appealRoundUsesEvidenceFixture() {
        String caseId = UUID.randomUUID().toString();
        String contentId = UUID.randomUUID().toString();
        stubContent(contentId, false, "APPEAL_EVIDENCE_OK");
        send(caseId, contentId, 1, UUID.randomUUID());
        send(caseId, contentId, 2, UUID.randomUUID());

        var all = awaitResults(caseId, 2).stream().map(ReviewWorkflowTest::parse).toList();
        assertThat(all).filteredOn(e -> e.reviewRound() == 1).singleElement()
                .satisfies(e -> assertThat(e.payload().decision()).isEqualTo(Decision.RESTRICT));
        assertThat(all).filteredOn(e -> e.reviewRound() == 2).singleElement()
                .satisfies(e -> assertThat(e.payload().decision()).isEqualTo(Decision.ALLOW));
    }

    @Test
    void duplicateRequestProducesOneResultEvent() {
        String caseId = UUID.randomUUID().toString();
        String contentId = UUID.randomUUID().toString();
        stubContent(contentId, false, null);
        UUID requestId = UUID.randomUUID();
        send(caseId, contentId, 1, requestId);
        send(caseId, contentId, 1, requestId);
        // Same key, same partition: the round-2 result proves the duplicate before it was already consumed.
        send(caseId, contentId, 2, UUID.randomUUID());

        var results = awaitResults(caseId, 2).stream().map(ReviewWorkflowTest::parse).toList();
        assertThat(results).extracting(EventEnvelope::reviewRound).containsExactlyInAnyOrder(1, 2);
        assertThat(results).extracting(e -> e.eventId()).doesNotHaveDuplicates();
        assertThat(decisions.findAll().stream().filter(d -> d.getCaseId().equals(caseId))).hasSize(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events WHERE message_key = ?", Integer.class, caseId))
                .isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM processed_events WHERE consumer_group = ? AND event_id = ?",
                Integer.class, Topics.GROUP_REVIEW_WORKERS, requestId.toString())).isEqualTo(1);
    }

    @Test
    void decisionDoesNotChangeWhenContentChangesAfterwards() {
        String caseId = UUID.randomUUID().toString();
        String contentId = UUID.randomUUID().toString();
        stubContent(contentId, false, null);
        send(caseId, contentId, 1, UUID.randomUUID());
        awaitResults(caseId, 1);

        stubContent(contentId, true, null);
        send(caseId, contentId, 1, UUID.randomUUID());
        send(caseId, contentId, 2, UUID.randomUUID());
        var results = awaitResults(caseId, 2).stream().map(ReviewWorkflowTest::parse).toList();
        assertThat(results).filteredOn(e -> e.reviewRound() == 1).singleElement()
                .satisfies(e -> assertThat(e.payload().decision()).isEqualTo(Decision.RESTRICT));
        assertThat(decisions.findByCaseIdAndReviewRound(caseId, 1).orElseThrow().getDecision()).isEqualTo(Decision.RESTRICT);
    }

    @Test
    void temporaryMetadataOutageIsRetriedUntilItRecovers() {
        String caseId = UUID.randomUUID().toString();
        String contentId = UUID.randomUUID().toString();
        when(metadataClient.get(anyString()))
                .thenThrow(new ContentMetadataClient.ContentUnavailableException("down", null))
                .thenThrow(new ContentMetadataClient.ContentUnavailableException("down", null))
                .thenReturn(Optional.of(new ContentMetadata(contentId, "creator-1", true, null)));
        send(caseId, contentId, 1, UUID.randomUUID());

        assertThat(parse(awaitResults(caseId, 1).get(0)).payload().decision()).isEqualTo(Decision.ALLOW);
    }

    @Test
    void malformedEventGoesToDeadLetterTopicWithoutBlockingLaterEvents() {
        String caseId = UUID.randomUUID().toString();
        String contentId = UUID.randomUUID().toString();
        stubContent(contentId, true, null);
        kafka.send(Topics.REVIEW_REQUESTED, caseId, "{not valid json");
        send(caseId, contentId, 1, UUID.randomUUID());

        assertThat(awaitResults(caseId, 1)).hasSize(1);
        var dead = awaitDeadLetters(caseId);
        assertThat(dead.value()).isEqualTo("{not valid json");
        assertThat(new String(dead.headers().lastHeader(KafkaHeaders.DLT_ORIGINAL_TOPIC).value()))
                .isEqualTo(Topics.REVIEW_REQUESTED);
        assertThat(dead.headers().lastHeader(KafkaHeaders.DLT_EXCEPTION_FQCN)).isNotNull();
    }

    @Test
    void persistentFailureIsRetriedThenDeadLetteredAndCounted() {
        String caseId = UUID.randomUUID().toString();
        String contentId = UUID.randomUUID().toString();
        when(metadataClient.get(contentId)).thenThrow(new ContentMetadataClient.ContentUnavailableException("down", null));
        double retriesBefore = registry.counter("kafka.consumer.retries", "group", Topics.GROUP_REVIEW_WORKERS,
                "topic", Topics.REVIEW_REQUESTED).count();
        double deadBefore = registry.counter("kafka.consumer.dead_lettered", "group", Topics.GROUP_REVIEW_WORKERS,
                "topic", Topics.REVIEW_REQUESTED).count();
        send(caseId, contentId, 1, UUID.randomUUID());

        assertThat(awaitDeadLetters(caseId).key()).isEqualTo(caseId);
        assertThat(registry.counter("kafka.consumer.retries", "group", Topics.GROUP_REVIEW_WORKERS,
                "topic", Topics.REVIEW_REQUESTED).count()).isGreaterThan(retriesBefore);
        assertThat(registry.counter("kafka.consumer.dead_lettered", "group", Topics.GROUP_REVIEW_WORKERS,
                "topic", Topics.REVIEW_REQUESTED).count()).isEqualTo(deadBefore + 1);
        assertThat(decisions.findByCaseIdAndReviewRound(caseId, 1)).isEmpty();
    }

    @Test
    void replayRedeliversDeadLetteredRequestOnceDependencyRecovers() {
        String caseId = UUID.randomUUID().toString();
        String contentId = UUID.randomUUID().toString();
        when(metadataClient.get(contentId)).thenThrow(new ContentMetadataClient.ContentUnavailableException("down", null));
        send(caseId, contentId, 1, UUID.randomUUID());
        awaitDeadLetters(caseId);

        org.mockito.Mockito.doReturn(Optional.of(new ContentMetadata(contentId, "creator-1", true, null)))
                .when(metadataClient).get(contentId);
        int replayed = replayService.replay(Topics.deadLetter(Topics.REVIEW_REQUESTED, Topics.GROUP_REVIEW_WORKERS),
                Topics.GROUP_REVIEW_WORKERS, 1000);

        assertThat(replayed).isGreaterThanOrEqualTo(1);
        assertThat(parse(awaitResults(caseId, 1).get(0)).payload().decision()).isEqualTo(Decision.ALLOW);
    }

    @Test
    void workerRestartWithQueuedMessagesProcessesEachOnceWithoutLoss() {
        var workers = listenerRegistry.getListenerContainers().stream()
                .filter(c -> Topics.GROUP_REVIEW_WORKERS.equals(c.getGroupId())).toList();
        assertThat(workers).isNotEmpty();
        workers.forEach(c -> c.stop());
        List<String> caseIds = new ArrayList<>();
        try {
            for (int i = 0; i < 12; i++) {
                String caseId = UUID.randomUUID().toString();
                String contentId = UUID.randomUUID().toString();
                stubContent(contentId, i % 2 == 0, null);
                send(caseId, contentId, 1, UUID.randomUUID());
                caseIds.add(caseId);
            }
            // While the worker is down nothing is decided; the messages stay queued in Kafka.
            assertThat(decisions.findAll().stream().filter(d -> caseIds.contains(d.getCaseId()))).isEmpty();
        } finally {
            workers.forEach(c -> c.start());
        }

        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(200)).untilAsserted(() ->
                assertThat(decisions.findAll().stream().filter(d -> caseIds.contains(d.getCaseId()))).hasSize(12));
        for (String caseId : caseIds) {
            assertThat(awaitResults(caseId, 1)).hasSize(1);
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events WHERE message_key = ?", Integer.class, caseId))
                    .isEqualTo(1);
        }
    }

    private ConsumerRecord<String, String> awaitDeadLetters(String caseId) {
        List<ConsumerRecord<String, String>> found = new ArrayList<>();
        try (var consumer = new KafkaConsumer<>(Map.<String, Object>of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaContainer.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "dlt-test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"),
                new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of(Topics.deadLetter(Topics.REVIEW_REQUESTED, Topics.GROUP_REVIEW_WORKERS)));
            await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> {
                consumer.poll(Duration.ofMillis(200)).forEach(r -> {
                    if (caseId.equals(r.key())) {
                        found.add(r);
                    }
                });
                assertThat(found).isNotEmpty();
            });
        }
        return found.get(0);
    }
}
