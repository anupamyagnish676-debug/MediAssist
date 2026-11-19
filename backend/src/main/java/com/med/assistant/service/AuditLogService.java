package com.med.assistant.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;

/**
 * Centralized audit and user-action logger.
 *
 * CRITICAL ARCHITECTURAL GUARANTEE:
 * User action logs are written directly to a dedicated file on disk (./logs/user_actions.log).
 * They are intentionally NEVER persisted in the database (Zero DB overhead & privacy compliance).
 *
 * Events recorded:
 * - WhatsApp messages, symptom questions, greetings
 * - Interactive button and list clicks
 * - GPS location shares & hospital radius queries
 * - Doctor, date, and shift selections
 * - OPD queue appointment bookings, cancellations, reschedules
 * - Case sheet PDF downloads & medical locker views
 * - Report and prescription uploads
 * - Medication alarms set, cancelled, taken, and snoozed
 * - Web portal logins, OTP requests, and patient demographic registrations
 */
@Service
public class AuditLogService {

    private static final Logger auditLogger = LoggerFactory.getLogger("AUDIT");
    private static final DateTimeFormatter FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final long MAX_FILE_SIZE_BYTES = 20 * 1024 * 1024; // 20 MB rollover

    private final Path logDir;
    private final Path logFile;

    public AuditLogService() {
        Path resolvedDir = Paths.get("logs");
        try {
            if (!Files.exists(resolvedDir)) {
                Files.createDirectories(resolvedDir);
            }
            Path testPath = resolvedDir.resolve(".write_test");
            Files.writeString(testPath, "ok", StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
            Files.deleteIfExists(testPath);
        } catch (Exception e) {
            // Container fallback if current working dir is read-only for unprivileged user
            resolvedDir = Paths.get(System.getProperty("java.io.tmpdir", "/tmp"), "med-logs");
            try {
                if (!Files.exists(resolvedDir)) {
                    Files.createDirectories(resolvedDir);
                }
            } catch (Exception ignored) {}
        }

        this.logDir = resolvedDir;
        this.logFile = resolvedDir.resolve("user_actions.log");

        try {
            if (!Files.exists(this.logFile)) {
                Files.writeString(this.logFile,
                        "# MediAssist User Action Log File (File-Based Storage, Non-Database)\n" +
                        "# Session Started: " + LocalDateTime.now() + "\n\n",
                        StandardCharsets.UTF_8, StandardOpenOption.CREATE);
            }
        } catch (Exception ignored) {}

        auditLogger.info("AuditLogService active. User actions log path: {}", this.logFile.toAbsolutePath());
    }

    public enum AuditEvent {
        // WhatsApp User Actions
        MESSAGE_RECEIVED,
        BUTTON_CLICKED,
        LIST_OPTION_SELECTED,
        LOCATION_SHARED,
        NEARBY_HOSPITALS_VIEWED,
        HOSPITAL_SELECTED,
        DOCTOR_SCHEDULE_VIEWED,
        DOCTOR_SELECTED,
        DATE_SELECTED,
        SHIFT_SELECTED,
        APPOINTMENT_BOOKED,
        APPOINTMENT_CANCELLED,
        APPOINTMENT_RESCHEDULED,
        APPOINTMENT_RESCHEDULE_REQUESTED,
        LIVE_QUEUE_CHECKED,
        MEDICAL_LOCKER_VIEWED,
        MEDICAL_RECORD_ACCESSED,
        CASE_SHEET_DOWNLOADED,
        REPORT_UPLOADED,
        FILE_UPLOADED,
        FILE_REJECTED,
        MEDICATION_REMINDER_SET,
        MEDICATION_REMINDER_CANCELLED,
        MEDICATION_TAKEN,
        MEDICATION_SNOOZED,
        EMERGENCY_TRIGGERED,

        // Authentication & Security Actions
        OTP_REQUESTED,
        OTP_VERIFIED,
        OTP_FAILED,
        OTP_EXPIRED,
        OTP_RATE_LIMITED,
        RATE_LIMIT_HIT,
        WEBHOOK_SIGNATURE_FAILED,
        WEBHOOK_DUPLICATE_DROPPED,
        LOGIN_SUCCESS,
        LOGIN_FAILED,
        LOGIN_LOCKED,
        DOUBLE_BOOKING_BLOCKED,

        // Profile & Administrative Actions
        PROFILE_REGISTERED,
        PROFILE_CREATED,
        PROFILE_UPDATED,
        ADMIN_ACTION,
        WALK_IN_TOKEN_ISSUED
    }

    /**
     * Alias for log(...) to record user action.
     */
    public synchronized void logAction(AuditEvent event, String actor, String detail) {
        log(event, actor, detail, null);
    }

    /**
     * Log a user action or system event.
     * Appends directly to ./logs/user_actions.log (never stored in the database).
     *
     * @param event  The type of event/action
     * @param actor  User phone number, email, or system identifier
     * @param detail Description of the action performed
     */
    public synchronized void log(AuditEvent event, String actor, String detail) {
        log(event, actor, detail, null);
    }

    /**
     * Log an action with client IP address context.
     */
    public synchronized void log(AuditEvent event, String actor, String detail, String ipAddress) {
        String timestamp = LocalDateTime.now().format(FMT);
        String cleanActor = (actor != null && !actor.isBlank()) ? actor.trim() : "ANONYMOUS";
        String ipPart = (ipAddress != null && !ipAddress.isBlank()) ? " [IP: " + ipAddress.trim() + "]" : "";

        String logEntry = String.format("[%s] [USER_ACTION] [ACTOR: %s]%s [EVENT: %s] - %s%n",
                timestamp, cleanActor, ipPart, event.name(), detail != null ? detail.trim() : "");

        // 1. Output to console / standard logger
        auditLogger.info(logEntry.trim());

        // 2. Write directly to disk file ./logs/user_actions.log
        try {
            if (!Files.exists(logDir)) {
                Files.createDirectories(logDir);
            }

            // Simple size-based rotation if log exceeds 20MB
            if (Files.exists(logFile) && Files.size(logFile) > MAX_FILE_SIZE_BYTES) {
                Path backup = logDir.resolve("user_actions_" + System.currentTimeMillis() + ".log");
                Files.move(logFile, backup);
            }

            Files.writeString(logFile, logEntry, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            auditLogger.error("Failed to write user action to log file: {}", e.getMessage());
        }
    }

    /**
     * Returns the physical log file on disk.
     */
    public File getLogFile() {
        try {
            if (!Files.exists(logDir)) {
                Files.createDirectories(logDir);
            }
            if (!Files.exists(logFile)) {
                Files.writeString(logFile, "# MediAssist User Action Log File\n# Started: " + LocalDateTime.now() + "\n\n",
                        StandardCharsets.UTF_8, StandardOpenOption.CREATE);
            }
        } catch (IOException ignored) {}
        return logFile.toFile();
    }

    /**
     * Retrieves the most recent log lines for UI viewing.
     *
     * @param maxLines Maximum number of recent lines to retrieve
     */
    public List<String> getRecentLogs(int maxLines) {
        try {
            if (!Files.exists(logFile)) {
                return Collections.emptyList();
            }
            List<String> allLines = Files.readAllLines(logFile, StandardCharsets.UTF_8);
            if (allLines.size() <= maxLines) {
                return allLines;
            }
            return allLines.subList(allLines.size() - maxLines, allLines.size());
        } catch (Exception e) {
            return List.of("Error reading user_actions.log: " + e.getMessage());
        }
    }

    /**
     * Returns size in bytes of the log file.
     */
    public long getLogFileSize() {
        try {
            return Files.exists(logFile) ? Files.size(logFile) : 0L;
        } catch (Exception e) {
            return 0L;
        }
    }

    /**
     * Clears the log file contents.
     */
    public synchronized boolean clearLogs() {
        try {
            if (Files.exists(logFile)) {
                Files.writeString(logFile, "# MediAssist User Action Log File (Cleared at " + LocalDateTime.now() + ")\n\n",
                        StandardCharsets.UTF_8, StandardOpenOption.TRUNCATE_EXISTING);
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }
}
