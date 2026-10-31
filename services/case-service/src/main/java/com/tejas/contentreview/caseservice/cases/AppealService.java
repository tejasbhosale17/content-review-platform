package com.tejas.contentreview.caseservice.cases;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tejas.contentreview.caseservice.cases.CaseDtos.AppealOutcome;
import com.tejas.contentreview.caseservice.cases.CaseDtos.AppealResponse;
import com.tejas.contentreview.caseservice.cases.CaseDtos.CreateAppealRequest;
import com.tejas.contentreview.caseservice.idempotency.IdempotencyRecord;
import com.tejas.contentreview.caseservice.idempotency.IdempotencyRepository;
import com.tejas.contentreview.caseservice.reliability.OutboxStore;
import com.tejas.contentreview.caseservice.web.ApiException;
import com.tejas.contentreview.contracts.events.EventCodec;
import com.tejas.contentreview.contracts.events.EventEnvelope;
import com.tejas.contentreview.contracts.events.ReviewRequestedPayload;
import com.tejas.contentreview.contracts.events.Topics;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Starts the single appeal allowed per case. The state change, the appeal row, the idempotency record and the round-2
 * ReviewRequested outbox row commit in one transaction. The state change is a database compare-and-set, so concurrent
 * or repeated appeals cannot both succeed.
 */
@Service
public class AppealService {

    static final String OP_CREATE_APPEAL = "CREATE_APPEAL";

    private final CaseRepository cases;
    private final AppealRepository appeals;
    private final IdempotencyRepository idempotency;
    private final OutboxStore outbox;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;

    public AppealService(CaseRepository cases, AppealRepository appeals, IdempotencyRepository idempotency,
                         OutboxStore outbox, ObjectMapper mapper, PlatformTransactionManager txManager) {
        this.cases = cases;
        this.appeals = appeals;
        this.idempotency = idempotency;
        this.outbox = outbox;
        this.mapper = mapper;
        this.tx = new TransactionTemplate(txManager);
    }

    public AppealOutcome appeal(String appellantId, String idempotencyKey, String caseId, CreateAppealRequest request) {
        String statement = request.statement().trim();
        String hash = hash(caseId + "\n" + statement);

        if (idempotencyKey != null) {
            Optional<AppealOutcome> replay = replay(appellantId, idempotencyKey, hash);
            if (replay.isPresent()) {
                return replay.get();
            }
        }
        if (!cases.existsById(caseId)) {
            throw ApiException.notFound("Case " + caseId + " not found");
        }

        try {
            AppealResponse created = tx.execute(s -> persist(appellantId, idempotencyKey, hash, caseId, statement));
            return new AppealOutcome(created, false);
        } catch (DataIntegrityViolationException e) {
            if (idempotencyKey != null) {
                Optional<AppealOutcome> replay = replay(appellantId, idempotencyKey, hash);
                if (replay.isPresent()) {
                    return replay.get();
                }
            }
            throw ApiException.conflict("Case " + caseId + " has already been appealed");
        }
    }

    private AppealResponse persist(String appellantId, String key, String hash, String caseId, String statement) {
        IdempotencyRecord record = null;
        if (key != null) {
            record = idempotency.saveAndFlush(new IdempotencyRecord(appellantId, OP_CREATE_APPEAL, key, hash, now()));
        }
        int changed = cases.transition(caseId, CaseStatus.RESTRICTED, CaseStatus.APPEAL_PENDING, now());
        if (changed == 0) {
            CaseStatus current = cases.findById(caseId).map(CaseEntity::getStatus).orElse(null);
            throw ApiException.conflict("Case is " + current + "; only a RESTRICTED case can be appealed, once");
        }
        CaseEntity c = cases.findById(caseId).orElseThrow();
        Appeal appeal = appeals.saveAndFlush(new Appeal(UUID.randomUUID().toString(), caseId, c.getContentId(),
                appellantId, statement, now()));
        var requested = new EventEnvelope<>(UUID.randomUUID(), "ReviewRequested", 1, Instant.now(), caseId,
                c.getContentId(), c.getReviewRound(), UUID.randomUUID().toString(),
                new ReviewRequestedPayload(statement, true));
        outbox.add(requested.eventId().toString(), Topics.REVIEW_REQUESTED, caseId, EventCodec.toJson(requested));
        AppealResponse response = new AppealResponse(appeal.getAppealId(), caseId, c.getContentId(), statement,
                c.getStatus(), c.getReviewRound(), appeal.getCreatedAt());
        if (record != null) {
            // The compare-and-set cleared the persistence context, so the record is detached and must be saved explicitly.
            record.complete(201, write(response));
            idempotency.saveAndFlush(record);
        }
        return response;
    }

    private Optional<AppealOutcome> replay(String appellantId, String key, String hash) {
        return idempotency.findByActorIdAndOperationAndIdempotencyKey(appellantId, OP_CREATE_APPEAL, key).map(record -> {
            if (!record.getRequestHash().equals(hash)) {
                throw ApiException.conflict("Idempotency-Key was already used with a different request payload");
            }
            if (record.getResponseBody() == null) {
                throw ApiException.conflict("A request with this Idempotency-Key is still being processed");
            }
            return new AppealOutcome(read(record.getResponseBody()), true);
        });
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    private String write(AppealResponse response) {
        try {
            return mapper.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private AppealResponse read(String json) {
        try {
            return mapper.readValue(json, AppealResponse.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hash(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
