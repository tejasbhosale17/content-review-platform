package com.tejas.contentreview.caseservice.content;

import java.util.Optional;

/** Verifies that referenced content exists. Implemented over HTTP; isolated so tests can substitute it. */
public interface ContentLookup {

    Optional<ContentInfo> find(String contentId);

    class ContentUnavailableException extends RuntimeException {
        public ContentUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
