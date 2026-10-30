CREATE TABLE contents (
    content_id           VARCHAR(36)  NOT NULL,
    creator_id           VARCHAR(64)  NOT NULL,
    title                VARCHAR(200) NOT NULL,
    license_valid        BOOLEAN      NOT NULL,
    license_note         VARCHAR(500) NULL,
    visibility           VARCHAR(16)  NOT NULL,
    applied_review_round INT          NOT NULL DEFAULT 0,
    created_at           TIMESTAMP(6) NOT NULL,
    updated_at           TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (content_id),
    CONSTRAINT chk_contents_visibility CHECK (visibility IN ('VISIBLE', 'RESTRICTED')),
    CONSTRAINT chk_contents_round CHECK (applied_review_round >= 0)
) ENGINE = InnoDB;
