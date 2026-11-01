#!/bin/bash
# Idempotent explicit topic creation (the services also declare these topics; both agree on the settings).
set -euo pipefail
BOOTSTRAP="${KAFKA_BOOTSTRAP:-kafka:9092}"
TOPICS_BIN=/opt/kafka/bin/kafka-topics.sh

create() {
  "${TOPICS_BIN}" --bootstrap-server "${BOOTSTRAP}" --create --if-not-exists \
    --topic "$1" --partitions "$2" --replication-factor 1
}

create case.review-requested.v1 3
create review.completed.v1 3
create case.review-requested.v1.review-workers.dlt 1
create review.completed.v1.case-projection.dlt 1
create review.completed.v1.content-projection.dlt 1

"${TOPICS_BIN}" --bootstrap-server "${BOOTSTRAP}" --list
