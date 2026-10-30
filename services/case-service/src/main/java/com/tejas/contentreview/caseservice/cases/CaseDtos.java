package com.tejas.contentreview.caseservice.cases;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public final class CaseDtos {

    private CaseDtos() {
    }

    public record CreateCaseRequest(
            @NotBlank @Size(max = 36) String contentId,
            @NotBlank @Size(max = 1000) String reason) {
    }

    public record CaseResponse(
            String caseId,
            String contentId,
            String reporterId,
            String reason,
            CaseStatus status,
            int reviewRound,
            Instant createdAt) {

        public static CaseResponse from(CaseEntity c) {
            return new CaseResponse(c.getCaseId(), c.getContentId(), c.getReporterId(), c.getReason(), c.getStatus(),
                    c.getReviewRound(), c.getCreatedAt());
        }
    }

    /** Result of a create call; replayed is true when an earlier idempotent result was returned. */
    public record CaseOutcome(CaseResponse body, boolean replayed) {
    }
}
