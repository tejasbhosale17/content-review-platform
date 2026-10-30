package com.tejas.contentreview.contracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.tejas.contentreview.contracts.events.EventCodec;
import com.tejas.contentreview.contracts.events.EventEnvelope;
import com.tejas.contentreview.contracts.events.EventFormatException;
import com.tejas.contentreview.contracts.events.ReviewCompletedPayload;
import com.tejas.contentreview.contracts.events.ReviewCompletedPayload.Decision;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EventCodecTest {

    @Test
    void roundTripPreservesEveryEnvelopeField() {
        var original = new EventEnvelope<>(UUID.randomUUID(), "ReviewCompleted", 1, Instant.parse("2026-01-01T10:15:30.123456Z"),
                "case-1", "content-1", 2, "corr-9", new ReviewCompletedPayload(Decision.RESTRICT, "demo-v1", "why"));
        String json = EventCodec.toJson(original);
        assertThat(json).contains("\"schemaVersion\":1").contains("\"occurredAt\":\"2026-01-01T10:15:30.123456Z\"");
        assertThat(EventCodec.fromJson(json, ReviewCompletedPayload.class)).isEqualTo(original);
    }

    @Test
    void malformedOrInvalidEventsAreRejectedAsFormatErrors() {
        assertThatThrownBy(() -> EventCodec.fromJson("{nope", ReviewCompletedPayload.class))
                .isInstanceOf(EventFormatException.class);
        String noRound = "{\"eventId\":\"" + UUID.randomUUID() + "\",\"eventType\":\"x\",\"schemaVersion\":1,"
                + "\"occurredAt\":\"2026-01-01T00:00:00Z\",\"caseId\":\"c\",\"contentId\":\"k\",\"reviewRound\":0,"
                + "\"correlationId\":\"r\",\"payload\":{\"decision\":\"ALLOW\"}}";
        assertThatThrownBy(() -> EventCodec.fromJson(noRound, ReviewCompletedPayload.class))
                .isInstanceOf(EventFormatException.class);
        String noPayload = noRound.replace("\"reviewRound\":0", "\"reviewRound\":1").replace(",\"payload\":{\"decision\":\"ALLOW\"}", "");
        assertThatThrownBy(() -> EventCodec.fromJson(noPayload, ReviewCompletedPayload.class))
                .isInstanceOf(EventFormatException.class);
    }
}
