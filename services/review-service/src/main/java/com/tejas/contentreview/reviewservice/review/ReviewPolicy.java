package com.tejas.contentreview.reviewservice.review;

import com.tejas.contentreview.contracts.events.ReviewCompletedPayload.Decision;
import com.tejas.contentreview.reviewservice.content.ContentMetadata;

/** Replaceable review policy. The shipped implementation is a deterministic demonstration only. */
public interface ReviewPolicy {

    String version();

    Result evaluate(int reviewRound, ContentMetadata content);

    record Result(Decision decision, String rationale) {
    }
}
