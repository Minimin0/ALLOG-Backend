package com.allog.group.service;

import java.util.Objects;

/** A lifecycle command the group's current state does not allow. */
public class GroupLifecycleException extends RuntimeException {

    private final Reason reason;

    public GroupLifecycleException(Reason reason, String message) {
        super(message);
        this.reason = Objects.requireNonNull(reason, "reason must not be null");
    }

    public Reason reason() {
        return reason;
    }

    public enum Reason {
        GROUP_NOT_FOUND,
        MEMBERSHIP_NOT_FOUND,
        /** The owner holds the room; ending it is a cancellation, not a departure. */
        OWNER_MUST_CANCEL,
        NOT_LEAVABLE,
        NOT_CANCELLABLE
    }
}
