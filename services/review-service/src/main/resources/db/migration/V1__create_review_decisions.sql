CREATE TABLE review_decisions (
    id              BIGINT        NOT NULL AUTO_INCREMENT,
    case_id         VARCHAR(36)   NOT NULL,
    review_round    INT           NOT NULL,
    content_id      VARCHAR(36)   NOT NULL,
    decision        VARCHAR(16)   NOT NULL,
    policy_version  VARCHAR(32)   NOT NULL,
    rationale       VARCHAR(500)  NOT NULL,
    result_event_id VARCHAR(36)   NOT NULL,
    correlation_id  VARCHAR(64)   NOT NULL,
    created_at      TIMESTAMP(6)  NOT NULL,
    PRIMARY KEY (id),
    -- exactly one decision per case and round
    CONSTRAINT uq_review_decisions_case_round UNIQUE (case_id, review_round),
    CONSTRAINT uq_review_decisions_result_event UNIQUE (result_event_id),
    CONSTRAINT chk_review_decisions_decision CHECK (decision IN ('ALLOW', 'RESTRICT')),
    CONSTRAINT chk_review_decisions_round CHECK (review_round BETWEEN 1 AND 2)
) ENGINE = InnoDB;
