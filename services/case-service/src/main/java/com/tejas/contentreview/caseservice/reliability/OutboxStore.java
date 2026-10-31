package com.tejas.contentreview.caseservice.reliability;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Transactional outbox storage. {@link #add} joins the caller's database transaction, so an event exists if and only
 * if the business change that produced it committed. Rows are claimed with a time-limited lease; a relay that dies
 * mid-batch simply lets the lease expire and another claim re-sends (at-least-once).
 */
@Component
public class OutboxStore {

    public record Row(long id, String eventId, String topic, String key, String payload, int attempts) {
    }

    private final JdbcTemplate jdbc;

    public OutboxStore(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    private static LocalDateTime utc(Instant instant) {
        return LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    public void add(String eventId, String topic, String key, String payload) {
        jdbc.update("INSERT INTO outbox_events (event_id, topic, message_key, payload, created_at) VALUES (?, ?, ?, ?, ?)",
                eventId, topic, key, payload, utc(Instant.now()));
    }

    /** Claims up to {@code limit} unpublished rows in id order; returns them with a lease owned by {@code owner}. */
    public List<Row> claim(String owner, int limit, Duration lease) {
        Instant now = Instant.now();
        int claimed = jdbc.update("UPDATE outbox_events SET lease_owner = ?, lease_until = ?, attempts = attempts + 1 "
                        + "WHERE published_at IS NULL AND (lease_until IS NULL OR lease_until < ?) ORDER BY id LIMIT ?",
                owner, utc(now.plus(lease)), utc(now), limit);
        if (claimed == 0) {
            return List.of();
        }
        return jdbc.query("SELECT id, event_id, topic, message_key, payload, attempts FROM outbox_events "
                        + "WHERE lease_owner = ? AND published_at IS NULL ORDER BY id",
                (rs, i) -> new Row(rs.getLong("id"), rs.getString("event_id"), rs.getString("topic"),
                        rs.getString("message_key"), rs.getString("payload"), rs.getInt("attempts")), owner);
    }

    public boolean markPublished(long id, String owner) {
        return jdbc.update("UPDATE outbox_events SET published_at = ?, lease_owner = NULL, lease_until = NULL, last_error = NULL "
                + "WHERE id = ? AND lease_owner = ?", utc(Instant.now()), id, owner) == 1;
    }

    /** Releases a failed row with exponential backoff (1s, 2s, 4s ... capped at 30s) based on its attempt count. */
    public void fail(long id, String owner, String error) {
        String message = error == null ? "unknown" : error.substring(0, Math.min(error.length(), 500));
        jdbc.update("UPDATE outbox_events SET lease_owner = NULL, last_error = ?, "
                        + "lease_until = DATE_ADD(?, INTERVAL LEAST(30000000, 500000 * POW(2, LEAST(attempts, 10))) MICROSECOND) "
                        + "WHERE id = ? AND lease_owner = ?",
                message, utc(Instant.now()), id, owner);
    }

    /** Gives back rows that were claimed but never attempted, without counting an attempt. */
    public void releaseUnsent(String owner) {
        jdbc.update("UPDATE outbox_events SET lease_owner = NULL, lease_until = NULL, attempts = GREATEST(attempts - 1, 0) "
                + "WHERE lease_owner = ? AND published_at IS NULL", owner);
    }

    public long pendingCount() {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM outbox_events WHERE published_at IS NULL", Long.class);
        return count == null ? 0 : count;
    }

    public static String newOwner() {
        return UUID.randomUUID().toString();
    }
}
