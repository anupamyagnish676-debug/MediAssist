package com.med.assistant.controller;

import com.med.assistant.service.AuditLogService;
import com.med.assistant.service.PatientAuthService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@CrossOrigin(origins = "*")
public class PatientOtpApiController {

    private final PatientAuthService patientAuthService;
    private final AuditLogService auditLogService;

    public PatientOtpApiController(PatientAuthService patientAuthService, AuditLogService auditLogService) {
        this.patientAuthService = patientAuthService;
        this.auditLogService = auditLogService;
    }

    public record OtpRequestDto(String phone) {}
    public record OtpVerifyDto(String phone, String otp) {}

    /**
     * Request a 6-digit WhatsApp OTP to access private patient medical locker.
     * Hardened against phone enumeration: always returns HTTP 200 with an identical message
     * regardless of whether the phone number is valid, unknown, or rate limited.
     */
    @PostMapping({"/api/v1/patient/otp/request", "/api/v1/appointments/patient/otp/request"})
    public ResponseEntity<?> requestOtp(@RequestBody(required = false) OtpRequestDto body,
                                        @RequestParam(required = false) String phone) {
        String targetPhone = (body != null && body.phone() != null) ? body.phone() : phone;

        if (targetPhone != null && !targetPhone.isBlank()) {
            patientAuthService.requestOtp(targetPhone.trim());
            auditLogService.log(AuditLogService.AuditEvent.OTP_REQUESTED, targetPhone.trim(), "OTP request received via API");
        } else {
            auditLogService.log(AuditLogService.AuditEvent.OTP_REQUESTED, "UNKNOWN", "Empty phone OTP request received");
        }

        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "A 6-digit verification passcode has been sent to your WhatsApp number."
        ));
    }

    /**
     * Verify the 6-digit WhatsApp OTP and receive an HMAC-signed session token.
     */
    @PostMapping({"/api/v1/patient/otp/verify", "/api/v1/appointments/patient/otp/verify"})
    public ResponseEntity<?> verifyOtp(@RequestBody(required = false) OtpVerifyDto body,
                                       @RequestParam(required = false) String phone,
                                       @RequestParam(required = false) String otp) {
        String targetPhone = (body != null && body.phone() != null) ? body.phone() : phone;
        String targetOtp = (body != null && body.otp() != null) ? body.otp() : otp;

        if (targetPhone == null || targetOtp == null || targetOtp.isBlank()) {
            auditLogService.log(AuditLogService.AuditEvent.OTP_FAILED, targetPhone != null ? targetPhone : "UNKNOWN", "Missing phone or OTP in verify request");
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "Phone and 6-digit OTP are required"));
        }

        String sessionToken = patientAuthService.verifyOtp(targetPhone.trim(), targetOtp.trim());
        if (sessionToken == null) {
            auditLogService.log(AuditLogService.AuditEvent.OTP_FAILED, targetPhone.trim(), "Invalid or expired OTP verification attempt");
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of(
                    "success", false,
                    "message", "Invalid or expired OTP. Please request a new passcode."
            ));
        }

        auditLogService.log(AuditLogService.AuditEvent.OTP_VERIFIED, targetPhone.trim(), "OTP verified successfully via API");
        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Identity successfully verified!",
                "token", sessionToken,
                "phone", patientAuthService.canonicalizePhone(targetPhone)
        ));
    }
}
