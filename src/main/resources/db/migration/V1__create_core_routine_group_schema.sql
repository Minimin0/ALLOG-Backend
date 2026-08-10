CREATE TABLE users (
    id BIGINT NOT NULL AUTO_INCREMENT,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE routine_definition (
    id BIGINT NOT NULL AUTO_INCREMENT,
    name VARCHAR(100) NOT NULL,
    description VARCHAR(1000),
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE routine_group (
    id BIGINT NOT NULL AUTO_INCREMENT,
    routine_definition_id BIGINT NOT NULL,
    created_by_user_id BIGINT NOT NULL,
    name VARCHAR(100) NOT NULL,
    visibility VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    max_members INT NOT NULL,
    required_completion_count INT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_routine_group_definition
        FOREIGN KEY (routine_definition_id) REFERENCES routine_definition (id),
    CONSTRAINT fk_routine_group_creator
        FOREIGN KEY (created_by_user_id) REFERENCES users (id),
    CONSTRAINT chk_routine_group_max_members CHECK (max_members > 0),
    CONSTRAINT chk_routine_group_required_count CHECK (required_completion_count > 0)
);

CREATE TABLE routine_schedule (
    id BIGINT NOT NULL AUTO_INCREMENT,
    routine_group_id BIGINT NOT NULL,
    schedule_type VARCHAR(32) NOT NULL,
    start_date DATE NOT NULL,
    end_date DATE NOT NULL,
    deadline_time TIME NOT NULL,
    timezone VARCHAR(64) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_routine_schedule_group UNIQUE (routine_group_id),
    CONSTRAINT fk_routine_schedule_group
        FOREIGN KEY (routine_group_id) REFERENCES routine_group (id),
    CONSTRAINT chk_routine_schedule_dates CHECK (start_date <= end_date)
);

CREATE TABLE routine_schedule_day (
    schedule_id BIGINT NOT NULL,
    day_of_week VARCHAR(16) NOT NULL,
    CONSTRAINT uk_routine_schedule_day UNIQUE (schedule_id, day_of_week),
    CONSTRAINT fk_routine_schedule_day_schedule
        FOREIGN KEY (schedule_id) REFERENCES routine_schedule (id) ON DELETE CASCADE
);

CREATE TABLE group_member (
    id BIGINT NOT NULL AUTO_INCREMENT,
    routine_group_id BIGINT NOT NULL,
    user_id BIGINT NOT NULL,
    role VARCHAR(32) NOT NULL,
    status VARCHAR(32) NOT NULL,
    joined_at TIMESTAMP(6) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_group_member_group_user UNIQUE (routine_group_id, user_id),
    CONSTRAINT fk_group_member_group
        FOREIGN KEY (routine_group_id) REFERENCES routine_group (id),
    CONSTRAINT fk_group_member_user
        FOREIGN KEY (user_id) REFERENCES users (id)
);

CREATE INDEX idx_group_member_user_status ON group_member (user_id, status);
