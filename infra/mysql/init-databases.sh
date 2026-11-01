#!/bin/bash
# Runs once, on first start of an empty data volume. One database and one user per service;
# each user can only reach its own database.
set -euo pipefail

create_db() {
  local db="$1" user="$2" password="$3"
  mysql -uroot -p"${MYSQL_ROOT_PASSWORD}" <<SQL
CREATE DATABASE IF NOT EXISTS \`${db}\` CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci;
CREATE USER IF NOT EXISTS '${user}'@'%' IDENTIFIED BY '${password}';
GRANT ALL PRIVILEGES ON \`${db}\`.* TO '${user}'@'%';
SQL
}

create_db case_db "${CASE_DB_USER}" "${CASE_DB_PASSWORD}"
create_db review_db "${REVIEW_DB_USER}" "${REVIEW_DB_PASSWORD}"
create_db content_db "${CONTENT_DB_USER}" "${CONTENT_DB_PASSWORD}"
