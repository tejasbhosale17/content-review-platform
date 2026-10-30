package com.tejas.contentreview.contentservice.content;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.Instant;

public final class ContentDtos {

    private ContentDtos() {
    }

    /** creatorId is taken from the body until authentication is added in the access-control stage. */
    public record CreateContentRequest(
            @NotBlank @Size(max = 64) String creatorId,
            @NotBlank @Size(max = 200) String title,
            @NotNull Boolean licenseValid,
            @Size(max = 500) String licenseNote) {
    }

    public record ContentResponse(
            String contentId,
            String creatorId,
            String title,
            boolean licenseValid,
            String licenseNote,
            Content.Visibility visibility,
            int appliedReviewRound,
            Instant createdAt) {

        public static ContentResponse from(Content c) {
            return new ContentResponse(c.getContentId(), c.getCreatorId(), c.getTitle(), c.isLicenseValid(),
                    c.getLicenseNote(), c.getVisibility(), c.getAppliedReviewRound(), c.getCreatedAt());
        }
    }
}
