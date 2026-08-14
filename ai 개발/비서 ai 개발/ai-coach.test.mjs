import assert from "node:assert/strict";
import test from "node:test";
import {
  ActionType,
  AiCoachService,
  CompletionRiskLevel,
  GenerationType,
  InsightType,
  buildCoachContext,
  createProgressSnapshot,
  detectProgressInsights,
  selectTopInsight,
} from "./ai-coach.mjs";

function snapshot(overrides = {}) {
  return createProgressSnapshot({
    userId: 1,
    participationId: 10,
    challengeId: 100,
    challengeName: "매일 물 2L 마시기",
    now: "2026-08-07T08:00:00.000Z",
    today: "2026-08-07",
    currentDay: 4,
    totalDays: 7,
    requiredCompletionCount: 5,
    completedCount: 4,
    todayCompleted: true,
    currentStreak: 2,
    bestStreak: 4,
    groupCompletionRate: 0.4,
    groupCompletedToday: 2,
    groupMemberCount: 5,
    ...overrides,
  });
}

function typesOf(snapshotValue) {
  return detectProgressInsights(snapshotValue).map((insight) => insight.type);
}

test("오늘 인증 완료면 DEADLINE_APPROACHING이 발생하지 않는다", () => {
  const result = typesOf(snapshot({ certificationDeadline: "2026-08-07T09:00:00.000Z" }));

  assert.equal(result.includes(InsightType.DEADLINE_APPROACHING), false);
});

test("오늘 미인증이고 마감까지 60분이면 DEADLINE_APPROACHING이 발생한다", () => {
  const result = typesOf(
    snapshot({
      todayCompleted: false,
      certificationDeadline: "2026-08-07T09:00:00.000Z",
    }),
  );

  assert.equal(result.includes(InsightType.DEADLINE_APPROACHING), true);
});

test("currentStreak가 3일 이상이면 STREAK_CONTINUING이 발생한다", () => {
  const result = typesOf(snapshot({ currentStreak: 4 }));

  assert.equal(result.includes(InsightType.STREAK_CONTINUING), true);
});

test("currentStreak가 bestStreak를 넘으면 STREAK_RECORD가 발생한다", () => {
  const result = typesOf(snapshot({ currentStreak: 5, bestStreak: 4 }));

  assert.equal(result.includes(InsightType.STREAK_RECORD), true);
});

test("남은 일수와 필요한 인증 횟수가 같으면 COMPLETION_RISK HIGH다", () => {
  const result = snapshot({
    currentDay: 4,
    totalDays: 7,
    requiredCompletionCount: 5,
    completedCount: 2,
  });

  assert.equal(result.remainingAvailableDays, 3);
  assert.equal(result.remainingRequiredCount, 3);
  assert.equal(result.completionRiskLevel, CompletionRiskLevel.HIGH);
  assert.equal(typesOf(result).includes(InsightType.COMPLETION_RISK), true);
});

test("그룹 달성률이 80% 이상이면 GROUP_GOAL_NEAR가 발생한다", () => {
  const result = typesOf(snapshot({ groupCompletionRate: 0.82 }));

  assert.equal(result.includes(InsightType.GROUP_GOAL_NEAR), true);
});

test("여러 Insight가 있으면 DEADLINE_APPROACHING을 우선 선택한다", () => {
  const result = selectTopInsight(
    detectProgressInsights(
      snapshot({
        todayCompleted: false,
        certificationDeadline: "2026-08-07T09:00:00.000Z",
        currentStreak: 4,
      }),
    ),
  );

  assert.equal(result.type, InsightType.DEADLINE_APPROACHING);
});

test("AI API 실패 시 Template Fallback을 반환한다", async () => {
  const service = new AiCoachService({
    provider: {
      async generateCoachMessage() {
        throw new Error("network");
      },
    },
  });

  const result = await service.getCoachMessage({
    snapshot: snapshot({
      todayCompleted: false,
      certificationDeadline: "2026-08-07T09:00:00.000Z",
    }),
  });

  assert.equal(result.generationType, GenerationType.TEMPLATE);
  assert.equal(result.actionType, ActionType.OPEN_CERTIFICATION);
});

test("AI 응답 Schema 오류 시 서비스 실패 없이 Fallback한다", async () => {
  const service = new AiCoachService({
    provider: {
      async generateCoachMessage() {
        return {
          title: "잘못된 응답",
          message: "actionType이 잘못되었습니다.",
          actionType: "BAD_ACTION",
          actionLabel: "이동",
        };
      },
    },
  });

  const result = await service.getCoachMessage({
    snapshot: snapshot({ currentStreak: 4 }),
  });

  assert.equal(result.generationType, GenerationType.TEMPLATE);
  assert.equal(result.actionType, ActionType.OPEN_PROGRESS);
});

test("동일 Insight 반복 요청은 AI를 재호출하지 않는다", async () => {
  let calls = 0;
  const service = new AiCoachService({
    provider: {
      async generateCoachMessage() {
        calls += 1;
        return {
          title: "오늘 인증이 아직 남아 있어요",
          message: "마감까지 약 1시간 남았습니다.",
          actionType: ActionType.OPEN_CERTIFICATION,
          actionLabel: "인증하기",
        };
      },
    },
  });
  const input = {
    snapshot: snapshot({
      todayCompleted: false,
      certificationDeadline: "2026-08-07T09:00:00.000Z",
    }),
  };

  const first = await service.getCoachMessage(input);
  const second = await service.getCoachMessage(input);

  assert.equal(calls, 1);
  assert.equal(first.generationType, GenerationType.AI);
  assert.equal(second.cached, true);
});

test("AI Context에는 불필요한 사용자 식별정보를 넣지 않는다", () => {
  const progress = snapshot({ currentStreak: 4 });
  const context = buildCoachContext(progress, selectTopInsight(detectProgressInsights(progress)));
  const serialized = JSON.stringify(context);

  assert.equal(serialized.includes("userId"), false);
  assert.equal(serialized.includes("participationId"), false);
});
