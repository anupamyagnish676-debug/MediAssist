package com.med.assistant.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe sliding window rate limiter to protect against:
 * - Gemini AI Vision cost exploitation / Denial-of-Wallet attacks
 * - WhatsApp API message spamming
 * - Malicious rapid-fire token hogging
 */
@Service
public class RateLimitService {

    private static final Logger logger = LoggerFactory.getLogger(RateLimitService.class);

    private final ConcurrentHashMap<String, Deque<Long>> requestLogs = new ConcurrentHashMap<>();

    /**
     * Attempts to acquire an execution permit within the sliding time window.
     *
     * @param key           Unique rate-limit key (e.g. "PRESCRIPTION_UPLOAD:+919876543210")
     * @param maxRequests   Maximum number of requests permitted within the window
     * @param windowSeconds Duration of the sliding window in seconds
     * @return true if permitted, false if rate limit exceeded
     */
    public boolean tryAcquire(String key, int maxRequests, long windowSeconds) {
        long now = Instant.now().toEpochMilli();
        long windowMillis = windowSeconds * 1000L;
        long windowStart = now - windowMillis;

        Deque<Long> timestamps = requestLogs.computeIfAbsent(key, k -> new ArrayDeque<>());

        synchronized (timestamps) {
            // Evict outdated entries outside the sliding window
            while (!timestamps.isEmpty() && timestamps.peekFirst() < windowStart) {
                timestamps.pollFirst();
            }

            if (timestamps.size() < maxRequests) {
                timestamps.addLast(now);
                return true;
            } else {
                logger.warn("Rate limit exceeded for key [{}]: {}/{} requests in {}s window",
                        key, timestamps.size(), maxRequests, windowSeconds);
                return false;
            }
        }
    }

    /**
     * Calculates the remaining seconds until a new permit becomes available.
     */
    public long getWaitTimeSeconds(String key, int maxRequests, long windowSeconds) {
        long now = Instant.now().toEpochMilli();
        long windowMillis = windowSeconds * 1000L;
        long windowStart = now - windowMillis;

        Deque<Long> timestamps = requestLogs.get(key);
        if (timestamps == null) return 0;

        synchronized (timestamps) {
            while (!timestamps.isEmpty() && timestamps.peekFirst() < windowStart) {
                timestamps.pollFirst();
            }
            if (timestamps.size() < maxRequests) return 0;
            Long oldest = timestamps.peekFirst();
            if (oldest == null) return 0;
            long waitMillis = (oldest + windowMillis) - now;
            return Math.max(1, (waitMillis + 999) / 1000);
        }
    }

    /**
     * Helper specifically for Gemini Vision prescription & report scans:
     * Max 5 uploads per phone every 10 minutes.
     */
    public boolean tryAcquirePrescriptionScan(String phone) {
        return tryAcquire("VISION_SCAN:" + phone, 5, 600);
    }

    /**
     * Helper specifically for appointment bookings:
     * Max 10 bookings per phone every 1 hour.
     */
    public boolean tryAcquireAppointmentBooking(String phone) {
        return tryAcquire("APPT_BOOKING:" + phone, 10, 3600);
    }

    /**
     * Helper specifically for webhook messages per user:
     * Max 60 messages per phone every 60 seconds (1 msg/sec burst protection).
     */
    public boolean tryAcquireWebhookMessage(String phone) {
        return tryAcquire("MSG_BURST:" + phone, 60, 60);
    }
}
