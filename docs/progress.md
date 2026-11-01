# Progress

## Stage 1 - Scaffold Java services and Maven build (complete)

Completed:
- Maven multi-module build (`contracts`, `case-service`, `review-service`, `content-service`) on Spring Boot 3.5.16, Java 21, committed Maven wrapper.
- `contracts`: `EventEnvelope`, topic/consumer-group constants, review payload records, and `contentmetadata.proto` (`ContentMetadata.GetContent`). Java classes are generated at build time, not committed.
- Each service boots with Actuator health on ports 8081 / 8082 / 8083.
- `.gitignore`, `.env.example`, `docs/versions.md`, repo directory layout.

Verification (actual): `.\mvnw.cmd -B verify` on Windows, JDK 21.0.10 -> BUILD SUCCESS.
- contracts: 4 tests passed (protobuf generation/round trip, envelope JSON, validation, topic names)
- case-service, review-service, content-service: 1 test each passed (context starts, `/actuator/health` is UP)

Not yet done: everything functional (persistence, Kafka, gRPC server/client, security, Docker, Kubernetes, CI, README).
Unresolved: kind and kubectl are not installed on this machine (needed for the Kubernetes stage).

## Stage 2 - Content and complaint persistence (complete)

Completed:
- content-service: Flyway `V1__create_contents.sql`; `POST /api/contents`, `GET /api/contents/{id}` (creator, title, synthetic license metadata, visibility, applied review round); consistent JSON errors (400/404).
- case-service: Flyway `V1` creates `review_cases` (unique `content_id` = one case per content; status/round CHECK constraints) and `idempotency_keys` (unique actor/operation/key).
- `POST /api/cases` creates a case in `PENDING_REVIEW`, round 1, after verifying the content exists over HTTP (`RestContentLookup`, 1s connect / 2s read timeouts, outside any DB transaction). `GET /api/cases/{id}`.
- `Idempotency-Key`: same key + same payload returns the original body (`Idempotency-Replayed: true`); same key + different payload returns 409. The key row is inserted first in the same transaction as the case, so concurrent duplicates serialize on the unique index. A second complaint for the same content returns 409.
- Error mapping: 400 validation/malformed/missing header, 404 unknown content or case, 409 conflicts, 503 content-service unavailable.

Known interim behavior (to be replaced):
- The reporter identity comes from the `X-Actor-Id` header and `creatorId` from the content request body. Both are placeholders until the access-control stage derives identity from authentication.
- No outbox or Kafka publication yet, so a new case stays `PENDING_REVIEW`. The appeals endpoint and the "one appeal per case" constraint come in the appeals stage.

Verification (actual): `.\mvnw.cmd -B verify`, JDK 21.0.10, real MySQL 8.4.13 via Testcontainers -> BUILD SUCCESS.
- contracts: 4 passed
- case-service: 13 passed (`CaseApiTest` 9: create/get, validation and unknown IDs, 503 on outage, duplicate content 409, matching/conflicting idempotency keys, 8-thread same-key race -> 1 case, 8-thread different-key race -> 1 created + 7 conflicts; `RestContentLookupTest` 4: found, 404, read timeout, connection refused against a real local HTTP server)
- content-service: 4 passed (create/get, validation, unknown ID, health)
- review-service: 1 passed (health)
- During development Flyway/Hibernate schema validation caught a `CHAR` vs `VARCHAR` mismatch, which was fixed in the migration before the final run.

Unresolved: no local MySQL for running the services by hand yet (Compose arrives in the Docker stage). kind/kubectl still not installed.

## Stage 3 - Kafka review workflow (complete)

Completed:
- `contracts`: `EventCodec` (JSON <-> `EventEnvelope`, malformed input raises non-retryable `EventFormatException`).
- case-service: creating a case publishes `ReviewRequested` to `case.review-requested.v1` (keyed by caseId) after the DB commit. A `case-projection` consumer applies `ReviewCompleted` results: round 1 -> DISMISSED/RESTRICTED, only from PENDING_REVIEW; duplicates and stale results are ignored by state.
- review-service: `review-workers` consumer persists one decision per (case, round) (unique constraints), calls content metadata over HTTP (interim, gRPC later) outside the DB transaction, then publishes `ReviewCompleted` reusing the stored result event ID on redelivery. Demo policy: round 1 ALLOW iff license valid; round 2 ALLOW iff note contains `APPEAL_EVIDENCE_OK`.
- content-service: `content-projection` consumer updates visibility with one atomic conditional UPDATE (`applied_review_round < :round`). Equal round + same decision = duplicate, equal round + different decision = logged conflict, older round = ignored, unknown content = logged and ignored.
- Topics: 3 partitions, replication factor 1 (local). Consumers use manual per-record ack, `acks=all`, idempotent producer.

