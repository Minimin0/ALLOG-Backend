package com.allog.heart.service;

/**
 * A wallet was expected and is missing.
 *
 * <p>A wallet exists from the moment a profile does, so this is a broken invariant rather than a
 * client mistake, and it must not be answered as a 4xx or healed by creating an empty wallet.
 */
public class HeartWalletNotFoundException extends IllegalStateException {

    public HeartWalletNotFoundException(Long userId) {
        super("heart wallet missing for user " + userId);
    }
}
