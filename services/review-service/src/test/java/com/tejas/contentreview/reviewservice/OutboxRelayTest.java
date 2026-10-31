package com.tejas.contentreview.reviewservice;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.tejas.contentreview.reviewservice.reliability.OutboxRelay;
import com.tejas.contentreview.reviewservice.reliability.OutboxSender;
import com.tejas.contentreview.reviewservice.reliability.OutboxStore;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Failure-injection tests for the outbox with the background relay disabled so each pass is driven by the test. */
@SpringBootTest(properties = "outbox.relay.enabled=false")
@Import(InfraTestConfig.class)
class OutboxRelayTest {

    @Autowired
    OutboxStore store;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    PlatformTransactionManager txManager;

    @BeforeEach
    void clean() {
        jdbc.update("DELETE FROM outbox_events");
    }

    private OutboxRelay relay(OutboxSender sender, int batch) {
        return new OutboxRelay(store, sender, new SimpleMeterRegistry(), batch, Duration.ofSeconds(30));
    }

    private String add(String key) {
        String eventId = UUID.randomUUID().toString();
        store.add(eventId, "test.topic", key, "{\"id\":\"" + eventId + "\"}");
        return eventId;
    }

    private int pending() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events WHERE published_at IS NULL", Integer.class);
    }

    @Test
    void rolledBackTransactionLeavesNoOutboxRow() {
        TransactionTemplate tx = new TransactionTemplate(txManager);
        try {
            tx.executeWithoutResult(s -> {
                add("k");
                throw new IllegalStateException("business failure after enqueue");
            });
        } catch (IllegalStateException expected) {
            // rolled back
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events", Integer.class)).isZero();
    }

    @Test
    void brokerOutageKeepsEventPendingThenPublishesTheSameEventOnRecovery() {
        String eventId = add("case-1");
        OutboxSender down = (topic, key, payload) -> {
            throw new java.io.IOException("broker unavailable");
        };
        assertThat(relay(down, 10).publishBatch()).isZero();
        assertThat(pending()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT last_error FROM outbox_events", String.class)).contains("broker unavailable");

        List<String> sent = Collections.synchronizedList(new ArrayList<>());
        OutboxRelay recovered = relay((topic, key, payload) -> sent.add(payload), 10);
        await().atMost(Duration.ofSeconds(10)).pollInterval(Duration.ofMillis(200)).until(() -> recovered.publishBatch() > 0);

        assertThat(sent).singleElement().asString().contains(eventId);
        assertThat(pending()).isZero();
    }

    @Test
    void failureStopsTheBatchSoLaterRowsAreNeverSentOutOfOrder() {
        String first = add("case-1");
        String second = add("case-1");
        add("case-1");
        List<String> sent = new ArrayList<>();
        OutboxSender flaky = (topic, key, payload) -> {
            if (sent.size() == 1) {
                throw new java.io.IOException("boom");
            }
            sent.add(payload);
        };

        assertThat(relay(flaky, 10).publishBatch()).isEqualTo(1);
        assertThat(sent).singleElement().asString().contains(first);
        assertThat(pending()).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT attempts FROM outbox_events WHERE event_id = ?", Integer.class, second)).isEqualTo(1);
        // Rows that were claimed but never attempted do not burn an attempt.
        assertThat(jdbc.queryForObject("SELECT MAX(attempts) FROM outbox_events WHERE published_at IS NULL AND event_id <> ?",
                Integer.class, second)).isZero();
    }

    @Test
    void leaseOfACrashedRelayExpiresAndAnotherRelayTakesOver() throws Exception {
        String eventId = add("case-1");
        // Simulates a relay that claimed the row and died before publishing.
        assertThat(store.claim("crashed-relay", 10, Duration.ofMillis(500))).hasSize(1);

        List<String> sent = new ArrayList<>();
        OutboxRelay other = relay((topic, key, payload) -> sent.add(payload), 10);
        assertThat(other.publishBatch()).isZero();
        Thread.sleep(700);
        assertThat(other.publishBatch()).isEqualTo(1);
        assertThat(sent).singleElement().asString().contains(eventId);
    }

    @Test
    void twoConcurrentRelaysNeverSendTheSameRowTwice() throws Exception {
        Set<String> ids = new HashSet<>();
        for (int i = 0; i < 40; i++) {
            ids.add(add("case-" + i));
        }
        List<String> sent = Collections.synchronizedList(new ArrayList<>());
        OutboxSender sender = (topic, key, payload) -> sent.add(payload);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<Future<?>> futures = new ArrayList<>();
            for (int r = 0; r < 2; r++) {
                OutboxRelay relay = relay(sender, 5);
                futures.add(pool.submit(() -> {
                    start.await();
                    while (relay.publishBatch() > 0 || pending() > 0) {
                        Thread.sleep(5);
                    }
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> f : futures) {
                f.get(60, java.util.concurrent.TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }
        assertThat(sent).hasSize(40).doesNotHaveDuplicates();
        assertThat(pending()).isZero();
    }
}
