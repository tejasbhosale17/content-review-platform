package com.tejas.contentreview.contracts.events;

/** Payload of review.completed.v1. */
public record ReviewCompletedPayload(Decision decision, String policyVersion, String rationale) {

    public enum Decision {
        ALLOW,
        RESTRICT
    }
}
