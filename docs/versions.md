# Versions

| Component | Version | Notes |
|---|---|---|
| Java | 21 (Temurin 21.0.10 used locally) | Required by spec |
| Spring Boot | 3.5.16 | Latest 3.5.x on Maven Central at scaffold time (2026-10-30) |
| Maven | 3.9.16 via committed wrapper (`only-script` type, downloads on first run) | `mvnw`, `mvnw.cmd` |
| gRPC Java | 1.85.1 (BOM) | |
| protobuf-java / protoc | 4.36.2 | |
| protobuf-maven-plugin (ascopes) | 5.1.11 | Generates Java at build time into `target/` |
| Testcontainers | 1.21.4 | Declared in dependency management; used from later stages |
| Awaitility | 4.3.0 | Declared in dependency management; used from later stages |
| MySQL | 8.4 | Image tag to be pinned in the Compose stage |

Versions were checked against Maven Central metadata. No snapshot third-party dependencies are used.
Kafka, MySQL and Spring Kafka/Flyway/JPA versions will be added here as those stages land.
