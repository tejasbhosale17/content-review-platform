package com.tejas.contentreview.caseservice.cases;

import com.tejas.contentreview.caseservice.cases.CaseDtos.AppealOutcome;
import com.tejas.contentreview.caseservice.cases.CaseDtos.AppealResponse;
import com.tejas.contentreview.caseservice.cases.CaseDtos.CaseOutcome;
import com.tejas.contentreview.caseservice.cases.CaseDtos.CreateAppealRequest;
import com.tejas.contentreview.caseservice.cases.CaseDtos.CaseResponse;
import com.tejas.contentreview.caseservice.cases.CaseDtos.CreateCaseRequest;
import com.tejas.contentreview.caseservice.web.ApiException;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
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
    private final AppealService appealService;

    public CaseController(CaseService service, AppealService appealService) {
        this.service = service;
        this.appealService = appealService;
    }

    @PostMapping
    public ResponseEntity<CaseResponse> create(
            Authentication auth,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateCaseRequest request) {
        String actorId = auth.getName();
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
    public CaseResponse get(@PathVariable String caseId, Authentication auth) {
        boolean admin = auth.getAuthorities().stream().anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
        return service.getCase(caseId, auth.getName(), admin);
    }

    @PostMapping("/{caseId}/appeals")
    public ResponseEntity<AppealResponse> appeal(
            @PathVariable String caseId,
            Authentication auth,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @Valid @RequestBody CreateAppealRequest request) {
        String actorId = auth.getName();
        if (idempotencyKey != null && (idempotencyKey.isBlank() || idempotencyKey.length() > MAX_KEY_LENGTH)) {
            throw ApiException.badRequest("Idempotency-Key must be 1-" + MAX_KEY_LENGTH + " characters");
        }
        AppealOutcome outcome = appealService.appeal(actorId.trim(), idempotencyKey, caseId, request);
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(HttpStatus.CREATED);
        if (outcome.replayed()) {
            builder.header("Idempotency-Replayed", "true");
        }
        return builder.body(outcome.body());
    }
}
