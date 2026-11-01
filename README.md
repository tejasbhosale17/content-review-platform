# Event-Driven Content Review & Appeals Platform

Portfolio backend (Java 21, Spring Boot, Kafka, gRPC, MySQL) where complaints about content are reviewed asynchronously and restrictions can be appealed once.

Status: in progress - see [docs/progress.md](docs/progress.md). CI: GitHub Actions builds and tests every push and pull request (`.github/workflows/build.yml`).

## Build

```powershell
.\mvnw.cmd -B verify
```

Tests start real MySQL containers, so Docker must be running.

Service ports: case-service 8081, review-service 8082, content-service 8083 (HTTP) and 9090 (gRPC, set `GRPC_PORT` to change). review-service reaches content-service via `CONTENT_SERVICE_GRPC_TARGET` (default `localhost:9090`; use the service DNS name in Compose/Kubernetes).
Copy `.env.example` to `.env` for local settings; `.env` is git-ignored. The services refuse to start unless `DEMO_REPORTER_PASSWORD`, `DEMO_CREATOR_PASSWORD` and `DEMO_ADMIN_PASSWORD` are set (review-service needs only the admin one).

case-service looks up content over gRPC (`CONTENT_SERVICE_GRPC_TARGET`, default `localhost:9090`).

## Run locally

```powershell
Copy-Item .env.example .env        # then change the passwords
docker compose up -d --build       # MySQL, Kafka, three services (ports on 127.0.0.1)
.\scripts\smoke-test.ps1           # end-to-end check
docker compose down                # add -v to delete data
```

Kubernetes (kind, needs docker, kind and kubectl):

```powershell
.\scripts\k8s-up.ps1               # build images, create cluster, deploy to namespace content-review
.\scripts\k8s-smoke.ps1            # port-forward and run the smoke test
.\scripts\k8s-down.ps1             # delete the cluster
```

Limitations: one Kafka broker (replication factor 1) and one MySQL instance with three logical databases, so there is no high availability; Kafka and HTTP are plaintext; accounts are demo credentials.

## Accounts and observability

- HTTP Basic accounts: `reporter` (files complaints), `creator` (publishes content, appeals), `admin` (reads any case, `/internal/**`, `/actuator/info`, `/actuator/prometheus`). `/actuator/health` is public.
- Send `X-Correlation-Id` to trace a request; it is echoed back and carried through the Kafka events. Logs are JSON with `service`, `caseId`, `eventId`, `reviewRound` and `correlationId`.
- Metrics: `complaints.accepted`, `reviews.completed`, `kafka.consumer.processing`, `outbox.*`, `kafka.consumer.retries`, `kafka.consumer.dead_lettered`.

## API (current)

Case statuses: `PENDING_REVIEW`, `DISMISSED` (round-1 ALLOW), `RESTRICTED` (round-1 RESTRICT), `APPEAL_PENDING`, `RESTORED` and `RESTRICTION_CONFIRMED` (round-2 outcomes). Details: [docs/architecture.md](docs/architecture.md), [docs/events.md](docs/events.md), measurements: [docs/results.md](docs/results.md).

```powershell
# create content (creator is the authenticated user)
curl.exe -s -u creator:$env:DEMO_CREATOR_PASSWORD -X POST localhost:8083/api/contents -H "Content-Type: application/json" -d '{\"title\":\"Demo\",\"licenseValid\":false}'
# file a complaint
curl.exe -s -u reporter:$env:DEMO_REPORTER_PASSWORD -X POST localhost:8081/api/cases -H "Content-Type: application/json" -H "Idempotency-Key: abc-1" -d '{\"contentId\":\"<id>\",\"reason\":\"stolen footage\"}'
curl.exe -s -u reporter:$env:DEMO_REPORTER_PASSWORD localhost:8081/api/cases/<caseId>
# appeal a restricted case (content creator only)
curl.exe -s -u creator:$env:DEMO_CREATOR_PASSWORD -X POST localhost:8081/api/cases/<caseId>/appeals -H "Content-Type: application/json" -d '{\"statement\":\"I hold the license\"}'
```
