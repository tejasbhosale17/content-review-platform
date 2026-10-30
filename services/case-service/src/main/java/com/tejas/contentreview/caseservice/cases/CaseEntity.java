package com.tejas.contentreview.caseservice.cases;

import com.tejas.contentreview.contracts.events.ReviewCompletedPayload.Decision;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.Instant;

@Entity
@Table(name = "review_cases")
public class CaseEntity {

    @Id
    @Column(name = "case_id", length = 36)
    private String caseId;

    @Column(name = "content_id", nullable = false)
    private String contentId;

    @Column(name = "creator_id", nullable = false)
    private String creatorId;

    @Column(name = "reporter_id", nullable = false)
    private String reporterId;

    @Column(nullable = false)
    private String reason;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CaseStatus status;

    @Column(name = "review_round", nullable = false)
    private int reviewRound;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected CaseEntity() {
    }

    public CaseEntity(String caseId, String contentId, String creatorId, String reporterId, String reason, Instant now) {
        this.caseId = caseId;
        this.contentId = contentId;
        this.creatorId = creatorId;
        this.reporterId = reporterId;
        this.reason = reason;
        this.status = CaseStatus.PENDING_REVIEW;
        this.reviewRound = 1;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /**
     * Applies a review result only when it matches the expected round and a state that is waiting for it.
     * Anything else (duplicate, stale, out of order) leaves the case untouched.
     */
    public boolean applyReviewResult(int round, Decision decision, Instant now) {
        CaseStatus next = null;
        if (status == CaseStatus.PENDING_REVIEW && reviewRound == 1 && round == 1) {
            next = decision == Decision.ALLOW ? CaseStatus.DISMISSED : CaseStatus.RESTRICTED;
        } else if (status == CaseStatus.APPEAL_PENDING && reviewRound == 2 && round == 2) {
            next = decision == Decision.ALLOW ? CaseStatus.RESTORED : CaseStatus.RESTRICTION_CONFIRMED;
        }
        if (next == null) {
            return false;
        }
        this.status = next;
        this.updatedAt = now;
        return true;
    }

    public String getCaseId() { return caseId; }
    public String getContentId() { return contentId; }
    public String getCreatorId() { return creatorId; }
    public String getReporterId() { return reporterId; }
    public String getReason() { return reason; }
    public CaseStatus getStatus() { return status; }
    public int getReviewRound() { return reviewRound; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
