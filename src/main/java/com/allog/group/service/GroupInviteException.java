package com.allog.group.service;

public class GroupInviteException extends RuntimeException {
    public enum Reason {
        GROUP_NOT_FOUND,
        NOT_PRIVATE,
        INVITE_NOT_FOUND
    }

    private final Reason reason;

    public GroupInviteException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public Reason reason() {
        return reason;
    }
}
