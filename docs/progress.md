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

## Next stage
`feat: add content and complaint persistence` - Flyway migrations, MySQL via Testcontainers, REST APIs, idempotency keys.
