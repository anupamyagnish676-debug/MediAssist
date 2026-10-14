package com.med.assistant;

import com.med.assistant.service.PatientAuthService;
import com.med.assistant.service.RateLimitService;
import com.med.assistant.service.WhatsAppClientService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.junit.jupiter.api.Assertions.*;

public class SecurityFeaturesTest {

    @Test
    @DisplayName("RateLimitService enforces sliding window request permits")
    void testRateLimitPermits() {
        RateLimitService limiter = new RateLimitService();
        String testKey = "TEST_USER_123";

        // Allow up to 3 requests in a 10s window
        assertTrue(limiter.tryAcquire(testKey, 3, 10));
        assertTrue(limiter.tryAcquire(testKey, 3, 10));
        assertTrue(limiter.tryAcquire(testKey, 3, 10));

        // 4th request must be rejected
        assertFalse(limiter.tryAcquire(testKey, 3, 10));
        assertTrue(limiter.getWaitTimeSeconds(testKey, 3, 10) > 0);
    }

    @Test
    @DisplayName("PatientAuthService verifies WhatsApp OTP and rejects invalid or tampered tokens")
    void testPatientOtpAndHmacTokenValidation() {
        WhatsAppClientService mockWhatsApp = Mockito.mock(WhatsAppClientService.class);
        RateLimitService limiter = new RateLimitService();
        PatientAuthService authService = new PatientAuthService(mockWhatsApp, limiter);

        String testPhone = "+919876543210";

        // 1. Request OTP
        boolean requested = authService.requestOtp(testPhone);
        assertTrue(requested);

        // 2. Incorrect OTP fails
        String badToken = authService.verifyOtp(testPhone, "000000");
        assertNull(badToken);

        // 3. Rate limiting OTP requests
        authService.requestOtp(testPhone);
        authService.requestOtp(testPhone);
        // 4th attempt within 5 minutes must be rate limited
        boolean fourthAttempt = authService.requestOtp(testPhone);
        assertFalse(fourthAttempt);

        // 4. Token validation with tampered token
        assertFalse(authService.validateToken("fake.token.value", testPhone));
        assertFalse(authService.validateToken(null, testPhone));
    }
}
