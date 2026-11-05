package com.med.assistant.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Brute-force login protection: tracks failed login attempts per username/IP.
 * After MAX_ATTEMPTS failures within LOCK_WINDOW_SECONDS, the account is locked
 * for LOCKOUT_SECONDS. Resets automatically after lockout expires.
 */
@Service
public class LoginAttemptService {

    private static final Logger logger = LoggerFactory.getLogger(LoginAttemptService.class);

    private static final int MAX_ATTEMPTS = 5;
    private static final long LOCK_WINDOW_SECONDS = 300;   // 5-minute window
    private static final long LOCKOUT_SECONDS = 900;        // 15-minute lockout

    private record AttemptRecord(AtomicInteger count, long windowStart, long lockedUntil) {}

    private final ConcurrentHashMap<String, AttemptRecord> attempts = new ConcurrentHashMap<>();

    /**
     * Call this when a login attempt FAILS.
     * @param key username or IP address
     */
    public void recordFailure(String key) {
        long now = Instant.now().getEpochSecond();
        attempts.compute(key, (k, existing) -> {
            if (existing == null || (now - existing.windowStart()) > LOCK_WINDOW_SECONDS) {
                // Start a fresh window
                AtomicInteger counter = new AtomicInteger(1);
                return new AttemptRecord(counter, now, 0L);
            }
            int count = existing.count().incrementAndGet();
            long lockedUntil = (count >= MAX_ATTEMPTS) ? now + LOCKOUT_SECONDS : existing.lockedUntil();
            if (count >= MAX_ATTEMPTS) {
                logger.warn("[SECURITY] Account locked after {} failed attempts: {}", count, k);
            }
            return new AttemptRecord(existing.count(), existing.windowStart(), lockedUntil);
        });
    }

    /**
     * Call this when a login attempt SUCCEEDS — clears the failure counter.
     * @param key username or IP address
     */
    public void recordSuccess(String key) {
        attempts.remove(key);
    }

    public void resetLockout(String key) {
        attempts.remove(key);
    }

    /**
     * Returns true if the key is currently locked out.
     * @param key username or IP address
     */
    public boolean isLocked(String key) {
        AttemptRecord record = attempts.get(key);
        if (record == null) return false;
        long now = Instant.now().getEpochSecond();
        if (record.lockedUntil() > 0 && now < record.lockedUntil()) {
            return true;
        }
        // Lockout expired — clean up
        if (record.lockedUntil() > 0 && now >= record.lockedUntil()) {
            attempts.remove(key);
        }
        return false;
    }

    /**
     * Returns remaining lockout seconds (0 if not locked).
     */
    public long getLockoutRemainingSeconds(String key) {
        AttemptRecord record = attempts.get(key);
        if (record == null || record.lockedUntil() == 0) return 0;
        long remaining = record.lockedUntil() - Instant.now().getEpochSecond();
        return Math.max(0, remaining);
    }

    /**
     * Returns number of failed attempts in the current window.
     */
    public int getFailureCount(String key) {
        AttemptRecord record = attempts.get(key);
        return (record == null) ? 0 : record.count().get();
    }
}
