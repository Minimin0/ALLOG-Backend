package com.allog.user.domain;

/**
 * Closed taxonomy of what a member wants to work on, used as recommendation input.
 *
 * <p>Deliberately not {@code RoutineKey}: that is an open, user-generated vocabulary identifying a
 * concrete routine definition, while this is a fixed set of categories. Binding the two would make a
 * stated interest depend on some other user's routine existing.
 */
public enum InterestCategory {
    HYDRATION,
    EXERCISE,
    MEAL,
    SLEEP,
    SKINCARE
}
