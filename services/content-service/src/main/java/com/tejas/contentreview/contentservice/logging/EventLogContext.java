package com.tejas.contentreview.contentservice.logging;

import com.tejas.contentreview.contracts.events.EventEnvelope;
import org.slf4j.MDC;

/** Puts the event identifiers into the logging context for the duration of one record's processing. */
public final class EventLogContext implements AutoCloseable {

    private EventLogContext() {
    }

    public static EventLogContext open(EventEnvelope<?> e) {
        MDC.put("caseId", e.caseId());
        MDC.put("eventId", e.eventId().toString());
        MDC.put("reviewRound", Integer.toString(e.reviewRound()));
        MDC.put("correlationId", e.correlationId());
        return new EventLogContext();
    }

    @Override
    public void close() {
        MDC.remove("caseId");
        MDC.remove("eventId");
        MDC.remove("reviewRound");
        MDC.remove("correlationId");
    }
}
