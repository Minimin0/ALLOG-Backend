package com.allog.user.service;

/** The authenticated user exists but has not completed onboarding yet. */
public class ProfileNotFoundException extends RuntimeException {

    public ProfileNotFoundException() {
        super("user profile not found");
    }
}
