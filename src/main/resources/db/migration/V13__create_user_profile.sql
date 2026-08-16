CREATE TABLE user_profile (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    nickname VARCHAR(20) NOT NULL,
    gender VARCHAR(16) NULL,
    birth_date DATE NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_user_profile_user UNIQUE (user_id),
    CONSTRAINT fk_user_profile_user
        FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT chk_user_profile_gender
        CHECK (gender IS NULL OR gender IN ('FEMALE', 'MALE'))
);
