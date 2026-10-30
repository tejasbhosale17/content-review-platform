package com.tejas.contentreview.contracts.events;

/** Payload of case.review-requested.v1. */
public record ReviewRequestedPayload(String reason, boolean appeal) {
}
