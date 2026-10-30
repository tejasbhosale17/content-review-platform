# Event-Driven Content Review & Appeals Platform

Portfolio backend (Java 21, Spring Boot, Kafka, gRPC, MySQL) where complaints about content are reviewed asynchronously and restrictions can be appealed once.

Status: in progress - see [docs/progress.md](docs/progress.md).

## Build

```powershell
.\mvnw.cmd -B verify
```

Service ports: case-service 8081, review-service 8082, content-service 8083 (gRPC 9090 in a later stage).
Copy `.env.example` to `.env` for local settings; `.env` is git-ignored.
