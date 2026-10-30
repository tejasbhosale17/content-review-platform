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

## Next stage
`feat: implement Kafka review workflow` - Kafka topics/consumers, review-service decision persistence, case/content projections.
