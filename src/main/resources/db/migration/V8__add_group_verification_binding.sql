ALTER TABLE routine_group
    ADD COLUMN verification_template_key VARCHAR(64) NULL;

ALTER TABLE routine_group
    ADD COLUMN verification_criteria_reference VARCHAR(64) NULL;

ALTER TABLE routine_group
    ADD CONSTRAINT chk_routine_group_verification_binding
        CHECK (
            (verification_template_key IS NULL AND verification_criteria_reference IS NULL)
            OR
            (verification_template_key IS NOT NULL AND verification_criteria_reference IS NOT NULL)
        );
