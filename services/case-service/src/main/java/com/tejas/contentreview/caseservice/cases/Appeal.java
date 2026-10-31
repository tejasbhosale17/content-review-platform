package com.tejas.contentreview.caseservice.cases;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "appeals")
public class Appeal {

    @Id
    @Column(name = "appeal_id", length = 36)
    private String appealId;

    @Column(name = "case_id", nullable = false)
    private String caseId;

    @Column(name = "content_id", nullable = false)
    private String contentId;

    @Column(name = "appellant_id", nullable = false)
    private String appellantId;

    @Column(nullable = false)
    private String statement;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Appeal() {
    }

    public Appeal(String appealId, String caseId, String contentId, String appellantId, String statement, Instant now) {
        this.appealId = appealId;
        this.caseId = caseId;
        this.contentId = contentId;
        this.appellantId = appellantId;
        this.statement = statement;
        this.createdAt = now;
    }

    public String getAppealId() { return appealId; }
    public String getCaseId() { return caseId; }
    public String getContentId() { return contentId; }
    public String getAppellantId() { return appellantId; }
    public String getStatement() { return statement; }
    public Instant getCreatedAt() { return createdAt; }
}
