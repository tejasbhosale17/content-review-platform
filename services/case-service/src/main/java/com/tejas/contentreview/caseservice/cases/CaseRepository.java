package com.tejas.contentreview.caseservice.cases;

import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface CaseRepository extends JpaRepository<CaseEntity, String> {

    /**
     * Atomic compare-and-set RESTRICTED -> APPEAL_PENDING (round 2). Returns 0 when the case is in any other state,
     * so two concurrent appeals can never both start.
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("update CaseEntity c set c.status = :next, c.reviewRound = 2, c.updatedAt = :now, c.version = c.version + 1 "
            + "where c.caseId = :id and c.status = :expected")
    int transition(@Param("id") String id, @Param("expected") CaseStatus expected, @Param("next") CaseStatus next,
                   @Param("now") Instant now);
}
