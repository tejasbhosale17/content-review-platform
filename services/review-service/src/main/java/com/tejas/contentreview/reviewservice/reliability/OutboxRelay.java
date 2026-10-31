package com.tejas.contentreview.reviewservice.reliability;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Publishes pending outbox rows in id order. A failed send stops the batch (preserving order for later rows) and puts
 * the failed row on backoff. Per-key ordering is guaranteed for a single relay instance; with several instances the
 * consumers' round-based stale-event protection is what keeps results correct.
 */
@Component
public class OutboxRelay {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);

    private final OutboxStore store;
    private final OutboxSender sender;
    private final int batchSize;
    private final Duration lease;
    private final Counter published;
    private final Counter failures;

    public OutboxRelay(OutboxStore store, OutboxSender sender, MeterRegistry registry,
                       @Value("${outbox.relay.batch-size:50}") int batchSize,
                       @Value("${outbox.relay.lease:30s}") Duration lease) {
        this.store = store;
        this.sender = sender;
        this.batchSize = batchSize;
        this.lease = lease;
        this.published = registry.counter("outbox.published");
        this.failures = registry.counter("outbox.publish.failures");
        Gauge.builder("outbox.pending", store, OutboxStore::pendingCount).register(registry);
    }

    /** Runs one claim-and-publish pass and returns how many rows were published. */
    public int publishBatch() {
        String owner = OutboxStore.newOwner();
        List<OutboxStore.Row> rows = store.claim(owner, batchSize, lease);
        // Stop before the lease can expire so another relay never sends the same row concurrently.
        Instant deadline = Instant.now().plus(lease.multipliedBy(4).dividedBy(5));
        int sent = 0;
        try {
            for (OutboxStore.Row row : rows) {
                if (Instant.now().isAfter(deadline)) {
                    break;
                }
                try {
                    sender.send(row.topic(), row.key(), row.payload());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    store.fail(row.id(), owner, "interrupted");
                    break;
                } catch (Exception e) {
                    failures.increment();
                    store.fail(row.id(), owner, e.toString());
                    log.warn("Outbox publish failed eventId={} topic={} attempt={}: {}", row.eventId(), row.topic(),
                            row.attempts(), e.toString());
                    break;
                }
                if (store.markPublished(row.id(), owner)) {
                    published.increment();
                    sent++;
                    log.info("Outbox published eventId={} topic={} key={}", row.eventId(), row.topic(), row.key());
                } else {
                    log.warn("Outbox lease lost after send eventId={}; it may be re-sent and consumers must dedupe", row.eventId());
                }
            }
        } finally {
            store.releaseUnsent(owner);
        }
        return sent;
    }
}
