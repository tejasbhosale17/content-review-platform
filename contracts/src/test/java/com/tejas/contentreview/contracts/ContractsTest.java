package com.tejas.contentreview.contracts;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.tejas.contentreview.contracts.events.EventEnvelope;
import com.tejas.contentreview.contracts.events.ReviewRequestedPayload;
import com.tejas.contentreview.contracts.events.Topics;
import com.tejas.contentreview.contracts.grpc.GetContentRequest;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ContractsTest {

    @Test
    void protobufClassesAreGeneratedAndRoundTrip() throws Exception {
        GetContentRequest request = GetContentRequest.newBuilder().setContentId("c-1").build();
        assertThat(GetContentRequest.parseFrom(request.toByteArray()).getContentId()).isEqualTo("c-1");
    }

    @Test
    void envelopeRoundTripsThroughJson() throws Exception {
        ObjectMapper mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        var envelope = new EventEnvelope<>(UUID.randomUUID(), "ReviewRequested", 1, Instant.parse("2026-01-01T00:00:00Z"),
                "case-1", "content-1", 1, "corr-1", new ReviewRequestedPayload("spam", false));
        String json = mapper.writeValueAsString(envelope);
        assertThat(json).contains("\"occurredAt\":\"2026-01-01T00:00:00Z\"");
        assertThat(mapper.readTree(json).get("eventId").asText()).isEqualTo(envelope.eventId().toString());
    }

    @Test
    void envelopeRejectsInvalidRound() {
        assertThatThrownBy(() -> new EventEnvelope<>(UUID.randomUUID(), "x", 1, Instant.now(), "c", "k", 0, "r", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void topicNamesMatchSpecification() {
        assertThat(Topics.REVIEW_REQUESTED).isEqualTo("case.review-requested.v1");
        assertThat(Topics.REVIEW_COMPLETED).isEqualTo("review.completed.v1");
    }
}
