package com.tejas.contentreview.caseservice.idempotency;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface IdempotencyRepository extends JpaRepository<IdempotencyRecord, Long> {

    Optional<IdempotencyRecord> findByActorIdAndOperationAndIdempotencyKey(String actorId, String operation, String key);
}
