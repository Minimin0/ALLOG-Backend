package com.allog.heart.service;

/** The wallet did not hold enough hearts for the spend. Carries no balance: it is not a hint. */
public class InsufficientHeartsException extends RuntimeException {

    public InsufficientHeartsException() {
        super("wallet does not hold enough hearts");
    }
}
