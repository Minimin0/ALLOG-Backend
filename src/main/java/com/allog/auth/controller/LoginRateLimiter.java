package com.allog.auth.controller;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
public class LoginRateLimiter {

    static final int MAX_LOGIN_FAILURES = 10;
    static final int MAX_SIGNUP_REQUESTS = 5;
    static final Duration WINDOW = Duration.ofMinutes(1);
    private static final int MAX_CLIENTS = 10_000;

    private final Map<String, Window> loginFailures = new LinkedHashMap<>(16, 0.75f, true);
    private final Map<String, Window> signupRequests = new LinkedHashMap<>(16, 0.75f, true);

    public void checkLogin(HttpServletRequest request) {
        if (!consumeLogin(clientKey(request), Instant.now())) {
            throw new LoginLimitExceededException();
        }
    }

    public synchronized void resetLoginFailures(HttpServletRequest request) {
        loginFailures.remove(clientKey(request));
    }

    public void checkSignup(HttpServletRequest request) {
        if (!consumeSignup(clientKey(request), Instant.now())) {
            throw new SignupLimitExceededException();
        }
    }

    // ponytail: one lock and bounded local LRUs fit one backend; share/shard only for multiple instances or measured contention.
    synchronized boolean consumeLogin(String clientKey, Instant now) {
        Window current = current(loginFailures, clientKey, now);
        if (current != null && current.requests() >= MAX_LOGIN_FAILURES) {
            return false;
        }
        put(loginFailures, clientKey, next(current, now));
        return true;
    }

    synchronized boolean consumeSignup(String clientKey, Instant now) {
        Window current = current(signupRequests, clientKey, now);
        if (current != null && current.requests() >= MAX_SIGNUP_REQUESTS) {
            return false;
        }
        put(signupRequests, clientKey, next(current, now));
        return true;
    }

    private Window next(Window current, Instant now) {
        return new Window(current == null ? now : current.startedAt(), current == null ? 1 : current.requests() + 1);
    }

    private Window current(Map<String, Window> windows, String clientKey, Instant now) {
        Window current = windows.get(clientKey);
        if (current == null || !now.isBefore(current.startedAt().plus(WINDOW))) {
            windows.remove(clientKey);
            return null;
        }
        return current;
    }

    private void put(Map<String, Window> windows, String clientKey, Window window) {
        if (!windows.containsKey(clientKey) && windows.size() >= MAX_CLIENTS) {
            windows.remove(windows.keySet().iterator().next());
        }
        windows.put(clientKey, window);
    }

    static String clientKey(HttpServletRequest request) {
        String remoteAddress = request.getRemoteAddr();
        String realAddress = request.getHeader("X-Real-IP");
        if (isLoopback(remoteAddress) && isIpLiteral(realAddress)) {
            return realAddress;
        }
        return remoteAddress == null || remoteAddress.isBlank() ? "unknown" : remoteAddress;
    }

    private static boolean isLoopback(String address) {
        return "127.0.0.1".equals(address)
                || "::1".equals(address)
                || "0:0:0:0:0:0:0:1".equals(address);
    }

    private static boolean isIpLiteral(String address) {
        if (address == null || address.isBlank() || address.length() > 45) {
            return false;
        }
        if (address.contains(":")) {
            try {
                return InetAddress.getByName(address) instanceof Inet6Address;
            } catch (UnknownHostException ignored) {
                return false;
            }
        }
        String[] octets = address.split("\\.", -1);
        if (octets.length != 4) {
            return false;
        }
        for (String octet : octets) {
            try {
                if (octet.isEmpty()
                        || octet.length() > 3
                        || octet.chars().anyMatch(character -> !Character.isDigit(character))
                        || Integer.parseInt(octet) > 255) {
                    return false;
                }
            } catch (NumberFormatException ignored) {
                return false;
            }
        }
        return true;
    }

    private record Window(Instant startedAt, int requests) {
    }

    static final class LoginLimitExceededException extends RuntimeException {
    }

    static final class SignupLimitExceededException extends RuntimeException {
    }
}
