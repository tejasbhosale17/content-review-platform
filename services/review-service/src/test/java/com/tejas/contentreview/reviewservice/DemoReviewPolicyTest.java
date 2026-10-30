package com.tejas.contentreview.reviewservice;

import static org.assertj.core.api.Assertions.assertThat;

import com.tejas.contentreview.contracts.events.ReviewCompletedPayload.Decision;
import com.tejas.contentreview.reviewservice.content.ContentMetadata;
import com.tejas.contentreview.reviewservice.review.DemoReviewPolicy;
import org.junit.jupiter.api.Test;

class DemoReviewPolicyTest {

    private final DemoReviewPolicy policy = new DemoReviewPolicy();

    private static ContentMetadata content(boolean licenseValid, String note) {
        return new ContentMetadata("c", "creator", licenseValid, note);
    }

    @Test
    void roundOneFollowsLicenseFlag() {
        assertThat(policy.evaluate(1, content(true, null)).decision()).isEqualTo(Decision.ALLOW);
        assertThat(policy.evaluate(1, content(false, null)).decision()).isEqualTo(Decision.RESTRICT);
    }

    @Test
    void roundOneIgnoresAppealEvidence() {
        assertThat(policy.evaluate(1, content(false, "APPEAL_EVIDENCE_OK")).decision()).isEqualTo(Decision.RESTRICT);
    }

    @Test
    void roundTwoUsesEvidenceFixtureNotLicenseFlag() {
        assertThat(policy.evaluate(2, content(false, "note APPEAL_EVIDENCE_OK")).decision()).isEqualTo(Decision.ALLOW);
        assertThat(policy.evaluate(2, content(true, "nothing")).decision()).isEqualTo(Decision.RESTRICT);
        assertThat(policy.evaluate(2, content(true, null)).decision()).isEqualTo(Decision.RESTRICT);
    }

    @Test
    void decisionsAreDeterministic() {
        var c = content(false, null);
        assertThat(policy.evaluate(1, c)).isEqualTo(policy.evaluate(1, c));
    }
}
