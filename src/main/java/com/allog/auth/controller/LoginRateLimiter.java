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

    static final int MAX_REQUESTS = 10;
    static final Duration WINDOW = Duration.ofMinutes(1);
    private static final int MAX_CLIENTS = 10_000;

    private final Map<String, Window> windows = new LinkedHashMap<>(16, 0.75f, true);

    public void check(HttpServletRequest request) {
        if (!allow(clientKey(request), Instant.now())) {
            throw new LimitExceededException();
        }
    }

    // ponytail: one lock and a bounded local LRU fit one backend; share/shard only for multiple instances or measured contention.
    synchronized boolean allow(String clientKey, Instant now) {
        Window current = windows.get(clientKey);
        if (current == null || !now.isBefore(current.startedAt().plus(WINDOW))) {
            if (current == null && windows.size() >= MAX_CLIENTS) {
                String oldest = windows.keySet().iterator().next();
                windows.remove(oldest);
            }
            windows.put(clientKey, new Window(now, 1));
            return true;
        }
        if (current.requests() >= MAX_REQUESTS) {
            return false;
        }
        windows.put(clientKey, new Window(current.startedAt(), current.requests() + 1));
        return true;
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

    static final class LimitExceededException extends RuntimeException {
    }
}
