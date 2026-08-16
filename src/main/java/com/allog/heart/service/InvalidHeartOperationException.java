package com.allog.heart.service;

/** A heart operation that is not allowed at all, such as refunding a spend that never happened. */
public class InvalidHeartOperationException extends IllegalStateException {

    public InvalidHeartOperationException(String message) {
        super(message);
    }
}
