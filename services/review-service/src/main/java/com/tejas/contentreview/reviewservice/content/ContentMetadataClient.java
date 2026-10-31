package com.tejas.contentreview.reviewservice.content;

import java.util.Optional;

/** Source of content metadata for reviews. Implemented over gRPC; tests may substitute a double. */
public interface ContentMetadataClient {

    /** Returns empty when the content does not exist. */
    Optional<ContentMetadata> get(String contentId);

    /** Temporary failure (service down, transport error, deadline). Safe to retry. */
    class ContentUnavailableException extends RuntimeException {
        public ContentUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** The RPC did not finish within its deadline. Retryable, reported separately from an unreachable service. */
    class ContentDeadlineExceededException extends ContentUnavailableException {
        public ContentDeadlineExceededException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    /** The server rejected the request itself; retrying the same request cannot succeed. */
    class InvalidContentRequestException extends RuntimeException {
        public InvalidContentRequestException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
