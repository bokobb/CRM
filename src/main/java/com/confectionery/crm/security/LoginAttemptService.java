package com.confectionery.crm.security;

import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Защита входа в админку от подбора: после пяти неудачных попыток подряд для одного логина
 * следующая возможна через минуту. В памяти — после перезапуска счётчики обнуляются, этого
 * достаточно против перебора через форму.
 */
@Service
public class LoginAttemptService {

    static final int MAX_FAILURES = 5;
    static final Duration PAUSE = Duration.ofMinutes(1);

    private record State(int failures, Instant lockedUntil) {
    }

    private final Map<String, State> states = new ConcurrentHashMap<>();

    private static String key(String username) {
        return username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
    }

    /** Сколько секунд ещё ждать; 0 — можно пробовать. */
    public long secondsLeft(String username) {
        State s = states.get(key(username));
        if (s == null || s.lockedUntil() == null) return 0;
        long left = Duration.between(Instant.now(), s.lockedUntil()).getSeconds();
        return Math.max(0, left + 1);
    }

    public void failed(String username) {
        states.compute(key(username), (k, s) -> {
            int n = (s == null || (s.lockedUntil() != null && Instant.now().isAfter(s.lockedUntil()))) ? 1 : s.failures() + 1;
            return new State(n, n >= MAX_FAILURES ? Instant.now().plus(PAUSE) : null);
        });
    }

    public void succeeded(String username) {
        states.remove(key(username));
    }
}
