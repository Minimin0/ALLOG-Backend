const DAY_MS = 24 * 60 * 60 * 1000;

export const InsightType = Object.freeze({
  TODAY_NOT_COMPLETED: "TODAY_NOT_COMPLETED",
  DEADLINE_APPROACHING: "DEADLINE_APPROACHING",
  STREAK_CONTINUING: "STREAK_CONTINUING",
  STREAK_RECORD: "STREAK_RECORD",
  COMPLETION_RISK: "COMPLETION_RISK",
  GROUP_GOAL_NEAR: "GROUP_GOAL_NEAR",
  IMPROVED_FROM_PREVIOUS: "IMPROVED_FROM_PREVIOUS",
});

export const CompletionRiskLevel = Object.freeze({
  LOW: "LOW",
  MEDIUM: "MEDIUM",
  HIGH: "HIGH",
});

export const ActionType = Object.freeze({
  OPEN_CERTIFICATION: "OPEN_CERTIFICATION",
  OPEN_GROUP: "OPEN_GROUP",
  OPEN_PROGRESS: "OPEN_PROGRESS",
  NONE: "NONE",
});

export const GenerationType = Object.freeze({
  AI: "AI",
  TEMPLATE: "TEMPLATE",
});

export const DEFAULT_POLICY = Object.freeze({
  deadlineApproachingMinutes: 120,
  streakContinuingDays: 3,
  completionRiskMediumRatio: 0.7,
  groupGoalNearRate: 0.8,
  priority: Object.freeze({
    [InsightType.DEADLINE_APPROACHING]: 1,
    [InsightType.COMPLETION_RISK]: 2,
    [InsightType.GROUP_GOAL_NEAR]: 3,
    [InsightType.STREAK_RECORD]: 4,
    [InsightType.STREAK_CONTINUING]: 5,
    [InsightType.IMPROVED_FROM_PREVIOUS]: 6,
    [InsightType.TODAY_NOT_COMPLETED]: 7,
  }),
  now: () => new Date(),
});

export const COACH_RESPONSE_SCHEMA = Object.freeze({
  type: "object",
  additionalProperties: false,
  required: ["title", "message", "actionType", "actionLabel"],
  properties: {
    title: { type: "string" },
    message: { type: "string" },
    actionType: {
      type: "string",
      enum: Object.values(ActionType),
    },
    actionLabel: { type: "string" },
  },
});

export const COACH_SYSTEM_PROMPT = [
  "너는 ALLOG AI Coach 메시지 작성자다.",
  "Backend가 판단한 insight와 제공된 fact만 사용한다.",
  "숫자를 새로 계산하거나 제공되지 않은 사실을 만들지 않는다.",
  "한국어 1~2문장으로 짧고 명확하게 작성한다.",
  "사용자를 비난하거나 의학적 조언, 진단, 강압적 표현을 하지 않는다.",
  "반드시 JSON schema에 맞춰 title, message, actionType, actionLabel만 반환한다.",
].join(" ");

