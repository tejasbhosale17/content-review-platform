# Event-Driven Content Review & Appeals Platform

Portfolio backend (Java 21, Spring Boot, Kafka, gRPC, MySQL) where complaints about content are reviewed asynchronously and restrictions can be appealed once.

Status: in progress - see [docs/progress.md](docs/progress.md).

## Build

```powershell
.\mvnw.cmd -B verify
```

Tests start real MySQL containers, so Docker must be running.

Service ports: case-service 8081, review-service 8082, content-service 8083 (HTTP) and 9090 (gRPC, set `GRPC_PORT` to change). review-service reaches content-service via `CONTENT_SERVICE_GRPC_TARGET` (default `localhost:9090`; use the service DNS name in Compose/Kubernetes).
Copy `.env.example` to `.env` for local settings; `.env` is git-ignored. The services refuse to start unless `DEMO_REPORTER_PASSWORD`, `DEMO_CREATOR_PASSWORD` and `DEMO_ADMIN_PASSWORD` are set (review-service needs only the admin one).

case-service looks up content over gRPC (`CONTENT_SERVICE_GRPC_TARGET`, default `localhost:9090`).

## Accounts and observability

- HTTP Basic accounts: `reporter` (files complaints), `creator` (publishes content, appeals), `admin` (reads any case, `/internal/**`, `/actuator/info`, `/actuator/prometheus`). `/actuator/health` is public.
- Send `X-Correlation-Id` to trace a request; it is echoed back and carried through the Kafka events. Logs are JSON with `service`, `caseId`, `eventId`, `reviewRound` and `correlationId`.
- Metrics: `complaints.accepted`, `reviews.completed`, `kafka.consumer.processing`, `outbox.*`, `kafka.consumer.retries`, `kafka.consumer.dead_lettered`.

## API (current)

```powershell
# create content (creator is the authenticated user)
curl.exe -s -u creator:$env:DEMO_CREATOR_PASSWORD -X POST localhost:8083/api/contents -H "Content-Type: application/json" -d '{\"title\":\"Demo\",\"licenseValid\":false}'
# file a complaint
curl.exe -s -u reporter:$env:DEMO_REPORTER_PASSWORD -X POST localhost:8081/api/cases -H "Content-Type: application/json" -H "Idempotency-Key: abc-1" -d '{\"contentId\":\"<id>\",\"reason\":\"stolen footage\"}'
curl.exe -s -u reporter:$env:DEMO_REPORTER_PASSWORD localhost:8081/api/cases/<caseId>
# appeal a restricted case (content creator only)
curl.exe -s -u creator:$env:DEMO_CREATOR_PASSWORD -X POST localhost:8081/api/cases/<caseId>/appeals -H "Content-Type: application/json" -d '{\"statement\":\"I hold the license\"}'
```
