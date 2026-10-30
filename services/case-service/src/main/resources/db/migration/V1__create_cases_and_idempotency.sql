CREATE TABLE review_cases (
    case_id      VARCHAR(36)   NOT NULL,
    content_id   VARCHAR(36)   NOT NULL,
    creator_id   VARCHAR(64)   NOT NULL,
    reporter_id  VARCHAR(64)   NOT NULL,
    reason       VARCHAR(1000) NOT NULL,
    status       VARCHAR(24)   NOT NULL,
    review_round INT           NOT NULL,
    created_at   TIMESTAMP(6)  NOT NULL,
    updated_at   TIMESTAMP(6)  NOT NULL,
    version      BIGINT        NOT NULL DEFAULT 0,
    PRIMARY KEY (case_id),
    -- one case per content item
    CONSTRAINT uq_review_cases_content UNIQUE (content_id),
    CONSTRAINT chk_review_cases_status CHECK (status IN
        ('PENDING_REVIEW', 'DISMISSED', 'RESTRICTED', 'APPEAL_PENDING', 'RESTORED', 'RESTRICTION_CONFIRMED')),
    CONSTRAINT chk_review_cases_round CHECK (review_round BETWEEN 1 AND 2)
) ENGINE = InnoDB;

CREATE TABLE idempotency_keys (
    id              BIGINT       NOT NULL AUTO_INCREMENT,
    actor_id        VARCHAR(64)  NOT NULL,
    operation       VARCHAR(32)  NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    request_hash    VARCHAR(64)  NOT NULL,
    response_status INT          NULL,
    response_body   TEXT         NULL,
    created_at      TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uq_idempotency_scope UNIQUE (actor_id, operation, idempotency_key)
) ENGINE = InnoDB;