Known interim behavior (replaced in the next stage):
- Publishing is direct after commit, not an outbox: a crash between commit and send loses the request.
- No consumer receipts table; no dead-letter topics. After 5 backoff attempts a failing record is skipped and logged.
- Content metadata over HTTP instead of gRPC; no appeals endpoint yet.

Verification (actual): `.\mvnw.cmd -B verify`, JDK 21.0.10, real MySQL 8.4.13 and Kafka (`apache/kafka:3.9.1`) via Testcontainers -> BUILD SUCCESS.
- contracts: 6 passed
- case-service: 19 passed (API/idempotency 9, workflow 6, content lookup 4)
- review-service: 11 passed (policy 4, workflow 7)
- content-service: 8 passed (API 4, projection 4 including a 20-iteration concurrent round-1/round-2 race)
- Not verified: manual run of the three jars together; behavior under broker/service crashes.

## Stage 4 - Outbox publishing and idempotent consumers (complete)
Implemented:
- Transactional outbox (`outbox_events`) in case-service (`ReviewRequested`) and review-service (`ReviewCompleted`); the row is written in the same DB transaction as the business change. A relay publishes in id order using leases, retries with exponential backoff, and exposes `outbox.published`, `outbox.publish.failures` and `outbox.pending` metrics.
- `processed_events` receipts in all three services, written in the same transaction as the effect, so a rollback removes the receipt.
- Per-group dead-letter topics (`<topic>.<group>.dlt`) after 5 backoff attempts; malformed events are not retried. `POST /internal/dlt/replay` re-publishes dead letters (unauthenticated until the access-control stage).
- Delivery is at-least-once: a crash between send and `markPublished` can produce a duplicate, which receipts and round checks make harmless. Per-key ordering assumes a single relay instance.

Verification (`.\mvnw.cmd -B verify`, real MySQL 8.4 and Kafka Testcontainers): BUILD SUCCESS.
- contracts: 6 passed
- case-service: 24 passed (API 9, reliability 5, workflow 6, content lookup 4)
- review-service: 18 passed (policy 4, outbox relay 5, workflow 9)
- content-service: 10 passed (API 4, projection 6 including receipt dedupe and dead-lettering of a malformed record)
- Not verified: manual run of the three jars together; broker/service crash recovery beyond injected failures; DLT replay for content-service has no dedicated test.

## Stage 5 - gRPC content metadata service (complete)
Implemented:
- `contentmetadata.proto` in `contracts`; Java messages and stubs are generated during the Maven build (protobuf-maven-plugin with `protoc-gen-grpc-java`) and not committed. The earlier plugin config used a parameter the plugin ignored, so stubs were silently not generated; fixed with `<plugins><plugin kind="binary-maven">`.
- content-service: gRPC server on `GRPC_PORT` (default 9090) started with the app, `GetContent` plus the standard gRPC health service. Returns `NOT_FOUND` for unknown content, `INVALID_ARGUMENT` for a blank or over-36-character ID, `UNAVAILABLE` if the store fails.
- review-service: `GrpcContentMetadataClient` replaces the interim REST client (removed). Every call has a 2s deadline (`content-service.grpc.deadline`). Mapping: `NOT_FOUND` -> empty (the review attempt fails and goes through retry/DLT), `INVALID_ARGUMENT` -> `InvalidContentRequestException` (not retried, dead-lettered immediately), `DEADLINE_EXCEEDED` -> `ContentDeadlineExceededException`, anything else -> `ContentUnavailableException` (both retried with backoff).
- Target is configured by `CONTENT_SERVICE_GRPC_TARGET`; no hard-coded localhost for container deployments (Compose/Kubernetes wiring comes in the infra stage).
- case-service still validates content over HTTP when a complaint is created; the spec only requires gRPC for review-service.

