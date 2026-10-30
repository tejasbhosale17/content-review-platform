package com.tejas.contentreview.contentservice.content;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

@Entity
@Table(name = "contents")
public class Content {

    public enum Visibility {
        VISIBLE,
        RESTRICTED
    }

    @Id
    @Column(name = "content_id", length = 36)
    private String contentId;

    @Column(name = "creator_id", nullable = false)
    private String creatorId;

    @Column(nullable = false)
    private String title;

    @Column(name = "license_valid", nullable = false)
    private boolean licenseValid;

    @Column(name = "license_note")
    private String licenseNote;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Visibility visibility;

    @Column(name = "applied_review_round", nullable = false)
    private int appliedReviewRound;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected Content() {
    }

    public Content(String contentId, String creatorId, String title, boolean licenseValid, String licenseNote, Instant now) {
        this.contentId = contentId;
        this.creatorId = creatorId;
        this.title = title;
        this.licenseValid = licenseValid;
        this.licenseNote = licenseNote;
        this.visibility = Visibility.VISIBLE;
        this.appliedReviewRound = 0;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public String getContentId() { return contentId; }
    public String getCreatorId() { return creatorId; }
    public String getTitle() { return title; }
    public boolean isLicenseValid() { return licenseValid; }
    public String getLicenseNote() { return licenseNote; }
    public Visibility getVisibility() { return visibility; }
    public int getAppliedReviewRound() { return appliedReviewRound; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
