package com.tejas.contentreview.reviewservice.review;

import com.tejas.contentreview.contracts.events.EventCodec;
import com.tejas.contentreview.contracts.events.EventEnvelope;
import com.tejas.contentreview.contracts.events.ReviewCompletedPayload;
import com.tejas.contentreview.contracts.events.ReviewRequestedPayload;
import com.tejas.contentreview.contracts.events.Topics;
import com.tejas.contentreview.reviewservice.content.ContentMetadata;
import com.tejas.contentreview.reviewservice.content.ContentMetadataClient;
import com.tejas.contentreview.reviewservice.reliability.OutboxStore;
import com.tejas.contentreview.reviewservice.reliability.ProcessedEvents;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class ReviewService {

    private static final Logger log = LoggerFactory.getLogger(ReviewService.class);

    private final ReviewDecisionRepository decisions;
    private final ContentMetadataClient metadataClient;
    private final ReviewPolicy policy;
    private final ProcessedEvents processed;
    private final OutboxStore outbox;
    private final TransactionTemplate tx;
    private final MeterRegistry meters;

    public ReviewService(ReviewDecisionRepository decisions, ContentMetadataClient metadataClient, ReviewPolicy policy,
                         ProcessedEvents processed, OutboxStore outbox, PlatformTransactionManager txManager,
                         MeterRegistry meters) {
        this.meters = meters;
        this.decisions = decisions;
        this.metadataClient = metadataClient;
        this.policy = policy;
        this.processed = processed;
        this.outbox = outbox;
        this.tx = new TransactionTemplate(txManager);
    }

    /**
     * Stores the single decision for (caseId, round) together with its receipt and the ReviewCompleted outbox row in
     * one transaction. Duplicates (same event ID, or another request for an already-decided round) change nothing:
     * the original decision and its outbox event are already committed, so the outcome never changes once persisted.
     */
    public void handle(EventEnvelope<ReviewRequestedPayload> request) {
        String eventId = request.eventId().toString();
        if (decisions.findByCaseIdAndReviewRound(request.caseId(), request.reviewRound()).isPresent()) {
            tx.executeWithoutResult(s -> processed.markProcessed(Topics.GROUP_REVIEW_WORKERS, eventId));
            log.info("Decision already exists caseId={} reviewRound={} eventId={}", request.caseId(),
                    request.reviewRound(), eventId);
            return;
        }

        // Remote call stays outside any database transaction.
        ContentMetadata content = metadataClient.get(request.contentId())
                .orElseThrow(() -> new IllegalStateException("Content " + request.contentId() + " not found for case " + request.caseId()));
        ReviewPolicy.Result result = policy.evaluate(request.reviewRound(), content);

        try {
            ReviewDecision stored = tx.execute(status -> {
                if (!processed.markProcessed(Topics.GROUP_REVIEW_WORKERS, eventId)) {
                    return null;
                }
                ReviewDecision decision = decisions.saveAndFlush(new ReviewDecision(request.caseId(), request.reviewRound(),
                        request.contentId(), result.decision(), policy.version(), result.rationale(),
                        request.correlationId(), Instant.now().truncatedTo(ChronoUnit.MICROS)));
                var completed = new EventEnvelope<>(UUID.fromString(decision.getResultEventId()), "ReviewCompleted", 1,
                        decision.getCreatedAt(), decision.getCaseId(), decision.getContentId(), decision.getReviewRound(),
                        decision.getCorrelationId(),
                        new ReviewCompletedPayload(decision.getDecision(), decision.getPolicyVersion(), decision.getRationale()));
                outbox.add(decision.getResultEventId(), Topics.REVIEW_COMPLETED, decision.getCaseId(), EventCodec.toJson(completed));
                return decision;
            });
            if (stored == null) {
                log.info("Duplicate review request ignored caseId={} eventId={}", request.caseId(), eventId);
            } else {
                meters.counter("reviews.completed", "decision", stored.getDecision().name(),
                        "round", Integer.toString(stored.getReviewRound())).increment();
                log.info("Review completed caseId={} reviewRound={} decision={} eventId={} correlationId={}",
                        stored.getCaseId(), stored.getReviewRound(), stored.getDecision(), stored.getResultEventId(),
                        stored.getCorrelationId());
            }
        } catch (DataIntegrityViolationException e) {
            // A concurrent worker stored the same (case, round) first; its transaction owns the outbox event.
            log.info("Concurrent decision for caseId={} reviewRound={} already stored", request.caseId(), request.reviewRound());
        }
    }
}
