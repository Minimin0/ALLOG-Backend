ALTER TABLE routine_definition
    ADD COLUMN routine_key VARCHAR(64) NULL;

ALTER TABLE routine_definition
    ADD CONSTRAINT uk_routine_definition_key UNIQUE (routine_key);
