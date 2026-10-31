package com.tejas.contentreview.reviewservice.reliability;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Per-consumer-group receipts. {@link #markProcessed} must run inside the same database transaction that applies
 * the event's effect, so the receipt and the effect commit or roll back together.
 */
@Component
public class ProcessedEvents {

    private final JdbcTemplate jdbc;

    public ProcessedEvents(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Returns true if this is the first time the group sees the event, false for a duplicate. */
    public boolean markProcessed(String group, String eventId) {
        return jdbc.update("INSERT IGNORE INTO processed_events (consumer_group, event_id, processed_at) VALUES (?, ?, ?)",
                group, eventId, LocalDateTime.ofInstant(Instant.now(), ZoneOffset.UTC)) == 1;
    }
}
