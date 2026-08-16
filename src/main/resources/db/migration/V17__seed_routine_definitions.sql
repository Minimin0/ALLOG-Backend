-- Creating a group needs a routine_definition_id, but the table was never seeded and no endpoint
-- exposed its rows, so no client could create a group on a fresh deployment. These five are the
-- routine categories the onboarding interest list already commits to, keyed by the stable
-- routine_key added in V7 so a database that was hand-seeded during development is left alone
-- instead of failing the migration on the unique key.

INSERT INTO routine_definition (routine_key, name, description, created_at, updated_at)
SELECT 'HYDRATION', '물 마시기', '하루 목표량만큼 물을 마셔요.', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM routine_definition WHERE routine_key = 'HYDRATION');

INSERT INTO routine_definition (routine_key, name, description, created_at, updated_at)
SELECT 'EXERCISE', '운동하기', '정해진 시간만큼 몸을 움직여요.', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM routine_definition WHERE routine_key = 'EXERCISE');

INSERT INTO routine_definition (routine_key, name, description, created_at, updated_at)
SELECT 'MEAL', '식사 챙기기', '끼니를 거르지 않고 균형 잡힌 식사를 해요.', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM routine_definition WHERE routine_key = 'MEAL');

INSERT INTO routine_definition (routine_key, name, description, created_at, updated_at)
SELECT 'SLEEP', '수면 지키기', '정해진 시간에 잠들고 일어나요.', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM routine_definition WHERE routine_key = 'SLEEP');

INSERT INTO routine_definition (routine_key, name, description, created_at, updated_at)
SELECT 'SKINCARE', '피부 관리', '자기 전 피부 관리를 빠뜨리지 않아요.', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP
FROM DUAL WHERE NOT EXISTS (SELECT 1 FROM routine_definition WHERE routine_key = 'SKINCARE');
