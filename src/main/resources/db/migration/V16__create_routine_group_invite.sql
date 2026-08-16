CREATE TABLE routine_group_invite (
    id BIGINT NOT NULL AUTO_INCREMENT,
    routine_group_id BIGINT NOT NULL,
    created_by_user_id BIGINT NOT NULL,
    code VARCHAR(32) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_routine_group_invite_group UNIQUE (routine_group_id),
    CONSTRAINT uk_routine_group_invite_code UNIQUE (code),
    CONSTRAINT fk_routine_group_invite_group FOREIGN KEY (routine_group_id) REFERENCES routine_group (id) ON DELETE CASCADE,
    CONSTRAINT fk_routine_group_invite_creator FOREIGN KEY (created_by_user_id) REFERENCES users (id)
);
