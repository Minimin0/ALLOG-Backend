package com.allog.heart.domain;

/**
 * Why hearts moved.
 *
 * <p>Only the reasons that exist today. Attendance, invites and admin adjustments are not listed:
 * Product has not defined those earning rules, and an enum constant the database accepts is a
 * promise the API has to keep.
 */
public enum HeartTransactionType {

    /** Paid once when a member finishes profile and onboarding. Source: user_profile id. */
    INITIAL_GRANT(true),

    /** Charged when a member joins a group. Source: group_member id. */
    GROUP_JOIN_SPEND(false),

    /** Returned when a joined group ends before it starts. Source: group_member id. */
    GROUP_JOIN_REFUND(true);

    private final boolean credit;

    HeartTransactionType(boolean credit) {
        this.credit = credit;
    }

    /** True when this type adds hearts. Mirrors chk_heart_ledger_direction. */
    public boolean isCredit() {
        return credit;
    }
}
