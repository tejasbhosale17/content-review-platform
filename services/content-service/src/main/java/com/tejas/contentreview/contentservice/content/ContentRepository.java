package com.tejas.contentreview.contentservice.content;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import java.time.Instant;

public interface ContentRepository extends JpaRepository<Content, String> {

    /** Atomic compare-and-set: applies only when the stored round is strictly lower than the incoming one. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update Content c set c.visibility = :visibility, c.appliedReviewRound = :round, c.updatedAt = :now "
            + "where c.contentId = :id and c.appliedReviewRound < :round")
    int applyReviewRound(@Param("id") String id, @Param("round") int round,
                         @Param("visibility") Content.Visibility visibility, @Param("now") Instant now);
}
