package com.tejas.contentreview.contentservice.projection;

import com.tejas.contentreview.contentservice.content.Content;
import com.tejas.contentreview.contentservice.content.ContentRepository;
import com.tejas.contentreview.contracts.events.EventEnvelope;
import com.tejas.contentreview.contracts.events.ReviewCompletedPayload;
import com.tejas.contentreview.contracts.events.ReviewCompletedPayload.Decision;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ContentProjectionService {

    public enum Outcome {
        APPLIED, DUPLICATE, STALE, CONFLICT, UNKNOWN_CONTENT
    }

    private static final Logger log = LoggerFactory.getLogger(ContentProjectionService.class);

    private final ContentRepository contents;

    public ContentProjectionService(ContentRepository contents) {
        this.contents = contents;
    }

    /**
     * Applies a review result when its round is newer than the stored one.
     * Equal rounds are harmless repeats (or a reported conflict if the decision differs); older rounds are ignored,
     * so a late round-1 restriction can never overwrite a round-2 restoration.
     */
    @Transactional
    public Outcome apply(EventEnvelope<ReviewCompletedPayload> event) {
        Content.Visibility target = event.payload().decision() == Decision.ALLOW
                ? Content.Visibility.VISIBLE : Content.Visibility.RESTRICTED;
        int updated = contents.applyReviewRound(event.contentId(), event.reviewRound(), target,
                Instant.now().truncatedTo(ChronoUnit.MICROS));
        if (updated == 1) {
            log.info("Content visibility updated contentId={} visibility={} reviewRound={} caseId={} eventId={} correlationId={}",
                    event.contentId(), target, event.reviewRound(), event.caseId(), event.eventId(), event.correlationId());
            return Outcome.APPLIED;
        }
        Content current = contents.findById(event.contentId()).orElse(null);
        if (current == null) {
            log.warn("Review result for unknown content ignored contentId={} caseId={} eventId={}", event.contentId(),
                    event.caseId(), event.eventId());
            return Outcome.UNKNOWN_CONTENT;
        }
        if (current.getAppliedReviewRound() == event.reviewRound()) {
            if (current.getVisibility() != target) {
                log.error("CONFLICT: same-round decisions disagree contentId={} reviewRound={} stored={} incoming={} caseId={} eventId={}",
                        event.contentId(), event.reviewRound(), current.getVisibility(), target, event.caseId(), event.eventId());
                return Outcome.CONFLICT;
            }
            log.info("Duplicate review result ignored contentId={} reviewRound={} eventId={}", event.contentId(),
                    event.reviewRound(), event.eventId());
            return Outcome.DUPLICATE;
        }
        log.info("Stale review result ignored contentId={} eventRound={} appliedRound={} eventId={}", event.contentId(),
                event.reviewRound(), current.getAppliedReviewRound(), event.eventId());
        return Outcome.STALE;
    }
}
