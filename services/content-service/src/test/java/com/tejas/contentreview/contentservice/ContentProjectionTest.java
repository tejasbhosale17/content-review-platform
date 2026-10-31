package com.tejas.contentreview.contentservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.tejas.contentreview.contentservice.content.Content;
import com.tejas.contentreview.contentservice.content.Content.Visibility;
import com.tejas.contentreview.contentservice.content.ContentRepository;
import com.tejas.contentreview.contentservice.projection.ContentProjectionService;
import com.tejas.contentreview.contentservice.projection.ContentProjectionService.Outcome;
import com.tejas.contentreview.contracts.events.EventCodec;
import com.tejas.contentreview.contracts.events.EventEnvelope;
import com.tejas.contentreview.contracts.events.ReviewCompletedPayload;
import com.tejas.contentreview.contracts.events.ReviewCompletedPayload.Decision;
import com.tejas.contentreview.contracts.events.Topics;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaTemplate;
import org.testcontainers.kafka.KafkaContainer;

@SpringBootTest
@Import(InfraTestConfig.class)
class ContentProjectionTest {

    @Autowired
    ContentRepository contents;
    @Autowired
    ContentProjectionService projection;
    @Autowired
    KafkaTemplate<String, String> kafka;
    @Autowired
    KafkaContainer kafkaContainer;
    @Autowired
    org.springframework.jdbc.core.JdbcTemplate jdbc;

    private String newContent() {
        String id = UUID.randomUUID().toString();
        contents.saveAndFlush(new Content(id, "creator-1", "t", false, null, Instant.now()));
        return id;
    }

    private static EventEnvelope<ReviewCompletedPayload> result(String contentId, int round, Decision d) {
        return new EventEnvelope<>(UUID.randomUUID(), "ReviewCompleted", 1, Instant.now(), "case-" + contentId, contentId,
                round, "corr", new ReviewCompletedPayload(d, "demo-v1", "test"));
    }

    private Content reload(String id) {
        return contents.findById(id).orElseThrow();
    }

