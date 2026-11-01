package com.tejas.contentreview.caseservice.cases;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tejas.contentreview.caseservice.cases.CaseDtos.CaseOutcome;
import com.tejas.contentreview.caseservice.cases.CaseDtos.CaseResponse;
import com.tejas.contentreview.caseservice.cases.CaseDtos.CreateCaseRequest;
import com.tejas.contentreview.caseservice.content.ContentInfo;
import com.tejas.contentreview.caseservice.content.ContentLookup;
import com.tejas.contentreview.caseservice.idempotency.IdempotencyRecord;
import com.tejas.contentreview.caseservice.idempotency.IdempotencyRepository;
import com.tejas.contentreview.caseservice.web.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import com.tejas.contentreview.caseservice.reliability.OutboxStore;
import com.tejas.contentreview.contracts.events.EventCodec;
import com.tejas.contentreview.contracts.events.EventEnvelope;
import com.tejas.contentreview.contracts.events.ReviewRequestedPayload;
import com.tejas.contentreview.contracts.events.Topics;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class CaseService {

    static final String OP_CREATE_CASE = "CREATE_CASE";

    private final CaseRepository cases;
    private final IdempotencyRepository idempotency;
    private final ContentLookup contentLookup;
    private final ObjectMapper mapper;
    private final TransactionTemplate tx;
    private final OutboxStore outbox;
    private final MeterRegistry meters;

    public CaseService(CaseRepository cases, IdempotencyRepository idempotency, ContentLookup contentLookup,
                       ObjectMapper mapper, PlatformTransactionManager txManager, OutboxStore outbox,
                       MeterRegistry meters) {
        this.meters = meters;
        this.cases = cases;
        this.idempotency = idempotency;
        this.contentLookup = contentLookup;
        this.mapper = mapper;
        this.tx = new TransactionTemplate(txManager);
        this.outbox = outbox;
    }

    public CaseOutcome createCase(String reporterId, String idempotencyKey, CreateCaseRequest request) {
        String contentId = request.contentId().trim();
        String reason = request.reason().trim();
        String hash = hash(contentId + "\n" + reason);

        if (idempotencyKey != null) {
            Optional<CaseOutcome> replay = replay(reporterId, idempotencyKey, hash);
            if (replay.isPresent()) {
                return replay.get();
            }
        }

        // Remote call happens outside any database transaction.
        ContentInfo content = lookupContent(contentId);

        try {
            CaseResponse created = tx.execute(status -> persist(reporterId, idempotencyKey, hash, content, reason));
            meters.counter("complaints.accepted").increment();
            return new CaseOutcome(created, false);
        } catch (DataIntegrityViolationException e) {
            // A concurrent request won: either the same idempotency key or a case for this content already exists.
            if (idempotencyKey != null) {
                Optional<CaseOutcome> replay = replay(reporterId, idempotencyKey, hash);
                if (replay.isPresent()) {
                    return replay.get();
                }
            }
            throw ApiException.conflict("A case already exists for content " + contentId);
        }
    }

    public CaseResponse getCase(String caseId, String principal, boolean admin) {
        CaseEntity c = cases.findById(caseId)
                .orElseThrow(() -> ApiException.notFound("Case " + caseId + " not found"));
        if (!admin && !principal.equals(c.getReporterId()) && !principal.equals(c.getCreatorId())) {
            throw ApiException.forbidden("You are not a party to this case");
        }
        return CaseResponse.from(c);
    }

    private CaseResponse persist(String reporterId, String key, String hash, ContentInfo content, String reason) {
        IdempotencyRecord record = null;
        if (key != null) {
            // Inserted first so concurrent requests with the same key serialize on the unique index.
            record = idempotency.saveAndFlush(new IdempotencyRecord(reporterId, OP_CREATE_CASE, key, hash, now()));
        }
        CaseEntity entity = new CaseEntity(UUID.randomUUID().toString(), content.contentId(), content.creatorId(),
                reporterId, reason, now());
        cases.saveAndFlush(entity);
        var requested = new EventEnvelope<>(UUID.randomUUID(), "ReviewRequested", 1, Instant.now(), entity.getCaseId(),
                entity.getContentId(), entity.getReviewRound(), correlationId(),
                new ReviewRequestedPayload(reason, false));
        outbox.add(requested.eventId().toString(), Topics.REVIEW_REQUESTED, entity.getCaseId(), EventCodec.toJson(requested));
        CaseResponse response = CaseResponse.from(entity);
        if (record != null) {
            record.complete(201, write(response));
        }
        return response;
    }

    private static String correlationId() {
        String current = MDC.get("correlationId");
        return current != null ? current : UUID.randomUUID().toString();
    }

    private Optional<CaseOutcome> replay(String reporterId, String key, String hash) {
        return idempotency.findByActorIdAndOperationAndIdempotencyKey(reporterId, OP_CREATE_CASE, key).map(record -> {
            if (!record.getRequestHash().equals(hash)) {
                throw ApiException.conflict("Idempotency-Key was already used with a different request payload");
            }
            if (record.getResponseBody() == null) {
                throw ApiException.conflict("A request with this Idempotency-Key is still being processed");
            }
            return new CaseOutcome(read(record.getResponseBody()), true);
        });
    }

    private ContentInfo lookupContent(String contentId) {
        try {
            return contentLookup.find(contentId)
                    .orElseThrow(() -> ApiException.notFound("Content " + contentId + " not found"));
        } catch (ContentLookup.ContentUnavailableException e) {
            throw ApiException.unavailable("Content service is temporarily unavailable");
        }
    }

    private static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    private String write(CaseResponse response) {
        try {
            return mapper.writeValueAsString(response);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private CaseResponse read(String json) {
        try {
            return mapper.readValue(json, CaseResponse.class);
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