Verification (`.\mvnw.cmd -B verify`, real MySQL 8.4 and Kafka Testcontainers): BUILD SUCCESS.
- contracts: 6 passed
- case-service: 24 passed
- review-service: 24 passed (client 6, policy 4, outbox relay 5, workflow 9)
- content-service: 14 passed (API 4, gRPC 4, projection 6)
- gRPC server tests call the real server over a real socket against MySQL: found, not found, invalid IDs, health SERVING.
- gRPC client tests use the real client over a real socket against a scripted server: field mapping, not found, invalid argument, server error, deadline expiry (< 3s with a 500ms deadline), unreachable server.
- Not verified: the real review-service talking to the real content-service in one run (workflow tests still stub the metadata client at the Java interface); to be covered by the Compose/Kubernetes and recovery stages.

## Stage 6 - Appeals and stale-event protection (complete)
Implemented:
- `POST /api/cases/{caseId}/appeals` (body `{"statement": "..."}`, `X-Actor-Id`, optional `Idempotency-Key`). Returns 201 with the appeal and the resulting case state (`APPEAL_PENDING`, round 2); 404 for an unknown case, 400 for invalid input, 409 for any case that is not `RESTRICTED` (pending, dismissed, already appealed, restored, confirmed).
- State change is a database compare-and-set (`RESTRICTED -> APPEAL_PENDING`, round 2). In the same transaction: idempotency record, `appeals` row (migration `V3`, unique `case_id` = one appeal per case) and the round-2 `ReviewRequested` outbox row (`appeal=true`).
- Idempotency: same key and payload replays the original response (`Idempotency-Replayed: true`); same key with a different payload returns 409.
- Stale-event protection: case-service applies a result only for the expected round and state; content-service compares rounds atomically (stage 3) and now also dedupes by event ID (stage 4). The review policy still decides round 2 from the synthetic license note fixture (`APPEAL_EVIDENCE_OK`), not from the appeal statement.
- Found and fixed during testing: the compare-and-set clears the persistence context and detached the idempotency record, so its response was not saved and replays returned 409. The record is now saved explicitly.
- Interim: appeals are restricted to the content creator (stage 7).

Verification (`.\mvnw.cmd -B verify`, real MySQL 8.4 and Kafka Testcontainers): BUILD SUCCESS.
- contracts: 6 passed
- case-service: 31 passed (new appeal tests 7: start round 2 and publish request, rejected states/404/400, second and third appeals rejected with a single outbox row, ALLOW restores / RESTRICT confirms, late round-1 results cannot overwrite an appeal in progress or its outcome, idempotent replay and payload conflict, 8 concurrent appeals give exactly one 201, seven 409s, one appeal row and one outbox row)
- review-service: 24 passed
- content-service: 14 passed
- Not verified: a full appeal flowing through the three real services in one run (the case-service tests inject review results through Kafka); to be covered by the Compose/Kubernetes and recovery stages.

## Stage 7 - Access control and observability (complete)
Implemented:
- HTTP Basic (stateless, CSRF off) in all three services with demo accounts `reporter` (REPORTER), `creator` (CREATOR) and `admin` (ADMIN). Passwords come only from `DEMO_*_PASSWORD` environment variables with no defaults, so a service fails to start without them; they are BCrypt-hashed in memory.
- Rules: only REPORTER files complaints (reporter = authenticated user); only the content's creator can appeal (403 otherwise, 404 first for an unknown case); a case is readable by its reporter, its creator or ADMIN; only CREATOR creates content and `creatorId` is the authenticated user (the body field is gone); `/internal/**` and non-health actuator endpoints are ADMIN-only; `/actuator/health` is public. 401/403 return the same JSON error shape. The temporary `X-Actor-Id` header was removed.
- case-service now looks up content over gRPC (2s deadline, content-service port 9090 is not an HTTP API and needs no service credentials) instead of the REST endpoint, which would otherwise have needed service credentials.
- Structured JSON console logs (logstash format) with `service` and MDC fields `caseId`, `eventId`, `reviewRound`, `correlationId`. HTTP requests accept or generate `X-Correlation-Id` (echoed on the response, invalid values replaced); it is carried into the outbox events, and Kafka listeners restore it from the event envelope.
- Metrics (Micrometer, `/actuator/prometheus`, ADMIN-only): `complaints.accepted`, `reviews.completed{decision,round}`, `kafka.consumer.processing{group}` timer, plus the existing outbox and retry/dead-letter metrics; all tagged with `service`.

