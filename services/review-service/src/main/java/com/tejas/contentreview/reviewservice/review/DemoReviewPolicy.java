package com.tejas.contentreview.reviewservice.review;

import com.tejas.contentreview.contracts.events.ReviewCompletedPayload.Decision;
import com.tejas.contentreview.reviewservice.content.ContentMetadata;
import org.springframework.stereotype.Component;

/**
 * Demonstration rules (synthetic data, no real analysis):
 * <ul>
 *   <li>Round 1: ALLOW when the content's synthetic licenseValid flag is true, otherwise RESTRICT.</li>
 *   <li>Round 2 (appeal): synthetic evidence fixture. ALLOW only when licenseNote contains
 *       {@value #APPEAL_EVIDENCE_MARKER}; otherwise RESTRICT.</li>
 * </ul>
 */
@Component
public class DemoReviewPolicy implements ReviewPolicy {

    public static final String APPEAL_EVIDENCE_MARKER = "APPEAL_EVIDENCE_OK";
    static final String VERSION = "demo-v1";

    @Override
    public String version() {
        return VERSION;
    }

    @Override
    public Result evaluate(int reviewRound, ContentMetadata content) {
        if (reviewRound == 1) {
            return content.licenseValid()
                    ? new Result(Decision.ALLOW, "Synthetic license is valid")
                    : new Result(Decision.RESTRICT, "Synthetic license is not valid");
        }
        boolean evidence = content.licenseNote() != null && content.licenseNote().contains(APPEAL_EVIDENCE_MARKER);
        return evidence
                ? new Result(Decision.ALLOW, "Appeal evidence fixture accepted")
                : new Result(Decision.RESTRICT, "Appeal evidence fixture missing or insufficient");
    }
}
