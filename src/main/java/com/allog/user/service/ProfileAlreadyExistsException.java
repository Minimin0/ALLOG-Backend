package com.allog.user.service;

public class ProfileAlreadyExistsException extends RuntimeException {

    public ProfileAlreadyExistsException() {
        super("user profile already exists");
    }
}
