package com.med.assistant.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Centralized audit log for all sensitive patient and security actions.
 * Logs are structured for easy parsing by monitoring tools (e.g. Render log drain, Datadog).
 *
 * Events logged:
 * - OTP request / verify / failure
 * - Appointment booking / cancellation
 * - Medical record access
 * - File upload accept / reject
 * - Rate limit violations
 * - Webhook signature failures
 * - Login success / failure
 * - Admin actions
 */
@Service
public class AuditLogService {

    private static final Logger auditLogger = LoggerFactory.getLogger("AUDIT");
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public enum AuditEvent {
        OTP_REQUESTED, OTP_VERIFIED, OTP_FAILED, OTP_EXPIRED, OTP_RATE_LIMITED,
        APPOINTMENT_BOOKED, APPOINTMENT_CANCELLED, APPOINTMENT_RESCHEDULE_REQUESTED,
        MEDICAL_RECORD_ACCESSED, FILE_UPLOADED, FILE_REJECTED,
        RATE_LIMIT_HIT, WEBHOOK_SIGNATURE_FAILED, WEBHOOK_DUPLICATE_DROPPED,
        LOGIN_SUCCESS, LOGIN_FAILED, LOGIN_LOCKED,
        ADMIN_ACTION, PROFILE_CREATED, PROFILE_UPDATED,
        DOUBLE_BOOKING_BLOCKED, EMERGENCY_TRIGGERED
    }

    /**
     * Log a security or data-access audit event.
     *
     * @param event    The audit event type
     * @param actor    Phone number or username performing the action
     * @param detail   Additional context about the event
     */
    public void log(AuditEvent event, String actor, String detail) {
        String timestamp = LocalDateTime.now().format(FMT);
        // Mask phone numbers partially for privacy (keep last 4 digits)
        String maskedActor = maskPhone(actor);
        auditLogger.info("[AUDIT] {} | event={} | actor={} | detail={}",
                timestamp, event.name(), maskedActor, detail);
    }

    /**
     * Log a security event with an IP address (for REST API calls).
     */
    public void log(AuditEvent event, String actor, String detail, String ipAddress) {
        String timestamp = LocalDateTime.now().format(FMT);
        String maskedActor = maskPhone(actor);
        auditLogger.info("[AUDIT] {} | event={} | actor={} | ip={} | detail={}",
                timestamp, event.name(), maskedActor, ipAddress, detail);
    }

    /**
     * Mask phone number for privacy: show country code + last 4 digits only.
     * e.g. 919876543210 → 91******3210
     */
    private String maskPhone(String phone) {
        if (phone == null || phone.length() < 6) return "***";
        if (phone.matches("\\d+") && phone.length() >= 10) {
            return phone.substring(0, phone.length() - 6) + "******" + phone.substring(phone.length() - 4);
        }
        return phone; // not a phone (e.g. username), return as-is
    }
}
