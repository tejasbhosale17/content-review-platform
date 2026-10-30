# Copilot instructions

- Java 21, Spring Boot 3.5.x, Maven (use `./mvnw`). Package prefix `com.tejas.contentreview`.
- `contracts` holds only event DTOs and protobuf; never share JPA entities or business logic between services.
- Each service owns its database and Flyway migrations; never query another service's database.
- Delivery is at-least-once with idempotent effects; never claim exactly-once across Kafka and MySQL.
- Do not commit secrets, `.env`, build output, or generated protobuf code.
- Do not invent benchmark numbers; record real results in `docs/results.md`.
