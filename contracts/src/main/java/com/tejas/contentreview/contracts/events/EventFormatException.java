package com.tejas.contentreview.contracts.events;

/** Thrown when an event cannot be parsed or violates the envelope contract. Not retryable. */
public class EventFormatException extends RuntimeException {

    public EventFormatException(String message, Throwable cause) {
        super(message, cause);
    }
}
