package com.tejas.contentreview.caseservice.projection;

import com.tejas.contentreview.caseservice.cases.CaseEntity;
import com.tejas.contentreview.caseservice.cases.CaseRepository;
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

    public CaseProjectionService(CaseRepository cases) {
        this.cases = cases;
    }

    /** Returns true when the case changed. A concurrent update fails the transaction and the record is retried. */
    @Transactional
    public boolean apply(EventEnvelope<ReviewCompletedPayload> event) {
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
