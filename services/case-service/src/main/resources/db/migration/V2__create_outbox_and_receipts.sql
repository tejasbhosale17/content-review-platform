CREATE TABLE processed_events (
    consumer_group VARCHAR(100) NOT NULL,
    event_id       VARCHAR(36)  NOT NULL,
    processed_at   DATETIME(6)  NOT NULL,
    PRIMARY KEY (consumer_group, event_id)
) ENGINE = InnoDB;

CREATE TABLE outbox_events (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    event_id      VARCHAR(36)  NOT NULL,
    topic         VARCHAR(200) NOT NULL,
    message_key   VARCHAR(100) NOT NULL,
    payload       TEXT         NOT NULL,
    created_at    DATETIME(6)  NOT NULL,
    published_at  DATETIME(6)  NULL,
    lease_owner   VARCHAR(36)  NULL,
    lease_until   DATETIME(6)  NULL,
    attempts      INT          NOT NULL DEFAULT 0,
    last_error    VARCHAR(500) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_outbox_event_id UNIQUE (event_id),
    INDEX idx_outbox_pending (published_at, id)
) ENGINE = InnoDB;
