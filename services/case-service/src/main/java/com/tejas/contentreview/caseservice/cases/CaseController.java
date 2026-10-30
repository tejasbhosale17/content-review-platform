package com.tejas.contentreview.caseservice.cases;

import com.tejas.contentreview.caseservice.cases.CaseDtos.CaseOutcome;
import com.tejas.contentreview.caseservice.cases.CaseDtos.CaseResponse;
import com.tejas.contentreview.caseservice.cases.CaseDtos.CreateCaseRequest;
import com.tejas.contentreview.caseservice.web.ApiException;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/cases")
public class CaseController {

    private static final int MAX_KEY_LENGTH = 128;

    private final CaseService service;

    public CaseController(CaseService service) {
        this.service = service;
    }

    /** X-Actor-Id is a temporary stand-in for the authenticated principal added in the access-control stage. */
    @PostMapping
    public ResponseEntity<CaseResponse> create(
            @RequestHeader("X-Actor-Id") String actorId,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateCaseRequest request) {
        if (actorId.isBlank() || actorId.length() > 64) {
            throw ApiException.badRequest("X-Actor-Id must be 1-64 characters");
        }
        if (idempotencyKey != null && (idempotencyKey.isBlank() || idempotencyKey.length() > MAX_KEY_LENGTH)) {
            throw ApiException.badRequest("Idempotency-Key must be 1-" + MAX_KEY_LENGTH + " characters");
        }
        CaseOutcome outcome = service.createCase(actorId.trim(), idempotencyKey, request);
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(HttpStatus.CREATED);
        if (outcome.replayed()) {
            builder.header("Idempotency-Replayed", "true");
        }
        return builder.body(outcome.body());
    }

    @GetMapping("/{caseId}")
    public CaseResponse get(@PathVariable String caseId) {
        return service.getCase(caseId);
    }
}
