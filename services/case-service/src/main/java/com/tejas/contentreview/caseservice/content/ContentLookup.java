package com.tejas.contentreview.caseservice.content;

import java.util.Optional;

/** Verifies that referenced content exists and returns its owner. Implemented over gRPC; tests may substitute it. */
public interface ContentLookup {

    Optional<ContentInfo> find(String contentId);

    /** Temporary failure (service down, transport error, deadline). */
    class ContentUnavailableException extends RuntimeException {
        public ContentUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
