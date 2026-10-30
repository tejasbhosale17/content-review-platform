# Event-Driven Content Review & Appeals Platform

Portfolio backend (Java 21, Spring Boot, Kafka, gRPC, MySQL) where complaints about content are reviewed asynchronously and restrictions can be appealed once.

Status: in progress - see [docs/progress.md](docs/progress.md).

## Build

```powershell
.\mvnw.cmd -B verify
```

Tests start real MySQL containers, so Docker must be running.

Service ports: case-service 8081, review-service 8082, content-service 8083 (gRPC 9090 in a later stage).
Copy `.env.example` to `.env` for local settings; `.env` is git-ignored.

## API (current)

```powershell
# create content
curl.exe -s -X POST localhost:8083/api/contents -H "Content-Type: application/json" -d '{\"creatorId\":\"creator-1\",\"title\":\"Demo\",\"licenseValid\":false}'
# file a complaint (X-Actor-Id is a temporary placeholder for authentication)
curl.exe -s -X POST localhost:8081/api/cases -H "Content-Type: application/json" -H "X-Actor-Id: reporter-1" -H "Idempotency-Key: abc-1" -d '{\"contentId\":\"<id>\",\"reason\":\"stolen footage\"}'
curl.exe -s localhost:8081/api/cases/<caseId>
```
