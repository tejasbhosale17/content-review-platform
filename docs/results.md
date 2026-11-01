# Results

All numbers below come from real runs on one developer laptop. Nothing here is extrapolated. Anything not listed was not run.

## Environment

- Host: AMD Ryzen 7 7840HS, 16 logical CPUs, 15.3 GB RAM, Windows, Docker Desktop (VM sees 16 CPUs and about 7.9 GB).
- Deployment: `docker compose up -d --build` (single-node KRaft Kafka 3.9.1, one MySQL 8.4.13 instance, three services). No container CPU or memory limits were set in Compose. The load generator ran on the same machine, so it competes with the system under test.
- Workers: 1 review-service replica (Compose). Partitions: 3 for `case.review-requested.v1` and `review.completed.v1`. Kafka replication factor 1.
- Services run with `-XX:MaxRAMPercentage=75` and default Spring/Tomcat settings.

## Workload

`load-tests/load-test.ps1` creates N unique contents (half with an invalid license, half valid), then files one complaint per content as the `reporter` user, then polls each case until it leaves `PENDING_REVIEW`.

- HTTP latency: time for `POST /api/cases` (accepted requests only). Each request includes a gRPC content lookup, the database commit and the outbox insert.
- End-to-end latency: from sending the complaint request until a 100 ms poll observes a final status. It includes outbox relay delay, Kafka hops through review-service and back, the projection commit, and polling resolution (about 100 ms plus the time to poll all pending cases). With many cases in flight it also contains queueing time, so it grows with dataset size.
- Backlog: accepted cases still `PENDING_REVIEW` when the 120 s drain timeout expired.

## Throughput and latency runs

Commands (stack freshly started, runs executed one after another in this order):

```powershell
.\load-tests\load-test.ps1 -Count 50  -Concurrency 4
.\load-tests\load-test.ps1 -Count 200 -Concurrency 4
.\load-tests\load-test.ps1 -Count 500 -Concurrency 8
```

| Run | Dataset | Client concurrency | Accepted / failed / completed / backlog | Submission rate | HTTP p50 / p95 / p99 / max (ms) | End-to-end p50 / p95 / p99 / max (ms) |
|-----|---------|--------------------|------------------------------------------|-----------------|---------------------------------|---------------------------------------|
| 1 (cold stack) | 50 | 4 | 50 / 0 / 50 / 0 | 12.1 req/s | 96.3 / 2979.4 / 2980.2 / 2980.2 | 2519.9 / 4587.8 / 6056.7 / 6056.7 |
| 2 | 200 | 4 | 200 / 0 / 200 / 0 | 30.7 req/s | 86.5 / 111.3 / 2126.4 / 2127.5 | 3339.1 / 5639.9 / 6686.7 / 6845 |
| 3 | 500 | 8 | 500 / 0 / 500 / 0 | 83.1 req/s | 93.3 / 113.8 / 126.9 / 145.4 | 7292 / 8986.5 / 9523.6 / 9856.2 |

Every run ended with half `RESTRICTED` and half `DISMISSED`, with zero failures and zero backlog.

Observations (from the numbers above, not tested further):
- The 2-3 s HTTP outliers in runs 1 and 2 look like warm-up of the first requests (run 3 on the already warm stack had none), but this was not isolated by a dedicated experiment.
- In run 3 the complaints are accepted faster than the single review worker drains them, so end-to-end latency is dominated by queueing. The reviews of 500 cases finished within roughly 10 s of the first request.
- The workload is small and the setup is not tuned. These numbers say nothing about production capacity, multiple brokers or multiple review-service replicas.

## Recovery checks

`scripts/recovery-test.ps1 -Count 10` against Compose (stopping and starting containers with `docker compose stop/start`):

| Scenario | Result |
|----------|--------|
| review-service stopped, 10 complaints accepted | All 10 stayed `PENDING_REVIEW` while it was down. After restart all 10 reached a final status in 10.1 s. |
| Kafka stopped, 10 complaints accepted | All 10 were accepted (events waited in the outbox). After Kafka restarted all 10 reached a final status in 5.3 s. |

The script only checks that no case stays pending. Exactly-once effects are covered by the integration tests (idempotent consumers, `processed_events`), not by this script.

## Automated test suite

`.\mvnw.cmd -B verify` (real MySQL 8.4 and Kafka Testcontainers): BUILD SUCCESS - contracts 6, case-service 39, review-service 27, content-service 19 tests, 0 failures.

## Not run

- Throughput with more than one review-service replica, more partitions, or a larger dataset than 500 pairs.
- Performance on the kind cluster (kind was only used for the smoke test).
- Sustained or soak tests, and resource-limited runs.
- Recovery of MySQL (stopping the database) and of case-service/content-service restarts under load.
- The optional MirrorMaker 2 replication profile.
