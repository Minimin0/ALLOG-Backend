CREATE TABLE verification (
    id BIGINT NOT NULL AUTO_INCREMENT,
    group_member_id BIGINT NOT NULL,
    routine_schedule_id BIGINT NOT NULL,
    scheduled_date DATE NOT NULL,
    status VARCHAR(32) NOT NULL,
    submitted_at TIMESTAMP(6),
    approved_at TIMESTAMP(6),
    invalidated_at TIMESTAMP(6),
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_verification_member_schedule_date
        UNIQUE (group_member_id, routine_schedule_id, scheduled_date),
    CONSTRAINT fk_verification_group_member
        FOREIGN KEY (group_member_id) REFERENCES group_member (id),
    CONSTRAINT fk_verification_schedule
        FOREIGN KEY (routine_schedule_id) REFERENCES routine_schedule (id)
);

CREATE INDEX idx_verification_member_status_date
    ON verification (group_member_id, status, scheduled_date);

CREATE INDEX idx_verification_schedule_date_status
    ON verification (routine_schedule_id, scheduled_date, status);
