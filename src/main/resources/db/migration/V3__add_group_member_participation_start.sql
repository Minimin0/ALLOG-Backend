ALTER TABLE group_member
    ADD COLUMN participation_started_at TIMESTAMP(6) NULL;

CREATE INDEX idx_group_member_group_participation_started_at
    ON group_member (routine_group_id, participation_started_at);