export function createProgressSnapshot(input, policy = DEFAULT_POLICY) {
  const p = withDefaultPolicy(policy);
  const challenge = input.challenge ?? {};
  const participation = input.participation ?? {};
  const group = input.group ?? {};
  const now = parseInstant(input.snapshotGeneratedAt ?? input.now ?? p.now());
  const today = toDateOnly(input.today ?? now);
  const totalDays = positiveInt(input.totalDays ?? challenge.totalDays, "totalDays");
  const startedOn = input.startedOn ?? participation.startedOn ?? participation.startDate;
  const currentDay = clampInt(
    input.currentDay ?? (startedOn ? daysBetweenInclusive(startedOn, today) : 1),
    1,
    totalDays,
  );
  const completionDates = new Set(
    (input.certifications ?? [])
      .filter(isCompletedCertification)
      .map(certificationDateOf)
      .filter(Boolean)
      .map(toDateOnly),
  );
  const completedCount = nonNegativeInt(input.completedCount ?? completionDates.size, "completedCount");
  const requiredCompletionCount = positiveInt(
    input.requiredCompletionCount ?? challenge.requiredCompletionCount ?? totalDays,
    "requiredCompletionCount",
  );
  const todayCompleted = input.todayCompleted ?? completionDates.has(today);
  const currentStreak = nonNegativeInt(
    input.currentStreak ?? calculateCurrentStreak(completionDates, today, todayCompleted),
    "currentStreak",
  );
  const bestStreak = nonNegativeInt(input.bestStreak ?? calculateBestStreak(completionDates), "bestStreak");
  const remainingDays = Math.max(totalDays - currentDay, 0);
  const deadline = input.certificationDeadline ?? input.deadline;
  const minutesUntilDeadline = deadline
    ? Math.max(0, Math.ceil((parseInstant(deadline).getTime() - now.getTime()) / 60000))
    : null;
  const groupCompletedToday = nullableInt(input.groupCompletedToday ?? group.completedToday);
  const groupMemberCount = nullableInt(input.groupMemberCount ?? group.memberCount);
  const snapshot = {
    userId: required(input.userId ?? participation.userId, "userId"),
    participationId: required(input.participationId ?? participation.id, "participationId"),
    challengeId: required(input.challengeId ?? challenge.id, "challengeId"),
    challengeName: input.challengeName ?? challenge.name ?? null,
    currentDay,
    totalDays,
    remainingDays,
    todayCompleted,
    completedCount,
    requiredCompletionCount,
    personalCompletionRate: normalizeRate(input.personalCompletionRate ?? completedCount / requiredCompletionCount),
    currentStreak,
    bestStreak,
    missedDays:
      input.missedDays ??
      calculateMissedDays(startedOn ?? addDays(today, -(currentDay - 1)), currentDay, completionDates, today),
    groupCompletionRate: normalizeNullableRate(
      input.groupCompletionRate ??
        group.completionRate ??
        (groupCompletedToday !== null && groupMemberCount ? groupCompletedToday / groupMemberCount : null),
    ),
    groupCompletedToday,
    groupMemberCount,
    certificationDeadline: deadline ? parseInstant(deadline).toISOString() : null,
    minutesUntilDeadline,
    previousChallengeCompletionRate: normalizeNullableRate(input.previousChallengeCompletionRate),
    snapshotGeneratedAt: now.toISOString(),
  };

  return {
    ...snapshot,
    ...calculateCompletionRisk(snapshot, p),
  };
}

export function detectProgressInsights(snapshot, policy = DEFAULT_POLICY) {
  const p = withDefaultPolicy(policy);
  const insights = [];

  if (isDeadlineApproaching(snapshot, p)) {
    insights.push(insight(InsightType.DEADLINE_APPROACHING, snapshot, p, {
      minutesRemaining: snapshot.minutesUntilDeadline,
    }));
  }

  if (isCompletionAtRisk(snapshot)) {
    insights.push(insight(InsightType.COMPLETION_RISK, snapshot, p, {
      riskLevel: snapshot.completionRiskLevel,
      remainingRequiredCount: snapshot.remainingRequiredCount,
      remainingAvailableDays: snapshot.remainingAvailableDays,
    }));
  }

  if (isGroupGoalNear(snapshot, p)) {
    insights.push(insight(InsightType.GROUP_GOAL_NEAR, snapshot, p, {
      groupCompletionRate: snapshot.groupCompletionRate,
      groupCompletedToday: snapshot.groupCompletedToday,
      groupMemberCount: snapshot.groupMemberCount,
    }));
  }

  if (isNewStreakRecord(snapshot)) {
    insights.push(insight(InsightType.STREAK_RECORD, snapshot, p, {
      currentStreak: snapshot.currentStreak,
      bestStreak: snapshot.bestStreak,
    }));
  }

  if (isStreakContinuing(snapshot, p)) {
    insights.push(insight(InsightType.STREAK_CONTINUING, snapshot, p, {
      currentStreak: snapshot.currentStreak,
    }));
  }

  if (isImprovedFromPrevious(snapshot)) {
    insights.push(insight(InsightType.IMPROVED_FROM_PREVIOUS, snapshot, p, {
      previousChallengeCompletionRate: snapshot.previousChallengeCompletionRate,
      personalCompletionRate: snapshot.personalCompletionRate,
    }));
  }

  if (!snapshot.todayCompleted) {
    insights.push(insight(InsightType.TODAY_NOT_COMPLETED, snapshot, p, {}));
  }

  return insights;
}