    @Test
    void restrictAndAllowOverKafkaUpdateVisibilityAndAppliedRound() {
        String a = newContent();
        String b = newContent();
        kafka.send(Topics.REVIEW_COMPLETED, "case-" + a, EventCodec.toJson(result(a, 1, Decision.RESTRICT)));
        kafka.send(Topics.REVIEW_COMPLETED, "case-" + b, EventCodec.toJson(result(b, 1, Decision.ALLOW)));

        await().atMost(Duration.ofSeconds(45)).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> {
            assertThat(reload(a).getVisibility()).isEqualTo(Visibility.RESTRICTED);
            assertThat(reload(a).getAppliedReviewRound()).isEqualTo(1);
            assertThat(reload(b).getVisibility()).isEqualTo(Visibility.VISIBLE);
            assertThat(reload(b).getAppliedReviewRound()).isEqualTo(1);
        });
    }

    @Test
    void lateRoundOneRestrictionDoesNotOverwriteRoundTwoRestoration() throws Exception {
        String id = newContent();
        kafka.send(Topics.REVIEW_COMPLETED, "case-" + id, EventCodec.toJson(result(id, 1, Decision.RESTRICT)));
        await().atMost(Duration.ofSeconds(45)).untilAsserted(() -> assertThat(reload(id).getAppliedReviewRound()).isEqualTo(1));
        kafka.send(Topics.REVIEW_COMPLETED, "case-" + id, EventCodec.toJson(result(id, 2, Decision.ALLOW)));
        await().atMost(Duration.ofSeconds(45)).untilAsserted(() -> assertThat(reload(id).getVisibility()).isEqualTo(Visibility.VISIBLE));

        kafka.send(Topics.REVIEW_COMPLETED, "case-" + id, EventCodec.toJson(result(id, 1, Decision.RESTRICT)));
        awaitGroupCaughtUp();

        assertThat(reload(id).getVisibility()).isEqualTo(Visibility.VISIBLE);
        assertThat(reload(id).getAppliedReviewRound()).isEqualTo(2);
    }

    @Test
    void outcomesAreClassifiedPerRound() {
        String id = newContent();
        assertThat(projection.apply(result(id, 1, Decision.RESTRICT))).isEqualTo(Outcome.APPLIED);
        assertThat(projection.apply(result(id, 1, Decision.RESTRICT))).isEqualTo(Outcome.DUPLICATE);
        assertThat(projection.apply(result(id, 1, Decision.ALLOW))).isEqualTo(Outcome.CONFLICT);
        assertThat(reload(id).getVisibility()).isEqualTo(Visibility.RESTRICTED);
        assertThat(projection.apply(result(id, 2, Decision.ALLOW))).isEqualTo(Outcome.APPLIED);
        assertThat(projection.apply(result(id, 1, Decision.RESTRICT))).isEqualTo(Outcome.STALE);
        assertThat(reload(id).getVisibility()).isEqualTo(Visibility.VISIBLE);
        assertThat(projection.apply(result(UUID.randomUUID().toString(), 1, Decision.ALLOW))).isEqualTo(Outcome.UNKNOWN_CONTENT);
    }

    @Test
    void concurrentRoundsAlwaysEndAtTheHigherRound() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 20; i++) {
                String id = newContent();
                CountDownLatch start = new CountDownLatch(1);
                List<Future<Outcome>> futures = new ArrayList<>();
                futures.add(pool.submit(() -> {
                    start.await();
                    return projection.apply(result(id, 1, Decision.RESTRICT));
                }));
                futures.add(pool.submit(() -> {
                    start.await();
                    return projection.apply(result(id, 2, Decision.ALLOW));
                }));
                start.countDown();
                for (Future<Outcome> f : futures) {
                    f.get(30, TimeUnit.SECONDS);
                }
                assertThat(reload(id).getAppliedReviewRound()).isEqualTo(2);
                assertThat(reload(id).getVisibility()).isEqualTo(Visibility.VISIBLE);
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void sameEventIdIsAppliedOnlyOnceEvenIfStateWouldAllowIt() {
        String id = newContent();
        var event = result(id, 1, Decision.RESTRICT);
        assertThat(projection.apply(event)).isEqualTo(Outcome.APPLIED);

        jdbc.update("UPDATE contents SET applied_review_round = 0, visibility = 'VISIBLE' WHERE content_id = ?", id);
        assertThat(projection.apply(event)).isEqualTo(Outcome.DUPLICATE);
        assertThat(reload(id).getVisibility()).isEqualTo(Visibility.VISIBLE);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM processed_events WHERE consumer_group = ? AND event_id = ?",
                Integer.class, Topics.GROUP_CONTENT_PROJECTION, event.eventId().toString())).isEqualTo(1);
    }

    @Test
    void malformedResultIsDeadLetteredAndLaterEventsStillApply() {
        String poisonKey = UUID.randomUUID().toString();
        String id = newContent();
        kafka.send(Topics.REVIEW_COMPLETED, poisonKey, "{broken");
        kafka.send(Topics.REVIEW_COMPLETED, "case-" + id, EventCodec.toJson(result(id, 1, Decision.RESTRICT)));

        await().atMost(Duration.ofSeconds(45)).untilAsserted(() ->
                assertThat(reload(id).getVisibility()).isEqualTo(Visibility.RESTRICTED));
        try (var consumer = new KafkaConsumer<>(Map.<String, Object>of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafkaContainer.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "dlt-test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest"), new StringDeserializer(), new StringDeserializer())) {
            consumer.subscribe(List.of(Topics.deadLetter(Topics.REVIEW_COMPLETED, Topics.GROUP_CONTENT_PROJECTION)));
            List<String> keys = new ArrayList<>();
            await().atMost(Duration.ofSeconds(45)).untilAsserted(() -> {
                consumer.poll(Duration.ofMillis(200)).forEach(r -> keys.add(r.key()));
                assertThat(keys).contains(poisonKey);
            });
        }
    }

    /** Waits until content-projection has committed every record currently in the topic. */
    private void awaitGroupCaughtUp() throws Exception {
        String bootstrap = kafkaContainer.getBootstrapServers();
        try (AdminClient admin = AdminClient.create(Map.<String, Object>of("bootstrap.servers", bootstrap));
             var consumer = new KafkaConsumer<>(Map.<String, Object>of(
                     ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap,
                     ConsumerConfig.GROUP_ID_CONFIG, "probe-" + UUID.randomUUID()),
                     new StringDeserializer(), new StringDeserializer())) {
            var partitions = consumer.partitionsFor(Topics.REVIEW_COMPLETED).stream()
                    .map(p -> new TopicPartition(p.topic(), p.partition())).toList();
            var end = consumer.endOffsets(partitions);
            await().atMost(Duration.ofSeconds(45)).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> {
                var committed = admin.listConsumerGroupOffsets(Topics.GROUP_CONTENT_PROJECTION)
                        .partitionsToOffsetAndMetadata().get();
                end.forEach((tp, endOffset) -> {
                    long done = committed.containsKey(tp) ? committed.get(tp).offset() : 0;
                    assertThat(done).as("committed offset for %s", tp).isGreaterThanOrEqualTo(endOffset);
                });
            });
        }
    }
}
