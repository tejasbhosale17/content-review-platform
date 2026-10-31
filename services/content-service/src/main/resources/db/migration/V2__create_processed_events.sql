CREATE TABLE processed_events (
    consumer_group VARCHAR(100) NOT NULL,
    event_id       VARCHAR(36)  NOT NULL,
    processed_at   DATETIME(6)  NOT NULL,
    PRIMARY KEY (consumer_group, event_id)
) ENGINE = InnoDB;