export function selectTopInsight(insights) {
  return [...insights].sort((a, b) => a.priority - b.priority)[0] ?? null;
}

export function buildCoachContext(snapshot, insight) {
  return {
    challenge: {
      name: snapshot.challengeName,
      currentDay: snapshot.currentDay,
      totalDays: snapshot.totalDays,
    },
    progress: {
      todayCompleted: snapshot.todayCompleted,
      completionRate: snapshot.personalCompletionRate,
      currentStreak: snapshot.currentStreak,
      riskLevel: snapshot.completionRiskLevel,
      remainingRequiredCount: snapshot.remainingRequiredCount,
      remainingAvailableDays: snapshot.remainingAvailableDays,
    },
    group: {
      completionRate: snapshot.groupCompletionRate,
      completedToday: snapshot.groupCompletedToday,
      memberCount: snapshot.groupMemberCount,
    },
    deadline: {
      minutesRemaining: snapshot.minutesUntilDeadline,
    },
    insight: insight
      ? {
          type: insight.type,
          priority: insight.priority,
          context: insight.context,
        }
      : {
          type: null,
          priority: null,
          context: {},
        },
  };
}

export class AiCoachService {
  constructor({ provider = new OpenAiCoachProvider(), policy = DEFAULT_POLICY, cache = new Map(), logger = null } = {}) {
    this.provider = provider;
    this.policy = withDefaultPolicy(policy);
    this.cache = cache;
    this.logger = logger;
  }

  async getCoachMessage(input) {
    const snapshot = input.snapshot ?? createProgressSnapshot(input, this.policy);
    const insights = detectProgressInsights(snapshot, this.policy);
    const topInsight = selectTopInsight(insights);
    const context = buildCoachContext(snapshot, topInsight);
    const cacheKey = coachCacheKey(snapshot, context);
    const cached = this.cache.get(cacheKey);

    if (cached) {
      return { ...cached, cached: true };
    }

    let result;
    const startedAt = Date.now();

    try {
      if (!topInsight) {
        throw new Error("No insight to send to AI");
      }
      const aiResponse = validateCoachResponse(await this.provider.generateCoachMessage(context));
      result = {
        ...aiResponse,
        insightType: topInsight.type,
        generationType: GenerationType.AI,
        detectedAt: topInsight.detectedAt,
        latencyMs: Date.now() - startedAt,
      };
      this.log({
        event: "ai_coach_generated",
        insightType: topInsight.type,
        generationType: GenerationType.AI,
        latencyMs: result.latencyMs,
      });
    } catch (error) {
      result = {
        ...templateCoachResponse(context),
        insightType: topInsight?.type ?? null,
        generationType: GenerationType.TEMPLATE,
        detectedAt: topInsight?.detectedAt ?? snapshot.snapshotGeneratedAt,
        latencyMs: Date.now() - startedAt,
      };
      this.log({
        event: "ai_coach_fallback",
        insightType: result.insightType,
        generationType: GenerationType.TEMPLATE,
        latencyMs: result.latencyMs,
        reason: error.message,
      });
    }

    this.cache.set(cacheKey, result);
    return { ...result, cached: false };
  }

  log(event) {
    if (!this.logger) return;
    if (typeof this.logger === "function") this.logger(event);
    else if (this.logger.info) this.logger.info(event);
  }
}

export class OpenAiCoachProvider {
  constructor({
    apiKey = process.env.OPENAI_API_KEY,
    model = process.env.AI_COACH_MODEL ?? process.env.OPENAI_MODEL,
    endpoint = "https://api.openai.com/v1/responses",
    fetchImpl = globalThis.fetch,
  } = {}) {
    this.apiKey = apiKey;
    this.model = model;
    this.endpoint = endpoint;
    this.fetch = fetchImpl;
  }

