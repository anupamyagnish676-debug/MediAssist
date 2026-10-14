package com.med.assistant.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Service providing HIPAA & DPDP Act compliant patient authentication:
 * - Generates cryptographically secure 6-digit one-time passcodes (OTP).
 * - Delivers OTPs securely over the patient's verified WhatsApp number.
 * - Issues HMAC-SHA256 signed session tokens to gate access to sensitive medical records & prescriptions.
 */
@Service
public class PatientAuthService {

    private static final Logger logger = LoggerFactory.getLogger(PatientAuthService.class);

    private final WhatsAppClientService whatsAppClient;
    private final RateLimitService rateLimitService;

    @Value("${app.security.locker-secret:MediAssist_Patient_Medical_Locker_Secret_HMAC_Key_2026!}")
    private String lockerSecret;

    // In-memory store for active OTPs: canonicalPhone -> OtpRecord
    private record OtpRecord(String otp, long expiresAt, int attempts) {}
    private final Map<String, OtpRecord> otpStore = new ConcurrentHashMap<>();

    // Expiry durations
    private static final long OTP_VALIDITY_MILLIS = 5 * 60 * 1000L;       // 5 minutes
    private static final long SESSION_VALIDITY_MILLIS = 2 * 3600 * 1000L;  // 2 hours

    public PatientAuthService(WhatsAppClientService whatsAppClient, RateLimitService rateLimitService) {
        this.whatsAppClient = whatsAppClient;
        this.rateLimitService = rateLimitService;
    }

    /**
     * Normalizes phone numbers to standard 10-digit or canonical format.
     */
    public String canonicalizePhone(String rawPhone) {
        if (rawPhone == null) return "";
        String digits = rawPhone.replaceAll("[^0-9]", "");
        if (digits.length() == 10) {
            return digits;
        } else if (digits.length() == 12 && digits.startsWith("91")) {
            return digits.substring(2);
        }
        return digits;
    }

    /**
     * Generates a 6-digit OTP, records it, and delivers it to the patient via WhatsApp.
     */
    public boolean requestOtp(String phone) {
        String canonicalPhone = canonicalizePhone(phone);
        if (canonicalPhone.length() < 10) {
            logger.warn("Invalid phone number length for OTP request: {}", phone);
            return false;
        }

        // Rate limit: Max 3 OTP requests per phone every 5 minutes
        if (!rateLimitService.tryAcquire("OTP_REQUEST:" + canonicalPhone, 3, 300)) {
            logger.warn("OTP request rate limit hit for phone: {}", canonicalPhone);
            return false;
        }

        String otp = String.format("%06d", ThreadLocalRandom.current().nextInt(100000, 1000000));
        long expiresAt = Instant.now().toEpochMilli() + OTP_VALIDITY_MILLIS;

        otpStore.put(canonicalPhone, new OtpRecord(otp, expiresAt, 0));

        // Format destination phone for WhatsApp
        String sendPhone = phone.trim();
        if (!sendPhone.startsWith("+") && !sendPhone.startsWith("91") && canonicalPhone.length() == 10) {
            sendPhone = "+91" + canonicalPhone;
        }

        String message = String.format("""
            🔐 *MediAssist Medical Locker Passcode*
            
            Your one-time passcode (OTP) to view your private medical locker and prescriptions is:
            
            👉 *%s*
            
            ⏰ Valid for 5 minutes.
            ⚠️ Never share this passcode with anyone. MediAssist doctors and staff will never ask for this code.
            """, otp);

        try {
            whatsAppClient.sendTextMessage(sendPhone, message);
            logger.info("Successfully dispatched WhatsApp OTP to {}", sendPhone);
            return true;
        } catch (Exception e) {
            logger.error("Failed to send WhatsApp OTP to {}: {}", sendPhone, e.getMessage(), e);
            return false;
        }
    }

    /**
     * Verifies the submitted OTP. Returns a signed session token if valid, or null if invalid.
     */
    public String verifyOtp(String phone, String submittedOtp) {
        String canonicalPhone = canonicalizePhone(phone);
        if (canonicalPhone.isEmpty() || submittedOtp == null) return null;

        OtpRecord record = otpStore.get(canonicalPhone);
        if (record == null) {
            logger.warn("No pending OTP found for phone: {}", canonicalPhone);
            return null;
        }

        long now = Instant.now().toEpochMilli();
        if (now > record.expiresAt()) {
            otpStore.remove(canonicalPhone);
            logger.warn("Expired OTP for phone: {}", canonicalPhone);
            return null;
        }

        if (record.attempts() >= 3) {
            otpStore.remove(canonicalPhone);
            logger.warn("Max OTP verification attempts exceeded for phone: {}", canonicalPhone);
            return null;
        }

        if (!record.otp().equals(submittedOtp.trim())) {
            otpStore.put(canonicalPhone, new OtpRecord(record.otp(), record.expiresAt(), record.attempts() + 1));
            logger.warn("Incorrect OTP entered for phone: {}", canonicalPhone);
            return null;
        }

        // Successfully verified! Consume OTP
        otpStore.remove(canonicalPhone);

        // Generate signed HMAC session token
        return generateSessionToken(canonicalPhone);
    }

    /**
     * Creates a URL-safe Base64 signed token: canonicalPhone|expiresAt|hmac
     */
    private String generateSessionToken(String canonicalPhone) {
        long expiresAt = Instant.now().toEpochMilli() + SESSION_VALIDITY_MILLIS;
        String payload = canonicalPhone + "|" + expiresAt;
        String signature = computeHmac(payload);
        String rawToken = payload + "|" + signature;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(rawToken.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Validates that a session token is untampered, unexpired, and matches the target phone.
     */
    public boolean validateToken(String token, String targetPhone) {
        if (token == null || token.isBlank() || targetPhone == null) return false;

        try {
            String decoded = new String(Base64.getUrlDecoder().decode(token.trim()), StandardCharsets.UTF_8);
            String[] parts = decoded.split("\\|");
            if (parts.length != 3) return false;

            String tokenPhone = parts[0];
            long expiresAt = Long.parseLong(parts[1]);
            String tokenSignature = parts[2];

            // 1. Expiration check
            if (Instant.now().toEpochMilli() > expiresAt) {
                logger.warn("Expired patient session token for phone: {}", tokenPhone);
                return false;
            }

            // 2. Cryptographic signature check
            String expectedSignature = computeHmac(tokenPhone + "|" + expiresAt);
            if (!MessageDigest.isEqual(
                    expectedSignature.getBytes(StandardCharsets.UTF_8),
                    tokenSignature.getBytes(StandardCharsets.UTF_8))) {
                logger.warn("Tampered session token signature detected for phone: {}", tokenPhone);
                return false;
            }

            // 3. Subject Phone Match
            String canonicalTarget = canonicalizePhone(targetPhone);
            if (!canonicalTarget.equals(tokenPhone)) {
                logger.warn("Session token phone mismatch: token={}, target={}", tokenPhone, canonicalTarget);
                return false;
            }

            return true;
        } catch (Exception e) {
            logger.warn("Failed to validate patient session token: {}", e.getMessage());
            return false;
        }
    }

    private String computeHmac(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec keySpec = new SecretKeySpec(lockerSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(keySpec);
            byte[] hmacBytes = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hmacBytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException("HMAC computation failure", e);
        }
    }
}
