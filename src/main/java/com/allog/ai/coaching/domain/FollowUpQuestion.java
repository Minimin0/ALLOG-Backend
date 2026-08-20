package com.allog.ai.coaching.domain;

public enum FollowUpQuestion {
    PACE_CHECK(
            "지금 페이스 어때요?",
            "현재 개인 진행 페이스가 목표 대비 어떤 상태인지 설명한다."
    ),
    NEXT_ACTION(
            "지금 가장 중요한 건 뭐예요?",
            "현재 사실을 기준으로 사용자가 지금 가장 먼저 해야 할 행동을 설명한다."
    ),
    GROUP_PROGRESS(
            "우리 그룹은 잘하고 있나요?",
            "현재 그룹 공동 진행 상태를 설명한다."
    );

    private final String label;
    private final String instruction;

    FollowUpQuestion(String label, String instruction) {
        this.label = label;
        this.instruction = instruction;
    }

    public String label() {
        return label;
    }

    public String instruction() {
        return instruction;
    }
}
