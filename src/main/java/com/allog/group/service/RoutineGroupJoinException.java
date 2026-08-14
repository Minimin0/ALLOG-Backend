package com.allog.group.service;

import java.util.Objects;

public class RoutineGroupJoinException extends RuntimeException {

    private final Reason reason;

    public RoutineGroupJoinException(Reason reason, String message) {
        super(message);
        this.reason = Objects.requireNonNull(reason, "reason must not be null");
    }

    public Reason reason() {
        return reason;
    }

    public enum Reason {
        GROUP_NOT_FOUND,
        ALREADY_JOINED,
        NOT_JOINABLE,
        GROUP_FULL
    }
}
