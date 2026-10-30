package com.tejas.contentreview.reviewservice.content;

import java.util.Optional;

/**
 * Source of content metadata for reviews. The current implementation is a temporary HTTP client;
 * the gRPC stage replaces it without changing callers.
 */
public interface ContentMetadataClient {

    Optional<ContentMetadata> get(String contentId);

    class ContentUnavailableException extends RuntimeException {
        public ContentUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
