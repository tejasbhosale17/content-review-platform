CREATE TABLE appeals (
    appeal_id    VARCHAR(36)   NOT NULL,
    case_id      VARCHAR(36)   NOT NULL,
    content_id   VARCHAR(36)   NOT NULL,
    appellant_id VARCHAR(64)   NOT NULL,
    statement    VARCHAR(1000) NOT NULL,
    created_at   TIMESTAMP(6)  NOT NULL,
    PRIMARY KEY (appeal_id),
    -- at most one appeal per case, enforced by the database
    CONSTRAINT uq_appeals_case UNIQUE (case_id),
    CONSTRAINT fk_appeals_case FOREIGN KEY (case_id) REFERENCES review_cases (case_id)
) ENGINE = InnoDB;
