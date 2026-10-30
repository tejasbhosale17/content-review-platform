package com.tejas.contentreview.reviewservice.review;

import com.tejas.contentreview.contracts.events.ReviewCompletedPayload.Decision;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "review_decisions")
public class ReviewDecision {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "case_id", nullable = false)
    private String caseId;

    @Column(name = "review_round", nullable = false)
    private int reviewRound;

    @Column(name = "content_id", nullable = false)
    private String contentId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Decision decision;

    @Column(name = "policy_version", nullable = false)
    private String policyVersion;

    @Column(nullable = false)
    private String rationale;

    /** Generated once with the decision so every republication of the result carries the same event ID. */
    @Column(name = "result_event_id", nullable = false)
    private String resultEventId;

    @Column(name = "correlation_id", nullable = false)
    private String correlationId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ReviewDecision() {
    }

    public ReviewDecision(String caseId, int reviewRound, String contentId, Decision decision, String policyVersion,
                          String rationale, String correlationId, Instant createdAt) {
        this.caseId = caseId;
        this.reviewRound = reviewRound;
        this.contentId = contentId;
        this.decision = decision;
        this.policyVersion = policyVersion;
        this.rationale = rationale;
        this.resultEventId = UUID.randomUUID().toString();
        this.correlationId = correlationId;
        this.createdAt = createdAt;
    }

    public String getCaseId() { return caseId; }
    public int getReviewRound() { return reviewRound; }
    public String getContentId() { return contentId; }
    public Decision getDecision() { return decision; }
    public String getPolicyVersion() { return policyVersion; }
    public String getRationale() { return rationale; }
    public String getResultEventId() { return resultEventId; }
    public String getCorrelationId() { return correlationId; }
    public Instant getCreatedAt() { return createdAt; }
}
