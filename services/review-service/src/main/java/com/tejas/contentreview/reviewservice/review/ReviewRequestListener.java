package com.tejas.contentreview.reviewservice.review;

import com.tejas.contentreview.contracts.events.EventCodec;
import com.tejas.contentreview.contracts.events.EventEnvelope;
import com.tejas.contentreview.contracts.events.ReviewCompletedPayload;
import com.tejas.contentreview.contracts.events.ReviewRequestedPayload;
import com.tejas.contentreview.contracts.events.Topics;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

/**
 * Consumes review requests. The offset is committed only after the decision is stored AND the result
 * is acknowledged by the broker; any failure leaves the record to be redelivered. Redelivery re-sends
 * the stored decision under the same event ID. The transactional outbox replaces the direct publish later.
 */
@Component
public class ReviewRequestListener {

    private static final Logger log = LoggerFactory.getLogger(ReviewRequestListener.class);
    private static final long SEND_TIMEOUT_SECONDS = 10;

    private final ReviewService reviewService;
    private final KafkaTemplate<String, String> kafka;

    public ReviewRequestListener(ReviewService reviewService, KafkaTemplate<String, String> kafka) {
        this.reviewService = reviewService;
        this.kafka = kafka;
    }

    @KafkaListener(topics = Topics.REVIEW_REQUESTED, groupId = Topics.GROUP_REVIEW_WORKERS)
    public void onReviewRequested(ConsumerRecord<String, String> record) {
        EventEnvelope<ReviewRequestedPayload> request = EventCodec.fromJson(record.value(), ReviewRequestedPayload.class);
        ReviewDecision decision = reviewService.decide(request);
        publishResult(decision);
        log.info("Review completed caseId={} reviewRound={} decision={} eventId={} correlationId={}",
                decision.getCaseId(), decision.getReviewRound(), decision.getDecision(), decision.getResultEventId(),
                decision.getCorrelationId());
    }

    private void publishResult(ReviewDecision d) {
        var result = new EventEnvelope<>(java.util.UUID.fromString(d.getResultEventId()), "ReviewCompleted", 1,
                d.getCreatedAt(), d.getCaseId(), d.getContentId(), d.getReviewRound(), d.getCorrelationId(),
                new ReviewCompletedPayload(d.getDecision(), d.getPolicyVersion(), d.getRationale()));
        try {
            kafka.send(Topics.REVIEW_COMPLETED, d.getCaseId(), EventCodec.toJson(result)).get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while publishing result for case " + d.getCaseId(), e);
        } catch (ExecutionException | TimeoutException e) {
            throw new IllegalStateException("Publishing result failed for case " + d.getCaseId(), e);
        }
    }
}
