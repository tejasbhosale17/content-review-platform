# Events

All services exchange JSON events over Kafka. The Java records live in the `contracts` module (`EventEnvelope`, `ReviewRequestedPayload`, `ReviewCompletedPayload`, `Topics`); the gRPC contract is `contracts/src/main/proto/contentmetadata.proto`.

## Topics

| Topic | Partitions | Key | Producer | Consumer groups |
|---|---|---|---|---|
| `case.review-requested.v1` | 3 | `caseId` | case-service (via outbox) | `review-workers` (review-service) |
| `review.completed.v1` | 3 | `caseId` | review-service (via outbox) | `case-projection` (case-service), `content-projection` (content-service) |
| `case.review-requested.v1.review-workers.dlt` | 1 | original key | `review-workers` error handler | replay endpoint |
| `review.completed.v1.case-projection.dlt` | 1 | original key | `case-projection` error handler | replay endpoint |
| `review.completed.v1.content-projection.dlt` | 1 | original key | `content-projection` error handler | replay endpoint |

Dead-letter topics are per consumer group so groups that share a topic never mix their failures. Replication factor is 1 (single local broker).

## Envelope

Every event has the same envelope (`EventEnvelope`):

| Field | Meaning |
|---|---|
| `eventId` | UUID created once when the event is written to the outbox and reused on every retry or re-send. Consumers deduplicate on it. |
| `eventType` | `ReviewRequested` or `ReviewCompleted` |
| `schemaVersion` | `1` |
| `occurredAt` | ISO-8601 instant |
| `caseId`, `contentId` | Business identifiers |
| `reviewRound` | `1` for the original complaint, `2` for the appeal |
| `correlationId` | From `X-Correlation-Id` (or generated); copied to logs |

Payloads:
- `ReviewRequested`: `{ "reason": string, "appeal": boolean }`
- `ReviewCompleted`: `{ "decision": "ALLOW" | "RESTRICT", "policyVersion": string, "rationale": string }`

Example `ReviewCompleted`:

```json
{
  "eventId": "6f1c3b0e-3a4e-4a39-9a55-0c1f4f7f1d11",
  "eventType": "ReviewCompleted",
  "schemaVersion": 1,
  "occurredAt": "2026-10-31T12:00:00Z",
  "caseId": "c1", "contentId": "k1", "reviewRound": 1,
  "correlationId": "demo-1",
  "payload": { "decision": "RESTRICT", "policyVersion": "demo-1", "rationale": "..." }
}
```

## Delivery and ordering assumptions

- Delivery is at-least-once. Producers write the event to a MySQL outbox table in the same transaction as the business change; a relay publishes pending rows and marks them sent afterwards, so a crash between send and mark causes a re-send of the same `eventId`. Nothing in this repo claims exactly-once across Kafka and MySQL.
- Effects are idempotent. Each consumer records `(consumer_group, event_id)` in `processed_events` in the same transaction as its business effect, so a duplicate delivery commits nothing new.
- Ordering is per key only (`caseId`). A single relay publishes in outbox id order; with several relays or after retries, order is not guaranteed. Consumers therefore do not rely on it:
  - Results carry `reviewRound`; a round-1 result arriving after the appeal started or finished is ignored, and content visibility is only overwritten by a higher or equal applied round.
  - A result for an unknown case is ignored without blocking the partition.
- Review decisions are fixed per (case, round) and do not change if the content changes later.

## Failure handling and dead letters

- Consumers retry with backoff (`kafka.consumer.retries` metric); then the record, with original payload and `DLT_*` headers, goes to the group's dead-letter topic (`kafka.consumer.dead_lettered`).
- Malformed JSON is dead-lettered immediately and does not block later records.
- Replay (ADMIN only): `POST /internal/dlt/replay?max=100` on the owning service re-publishes dead-lettered records to the original topic for that group. Replay is safe because consumers deduplicate on `eventId`. Fix the cause (for example bring the dependency back) before replaying.

```powershell
curl.exe -s -u admin:$env:DEMO_ADMIN_PASSWORD -X POST "localhost:8082/internal/dlt/replay?max=100"
```

## gRPC: `ContentMetadata.GetContent`

Used by case-service (validate content at complaint time) and review-service (license data for the decision). `NOT_FOUND` for unknown content, `INVALID_ARGUMENT` for a blank id; callers use a short deadline and map `UNAVAILABLE`/`DEADLINE_EXCEEDED` to a temporary failure (HTTP 503 in case-service, retry then dead-letter in review-service). The port (9090) has no authentication; it is for the internal network only.
