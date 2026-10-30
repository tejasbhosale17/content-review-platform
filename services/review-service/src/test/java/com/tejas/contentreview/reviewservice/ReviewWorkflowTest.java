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
import com.tejas.contentreview.reviewservice.review.ReviewDecisionRepository;
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
        send(caseId, contentId, 1, UUID.randomUUID());

        assertThat(parse(awaitResults(caseId, 1).get(0)).payload().decision()).isEqualTo(Decision.RESTRICT);
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
    void duplicateRequestKeepsOneDecisionAndRepublishesSameEventId() {
        String caseId = UUID.randomUUID().toString();
        String contentId = UUID.randomUUID().toString();
        stubContent(contentId, false, null);
        UUID requestId = UUID.randomUUID();
        send(caseId, contentId, 1, requestId);
        send(caseId, contentId, 1, requestId);

        var results = awaitResults(caseId, 2).stream().map(ReviewWorkflowTest::parse).toList();
        assertThat(results.get(0).eventId()).isEqualTo(results.get(1).eventId());
        assertThat(results).extracting(e -> e.payload().decision()).containsOnly(Decision.RESTRICT);
        assertThat(decisions.findAll().stream().filter(d -> d.getCaseId().equals(caseId))).hasSize(1);
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
        var results = awaitResults(caseId, 2).stream().map(ReviewWorkflowTest::parse).toList();
        assertThat(results).extracting(e -> e.payload().decision()).containsOnly(Decision.RESTRICT);
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
    void malformedEventIsSkippedWithoutBlockingLaterEvents() {
        String caseId = UUID.randomUUID().toString();
        String contentId = UUID.randomUUID().toString();
        stubContent(contentId, true, null);
        kafka.send(Topics.REVIEW_REQUESTED, caseId, "{not valid json");
        send(caseId, contentId, 1, UUID.randomUUID());

        assertThat(awaitResults(caseId, 1)).hasSize(1);
    }
}
