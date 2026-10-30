package com.tejas.contentreview.contracts.events;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/** Single JSON representation of events so every service reads and writes the same format. */
public final class EventCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    private EventCodec() {
    }

    public static String toJson(EventEnvelope<?> envelope) {
        try {
            return MAPPER.writeValueAsString(envelope);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Cannot serialize event " + envelope.eventId(), e);
        }
    }

    public static <T> EventEnvelope<T> fromJson(String json, Class<T> payloadType) {
        try {
            JavaType type = MAPPER.getTypeFactory().constructParametricType(EventEnvelope.class, payloadType);
            EventEnvelope<T> envelope = MAPPER.readValue(json, type);
            if (envelope.payload() == null) {
                throw new EventFormatException("Event " + envelope.eventId() + " has no payload", null);
            }
            return envelope;
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new EventFormatException("Malformed event: " + e.getMessage(), e);
        }
    }
}
