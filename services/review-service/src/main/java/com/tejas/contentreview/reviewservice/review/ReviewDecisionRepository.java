package com.tejas.contentreview.reviewservice.review;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ReviewDecisionRepository extends JpaRepository<ReviewDecision, Long> {

    Optional<ReviewDecision> findByCaseIdAndReviewRound(String caseId, int reviewRound);
}