  async generateCoachMessage(context) {
    if (!this.apiKey) throw new Error("OPENAI_API_KEY is not set");
    if (!this.model) throw new Error("AI_COACH_MODEL is not set");
    if (!this.fetch) throw new Error("fetch is not available");

    const response = await this.fetch(this.endpoint, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${this.apiKey}`,
        "Content-Type": "application/json",
      },
      body: JSON.stringify({
        model: this.model,
        input: [
          { role: "system", content: COACH_SYSTEM_PROMPT },
          { role: "user", content: JSON.stringify(context) },
        ],
        max_output_tokens: 180,
        text: {
          format: {
            type: "json_schema",
            name: "ai_coach_response",
            strict: true,
            schema: COACH_RESPONSE_SCHEMA,
          },
        },
      }),
    });

    if (!response.ok) {
      throw new Error(`OpenAI request failed: ${response.status}`);
    }

    return parseCoachResponse(extractOutputText(await response.json()));
  }
}

export function templateCoachResponse(context) {
  const type = context.insight?.type;
  const response = (TEMPLATES[type] ?? TEMPLATES.DEFAULT)(context);
  return validateCoachResponse(response);
}

export function validateCoachResponse(value) {
  const response = typeof value === "string" ? JSON.parse(value) : value;
  if (!response || typeof response !== "object") {
    throw new Error("AI coach response must be an object");
  }

  const title = requireText(response.title, "title", 80);
  const message = requireText(response.message, "message", 220);
  const actionType = requireEnum(response.actionType, Object.values(ActionType), "actionType");
  const actionLabel = requireText(response.actionLabel, "actionLabel", 30, true);

  return { title, message, actionType, actionLabel };
}

export function parseCoachResponse(text) {
  return validateCoachResponse(JSON.parse(text));
}

export function extractOutputText(responseBody) {
  if (typeof responseBody.output_text === "string") return responseBody.output_text;

  return (responseBody.output ?? [])
    .flatMap((item) => item.content ?? [])
    .map((content) => content.text ?? content.output_text ?? "")
    .join("");
}

export function calculateCompletionRisk(snapshot, policy = DEFAULT_POLICY) {
  const remainingRequiredCount = Math.max(snapshot.requiredCompletionCount - snapshot.completedCount, 0);
  const remainingAvailableDays = Math.max(snapshot.totalDays - snapshot.currentDay, 0);
  let completionRiskLevel = CompletionRiskLevel.LOW;

  if (remainingRequiredCount > 0 && remainingAvailableDays <= 0) {
    completionRiskLevel = CompletionRiskLevel.HIGH;
  } else if (remainingRequiredCount >= remainingAvailableDays && remainingRequiredCount > 0) {
    completionRiskLevel = CompletionRiskLevel.HIGH;
  } else if (remainingRequiredCount >= Math.ceil(remainingAvailableDays * withDefaultPolicy(policy).completionRiskMediumRatio)) {
    completionRiskLevel = CompletionRiskLevel.MEDIUM;
  }

  return { remainingRequiredCount, remainingAvailableDays, completionRiskLevel };
}

export function isDeadlineApproaching(snapshot, policy = DEFAULT_POLICY) {
  return (
    !snapshot.todayCompleted &&
    snapshot.minutesUntilDeadline !== null &&
    snapshot.minutesUntilDeadline <= withDefaultPolicy(policy).deadlineApproachingMinutes
  );
}

export function isStreakContinuing(snapshot, policy = DEFAULT_POLICY) {
  return snapshot.currentStreak >= withDefaultPolicy(policy).streakContinuingDays;
}

export function isNewStreakRecord(snapshot) {
  return snapshot.currentStreak > snapshot.bestStreak;
}

export function isCompletionAtRisk(snapshot) {
  return snapshot.completionRiskLevel !== CompletionRiskLevel.LOW;
}

export function isGroupGoalNear(snapshot, policy = DEFAULT_POLICY) {
  return snapshot.groupCompletionRate !== null && snapshot.groupCompletionRate >= withDefaultPolicy(policy).groupGoalNearRate;
}

export function isImprovedFromPrevious(snapshot) {
  return (
    snapshot.previousChallengeCompletionRate !== null &&
    snapshot.personalCompletionRate > snapshot.previousChallengeCompletionRate
  );
}

function insight(type, snapshot, policy, context) {
  return {
    type,
    priority: withDefaultPolicy(policy).priority[type] ?? 999,
    context,
    detectedAt: snapshot.snapshotGeneratedAt,
  };
}

function coachCacheKey(snapshot, context) {
  return `${snapshot.participationId}:${context.insight.type ?? "NONE"}:${JSON.stringify(context.insight.context)}`;
}

function withDefaultPolicy(policy = {}) {
  return {
    ...DEFAULT_POLICY,
    ...policy,
    priority: {
      ...DEFAULT_POLICY.priority,
      ...(policy.priority ?? {}),
    },
  };
}

function certificationDateOf(certification) {
  if (typeof certification === "string" || certification instanceof Date) return certification;
  return certification.completedAt ?? certification.certifiedAt ?? certification.completedOn ?? certification.date;
}

function isCompletedCertification(certification) {
  if (typeof certification !== "object" || certification === null || certification instanceof Date) return true;
  if (certification.completed === false || certification.approved === false) return false;

  const status = String(certification.status ?? certification.state ?? "COMPLETED").toUpperCase();
  return !["FAILED", "REJECTED", "CANCELED", "CANCELLED"].includes(status);
}

function calculateCurrentStreak(completionDates, today, todayCompleted) {
  let day = todayCompleted ? today : addDays(today, -1);
  let streak = 0;

  while (completionDates.has(day)) {
    streak += 1;
    day = addDays(day, -1);
  }

  return streak;
}

function calculateBestStreak(completionDates) {
  let best = 0;
  let current = 0;
  let previous = null;

  for (const day of [...completionDates].sort()) {
    current = previous && day === addDays(previous, 1) ? current + 1 : 1;
    best = Math.max(best, current);
    previous = day;
  }

  return best;
}

function calculateMissedDays(startedOn, currentDay, completionDates, today) {
  const missed = [];

  for (let offset = 0; offset < currentDay; offset += 1) {
    const day = addDays(toDateOnly(startedOn), offset);
    if (day === today) continue;
    if (!completionDates.has(day)) missed.push(day);
  }

  return missed;
}

function daysBetweenInclusive(from, to) {
  return Math.floor((dateOnlyToTime(toDateOnly(to)) - dateOnlyToTime(toDateOnly(from))) / DAY_MS) + 1;
}

function addDays(dateOnly, days) {
  return new Date(dateOnlyToTime(dateOnly) + days * DAY_MS).toISOString().slice(0, 10);
}

function toDateOnly(value) {
  if (value instanceof Date) return value.toISOString().slice(0, 10);
  return String(value).slice(0, 10);
}

function parseInstant(value) {
  const date = value instanceof Date ? value : new Date(value);
  if (Number.isNaN(date.getTime())) throw new Error(`Invalid date: ${value}`);
  return date;
}

function dateOnlyToTime(dateOnly) {
  return Date.parse(`${dateOnly}T00:00:00.000Z`);
}

function required(value, name) {
  if (value === undefined || value === null || value === "") throw new Error(`${name} is required`);
  return value;
}

function positiveInt(value, name) {
  const number = Number(required(value, name));
  if (!Number.isInteger(number) || number <= 0) throw new Error(`${name} must be a positive integer`);
  return number;
}

function nonNegativeInt(value, name) {
  const number = Number(required(value, name));
  if (!Number.isInteger(number) || number < 0) throw new Error(`${name} must be a non-negative integer`);
  return number;
}

function nullableInt(value) {
  if (value === undefined || value === null) return null;
  const number = Number(value);
  if (!Number.isInteger(number) || number < 0) throw new Error("value must be a non-negative integer");
  return number;
}

function clampInt(value, min, max) {
  return Math.min(Math.max(Number(value), min), max);
}

function normalizeNullableRate(value) {
  return value === undefined || value === null ? null : normalizeRate(value);
}

function normalizeRate(value) {
  const number = Number(value);
  if (!Number.isFinite(number)) throw new Error("rate must be finite");
  return Math.round(Math.min(Math.max(number > 1 ? number / 100 : number, 0), 1) * 100) / 100;
}

function requireText(value, name, maxLength, allowEmpty = false) {
  if (typeof value !== "string") throw new Error(`${name} must be a string`);
  const text = value.trim();
  if (!allowEmpty && !text) throw new Error(`${name} is required`);
  if (text.length > maxLength) throw new Error(`${name} is too long`);
  return text;
}

function requireEnum(value, values, name) {
  if (!values.includes(value)) throw new Error(`${name} is invalid`);
  return value;
}

function actionFor(type, context) {
  if (type === InsightType.GROUP_GOAL_NEAR) return [ActionType.OPEN_GROUP, "그룹 현황 보기"];
  if (type === InsightType.STREAK_CONTINUING || type === InsightType.STREAK_RECORD) {
    return [ActionType.OPEN_PROGRESS, "진행 현황 보기"];
  }
  if (type === InsightType.IMPROVED_FROM_PREVIOUS) return [ActionType.OPEN_PROGRESS, "진행 현황 보기"];
  if (type === InsightType.COMPLETION_RISK && context.progress.todayCompleted) {
    return [ActionType.OPEN_PROGRESS, "진행 현황 보기"];
  }
  if (type) return [ActionType.OPEN_CERTIFICATION, "인증하기"];
  return [ActionType.NONE, ""];
}

function responseFor(type, context, title, message) {
  const [actionType, actionLabel] = actionFor(type, context);
  return { title, message, actionType, actionLabel };
}

function formatMinutes(minutes) {
  if (minutes === null) return null;
  if (minutes < 60) return `${minutes}분`;
  const hours = Math.floor(minutes / 60);
  const rest = minutes % 60;
  return rest ? `${hours}시간 ${rest}분` : `${hours}시간`;
}

const TEMPLATES = {
  [InsightType.DEADLINE_APPROACHING]: (context) =>
    responseFor(
      InsightType.DEADLINE_APPROACHING,
      context,
      "오늘 인증이 아직 남아 있어요",
      `마감까지 약 ${formatMinutes(context.deadline.minutesRemaining)} 남았습니다.`,
    ),
  [InsightType.COMPLETION_RISK]: (context) =>
    responseFor(
      InsightType.COMPLETION_RISK,
      context,
      "완주 기준을 한번 확인해요",
      `남은 ${context.progress.remainingAvailableDays}일 동안 ${context.progress.remainingRequiredCount}회 인증이 필요합니다.`,
    ),
  [InsightType.GROUP_GOAL_NEAR]: (context) =>
    responseFor(
      InsightType.GROUP_GOAL_NEAR,
      context,
      "그룹 목표가 가까워졌어요",
      "조금만 더 참여하면 공동 목표에 도달할 수 있어요.",
    ),
  [InsightType.STREAK_RECORD]: (context) =>
    responseFor(
      InsightType.STREAK_RECORD,
      context,
      "새로운 연속 기록이에요",
      `현재 ${context.progress.currentStreak}일 연속으로 루틴을 이어가고 있습니다.`,
    ),
  [InsightType.STREAK_CONTINUING]: (context) =>
    responseFor(
      InsightType.STREAK_CONTINUING,
      context,
      "좋은 흐름을 이어가고 있어요",
      `현재 ${context.progress.currentStreak}일 연속 성공 기록을 이어가고 있습니다.`,
    ),
  [InsightType.IMPROVED_FROM_PREVIOUS]: (context) =>
    responseFor(
      InsightType.IMPROVED_FROM_PREVIOUS,
      context,
      "이전보다 나아지고 있어요",
      "이번 챌린지 진행률이 이전 기록보다 좋아졌습니다.",
    ),
  [InsightType.TODAY_NOT_COMPLETED]: (context) =>
    responseFor(
      InsightType.TODAY_NOT_COMPLETED,
      context,
      "오늘 루틴을 아직 인증하지 않았어요",
      "가능한 시간에 오늘 인증을 완료해 보세요.",
    ),
  DEFAULT: () => ({
    title: "오늘도 차분히 이어가요",
    message: "현재 진행 상태를 확인하며 루틴을 이어가면 됩니다.",
    actionType: ActionType.NONE,
    actionLabel: "",
  }),
};
