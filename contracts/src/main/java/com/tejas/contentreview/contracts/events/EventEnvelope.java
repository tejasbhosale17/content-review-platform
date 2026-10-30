package com.tejas.contentreview.contracts.events;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * Common envelope for every Kafka event. The eventId is generated once, persisted, and reused on retries.
 */
public record EventEnvelope<T>(
        UUID eventId,
        String eventType,
        int schemaVersion,
        Instant occurredAt,
        String caseId,
        String contentId,
        int reviewRound,
        String correlationId,
        T payload) {

    public EventEnvelope {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(caseId, "caseId");
        Objects.requireNonNull(contentId, "contentId");
        Objects.requireNonNull(correlationId, "correlationId");
        if (reviewRound < 1) {
            throw new IllegalArgumentException("reviewRound must be >= 1");
        }
    }
}
