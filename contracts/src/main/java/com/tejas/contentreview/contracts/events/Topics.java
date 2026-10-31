package com.tejas.contentreview.contracts.events;

public final class Topics {

    public static final String REVIEW_REQUESTED = "case.review-requested.v1";
    public static final String REVIEW_COMPLETED = "review.completed.v1";

    public static final String GROUP_REVIEW_WORKERS = "review-workers";
    public static final String GROUP_CASE_PROJECTION = "case-projection";
    public static final String GROUP_CONTENT_PROJECTION = "content-projection";

    /** Dead-letter topic per consumer group, so groups sharing a topic never mix their failures. */
    public static String deadLetter(String topic, String group) {
        return topic + "." + group + ".dlt";
    }

    private Topics() {
    }
}
