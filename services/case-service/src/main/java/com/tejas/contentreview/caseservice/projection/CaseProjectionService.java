package com.tejas.contentreview.caseservice.projection;

import com.tejas.contentreview.caseservice.cases.CaseEntity;
import com.tejas.contentreview.caseservice.cases.CaseRepository;
import com.tejas.contentreview.caseservice.reliability.ProcessedEvents;
import com.tejas.contentreview.contracts.events.Topics;
import com.tejas.contentreview.contracts.events.EventEnvelope;
import com.tejas.contentreview.contracts.events.ReviewCompletedPayload;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class CaseProjectionService {

    private static final Logger log = LoggerFactory.getLogger(CaseProjectionService.class);

    private final CaseRepository cases;
    private final ProcessedEvents processed;

    public CaseProjectionService(CaseRepository cases, ProcessedEvents processed) {
        this.cases = cases;
        this.processed = processed;
    }

    /**
     * Returns true when the case changed. The receipt is written in this transaction, so a duplicate event ID is
     * skipped even if the state would otherwise allow it again; a failure rolls back both and the record is retried.
     */
    @Transactional
    public boolean apply(EventEnvelope<ReviewCompletedPayload> event) {
        if (!processed.markProcessed(Topics.GROUP_CASE_PROJECTION, event.eventId().toString())) {
            log.info("Duplicate review result ignored caseId={} eventId={}", event.caseId(), event.eventId());
            return false;
        }
        Optional<CaseEntity> found = cases.findById(event.caseId());
        if (found.isEmpty()) {
            log.warn("Review result for unknown case ignored caseId={} eventId={} correlationId={}", event.caseId(),
                    event.eventId(), event.correlationId());
            return false;
        }
        CaseEntity entity = found.get();
        boolean changed = entity.applyReviewResult(event.reviewRound(), event.payload().decision(),
                Instant.now().truncatedTo(ChronoUnit.MICROS));
        if (changed) {
            log.info("Case updated caseId={} status={} reviewRound={} eventId={} correlationId={}", event.caseId(),
                    entity.getStatus(), event.reviewRound(), event.eventId(), event.correlationId());
        } else {
            log.info("Review result not applicable (duplicate or stale) caseId={} status={} eventRound={} eventId={}",
                    event.caseId(), entity.getStatus(), event.reviewRound(), event.eventId());
        }
        return changed;
    }
}
