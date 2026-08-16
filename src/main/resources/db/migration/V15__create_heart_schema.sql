CREATE TABLE heart_wallet (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    balance INT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_heart_wallet_user UNIQUE (user_id),
    CONSTRAINT fk_heart_wallet_user
        FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT chk_heart_wallet_balance CHECK (balance >= 0)
);

-- Append-only. source_id is polymorphic by type - a user_profile id for a grant, a group_member id
-- for a spend or refund - so it carries no foreign key; provenance is (type, source_id), which is
-- also what makes every operation exactly-once.
CREATE TABLE heart_ledger_entry (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    type VARCHAR(32) NOT NULL,
    amount INT NOT NULL,
    source_id BIGINT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_heart_ledger_user
        FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT uk_heart_ledger_type_source UNIQUE (type, source_id),
    CONSTRAINT chk_heart_ledger_amount CHECK (amount <> 0),
    CONSTRAINT chk_heart_ledger_type
        CHECK (type IN ('INITIAL_GRANT', 'GROUP_JOIN_SPEND', 'GROUP_JOIN_REFUND')),
    CONSTRAINT chk_heart_ledger_direction
        CHECK (
            (type = 'INITIAL_GRANT' AND amount > 0)
            OR
            (type = 'GROUP_JOIN_SPEND' AND amount < 0)
            OR
            (type = 'GROUP_JOIN_REFUND' AND amount > 0)
        )
);

CREATE INDEX idx_heart_ledger_user_created
    ON heart_ledger_entry (user_id, created_at);

-- Backfill: everyone who already finished onboarding gets the same 3 hearts a new member gets.
-- Written as a wallet row plus its matching ledger entry, never as a bare balance, so the history
-- explains the balance. The ledger's (type, source_id) key is what stops the runtime grant from
-- paying these profiles a second time.
INSERT INTO heart_wallet (user_id, balance, created_at, updated_at)
SELECT profile.user_id, 3, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)
  FROM user_profile profile;

INSERT INTO heart_ledger_entry (user_id, type, amount, source_id, created_at)
SELECT profile.user_id, 'INITIAL_GRANT', 3, profile.id, CURRENT_TIMESTAMP(6)
  FROM user_profile profile;
