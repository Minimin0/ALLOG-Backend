ALTER TABLE verification
    ADD COLUMN reviewed_by BIGINT NULL;

ALTER TABLE verification
    ADD COLUMN reviewed_at TIMESTAMP(6) NULL;

ALTER TABLE verification
    ADD CONSTRAINT chk_verification_review_audit
        CHECK (
            (reviewed_by IS NULL AND reviewed_at IS NULL)
            OR
            (reviewed_by IS NOT NULL AND reviewed_at IS NOT NULL)
        );