Verification (`.\mvnw.cmd -B verify`, real MySQL 8.4 and Kafka Testcontainers): BUILD SUCCESS.
- contracts: 6 passed
- case-service: 39 passed (new: `SecurityTest` 7 - 401/403, HTTP Basic, party-only case reads, creator-only appeals, admin-only operations with public health, correlation ID reaching the outbox event, complaint counter; `GrpcContentLookupTest` 5 replaces the REST lookup test)
- review-service: 25 passed (new `ReviewSecurityTest`; `reviews.completed` and processing timer asserted in `ReviewWorkflowTest`)
- content-service: 19 passed (`ContentApiTest` rewritten to 9 tests incl. security and correlation header)
- Existing tests use Spring Security's `user()` post-processor for most calls; the Basic-auth path with real configured passwords is exercised in the security tests.
- Observed in test output: JSON log lines contain `service` and the MDC fields.
- Not verified: Prometheus scrape output over HTTP (metrics are asserted through the `MeterRegistry`), the `DltController` replay with an admin credential (only the 401/403 rejections are tested), and the demo credentials end to end across running services.

## Infra stage - Docker, Compose and local Kubernetes (complete)
Implemented:
- `infra/docker/Dockerfile`: one multi-stage build for all three services (JDK 21.0.10 build, JRE 21.0.10 runtime, non-root uid 10001, `MaxRAMPercentage=75`). `.dockerignore` keeps `.env`, `target/`, docs and scripts out of the build context.
- `compose.yaml`: MySQL 8.4.13 (one instance, three logical databases each with its own user, created by `infra/mysql/init-databases.sh`), a single-node KRaft Kafka 3.9.1, a `kafka-init` job creating topics (`infra/kafka/create-topics.sh`, 3 partitions for the main topics, 1 for the DLTs) and the three services with readiness healthchecks. Ports are bound to 127.0.0.1; secrets come from `.env`.
- `k8s/base/`: namespace and ConfigMap, MySQL and Kafka StatefulSets with PVCs, a topic-init Job, Deployments with RollingUpdate, startup/readiness/liveness probes, resource requests/limits and non-root security context, ClusterIP Services. review-service runs 2 replicas in the `review-workers` group. Secrets and init scripts are created from `.env` and `infra/` by `scripts/k8s-up.ps1`, never committed.
- `scripts/`: `smoke-test.ps1` (end-to-end flow over HTTP), `k8s-up.ps1` (build, create kind cluster, load images, deploy), `k8s-smoke.ps1` (port-forward and smoke test), `k8s-down.ps1`.

Verification (actual runs on Docker Desktop 29.9.2, kind v0.33.0, kubectl v1.36.1):
- Compose: `docker compose up -d --build` -> all five containers healthy; `scripts\smoke-test.ps1` -> all 13 checks passed (401, public health, content creation, complaint, asynchronous RESTRICTED, duplicate 409, reporter appeal 403, appeal reaching round-2 `RESTRICTION_CONFIRMED`, second appeal 409, admin-only metrics).
- kind: `scripts\k8s-up.ps1` -> every pod Running/Ready, kafka-init Completed; `scripts\k8s-smoke.ps1` -> all 13 checks passed. `kafka-consumer-groups.sh --describe --group review-workers` showed the two review-service pods as separate members splitting the 3 partitions of `case.review-requested.v1`.
- Bugs found and fixed while verifying: shell scripts written with CRLF broke `kafka-init` (LF enforced), and parallel image builds raced on the Maven cache (`sharing=locked`).
- The Maven build and unit tests were not re-run for this stage (no Java source changed).
- Not verified: rolling update behaviour under load, pod/node failure recovery (next stage), any non-kind cluster, image scanning.
- Limits: one Kafka broker (replication factor 1, no HA), one MySQL instance with three logical databases, plaintext Kafka and HTTP, demo credentials. The kind smoke test skips the `reviews.completed` counter assertion because counters are per replica and the port-forward reaches one pod.

## Next stage
`test: add recovery and performance checks` - worker restart with queued messages, crash between DB commit and publish, `docs/results.md` with measured numbers only.
