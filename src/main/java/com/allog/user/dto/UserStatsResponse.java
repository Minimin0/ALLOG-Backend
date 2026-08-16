package com.allog.user.dto;

/**
 * Counters for the member's own screen.
 *
 * <p>{@code successfulRoutines} is deliberately absent. Its agreed meaning is completed memberships,
 * and nothing persists that yet, so any number here would be invented. It arrives as a new field
 * once completion is recorded - adding one is safe, correcting a wrong one is not.
 */
public record UserStatsResponse(int hearts, long rewardPoints) {
}
