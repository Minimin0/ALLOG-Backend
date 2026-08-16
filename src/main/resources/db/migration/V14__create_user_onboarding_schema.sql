CREATE TABLE user_onboarding (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NOT NULL,
    coach_style VARCHAR(32) NOT NULL,
    average_sleep_hours DECIMAL(3,1) NOT NULL,
    exercise_days_per_week TINYINT NOT NULL,
    meals_per_day TINYINT NOT NULL,
    preferred_group_duration_days SMALLINT NOT NULL,
    created_at TIMESTAMP(6) NOT NULL,
    updated_at TIMESTAMP(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_user_onboarding_user UNIQUE (user_id),
    CONSTRAINT fk_user_onboarding_user
        FOREIGN KEY (user_id) REFERENCES users (id),
    CONSTRAINT chk_user_onboarding_coach_style
        CHECK (coach_style IN ('SUPPORTIVE', 'PRESSURING', 'FACT_BASED', 'HUMOROUS')),
    CONSTRAINT chk_user_onboarding_sleep
        CHECK (average_sleep_hours BETWEEN 0 AND 24),
    CONSTRAINT chk_user_onboarding_exercise
        CHECK (exercise_days_per_week BETWEEN 0 AND 7),
    CONSTRAINT chk_user_onboarding_meals
        CHECK (meals_per_day BETWEEN 0 AND 10),
    CONSTRAINT chk_user_onboarding_duration
        CHECK (preferred_group_duration_days IN (7, 14, 30))
);

-- ElementCollection value table: no synthetic id, the pair is the identity.
-- ON DELETE CASCADE matches routine_schedule_day, the existing value-table precedent.
CREATE TABLE user_onboarding_interest (
    onboarding_id BIGINT NOT NULL,
    interest VARCHAR(32) NOT NULL,
    PRIMARY KEY (onboarding_id, interest),
    CONSTRAINT fk_user_onboarding_interest_onboarding
        FOREIGN KEY (onboarding_id) REFERENCES user_onboarding (id) ON DELETE CASCADE,
    CONSTRAINT chk_user_onboarding_interest
        CHECK (interest IN ('HYDRATION', 'EXERCISE', 'MEAL', 'SLEEP', 'SKINCARE'))
);
