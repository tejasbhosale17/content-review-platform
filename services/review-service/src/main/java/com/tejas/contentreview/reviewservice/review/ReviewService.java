package com.tejas.contentreview.reviewservice.review;

import com.tejas.contentreview.contracts.events.EventEnvelope;
import com.tejas.contentreview.contracts.events.ReviewRequestedPayload;
import com.tejas.contentreview.reviewservice.content.ContentMetadata;
import com.tejas.contentreview.reviewservice.content.ContentMetadataClient;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
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
    private final TransactionTemplate tx;

    public ReviewService(ReviewDecisionRepository decisions, ContentMetadataClient metadataClient, ReviewPolicy policy,
                         PlatformTransactionManager txManager) {
        this.decisions = decisions;
        this.metadataClient = metadataClient;
        this.policy = policy;
        this.tx = new TransactionTemplate(txManager);
    }

    /**
     * Returns the single decision for (caseId, round), creating it on first use. Replays and concurrent
     * workers get the already-stored decision, so the outcome never changes once persisted.
     */
    public ReviewDecision decide(EventEnvelope<ReviewRequestedPayload> request) {
        var existing = decisions.findByCaseIdAndReviewRound(request.caseId(), request.reviewRound());
        if (existing.isPresent()) {
            log.info("Decision already exists caseId={} reviewRound={} eventId={}", request.caseId(), request.reviewRound(), request.eventId());
            return existing.get();
        }

        // Remote call stays outside any database transaction.
        ContentMetadata content = metadataClient.get(request.contentId())
                .orElseThrow(() -> new IllegalStateException("Content " + request.contentId() + " not found for case " + request.caseId()));
        ReviewPolicy.Result result = policy.evaluate(request.reviewRound(), content);

        try {
            return tx.execute(status -> decisions.saveAndFlush(new ReviewDecision(request.caseId(), request.reviewRound(),
                    request.contentId(), result.decision(), policy.version(), result.rationale(), request.correlationId(),
                    Instant.now().truncatedTo(ChronoUnit.MICROS))));
        } catch (DataIntegrityViolationException e) {
            return decisions.findByCaseIdAndReviewRound(request.caseId(), request.reviewRound()).orElseThrow(() -> e);
        }
    }
}
