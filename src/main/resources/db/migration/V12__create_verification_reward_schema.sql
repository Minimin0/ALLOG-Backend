CREATE TABLE verification_reward (
    id BIGINT NOT NULL AUTO_INCREMENT,
    verification_id BIGINT NOT NULL,
    points INT NOT NULL,
    granted_at TIMESTAMP(6) NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_verification_reward_verification UNIQUE (verification_id),
    CONSTRAINT fk_verification_reward_verification
        FOREIGN KEY (verification_id) REFERENCES verification (id),
    CONSTRAINT chk_verification_reward_points CHECK (points > 0)
);
