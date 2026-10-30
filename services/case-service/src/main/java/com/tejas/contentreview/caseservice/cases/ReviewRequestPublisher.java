package com.tejas.contentreview.caseservice.cases;

import com.tejas.contentreview.contracts.events.EventCodec;
import com.tejas.contentreview.contracts.events.EventEnvelope;
import com.tejas.contentreview.contracts.events.ReviewRequestedPayload;
import com.tejas.contentreview.contracts.events.Topics;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Interim publisher: sends after the case transaction commits. A crash between commit and send loses
 * the request; the transactional outbox in the reliability stage closes that gap.
 */
@Component
public class ReviewRequestPublisher {

    private static final Logger log = LoggerFactory.getLogger(ReviewRequestPublisher.class);

    public record ReviewRequestedEvent(String caseId, String contentId, int reviewRound, String reason, boolean appeal) {
    }

    private final KafkaTemplate<String, String> kafka;

    public ReviewRequestPublisher(KafkaTemplate<String, String> kafka) {
        this.kafka = kafka;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void publish(ReviewRequestedEvent e) {
        String correlationId = UUID.randomUUID().toString();
        var envelope = new EventEnvelope<>(UUID.randomUUID(), "ReviewRequested", 1, Instant.now(), e.caseId(),
                e.contentId(), e.reviewRound(), correlationId, new ReviewRequestedPayload(e.reason(), e.appeal()));
        kafka.send(Topics.REVIEW_REQUESTED, e.caseId(), EventCodec.toJson(envelope)).whenComplete((r, ex) -> {
            if (ex != null) {
                log.error("Failed to publish review request caseId={} eventId={} correlationId={}", e.caseId(),
                        envelope.eventId(), correlationId, ex);
            } else {
                log.info("Published review request caseId={} reviewRound={} eventId={} correlationId={}", e.caseId(),
                        e.reviewRound(), envelope.eventId(), correlationId);
            }
        });
    }
}
