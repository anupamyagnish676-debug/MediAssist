package com.med.assistant.service;

import com.med.assistant.model.Doctor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

@Service
public class OpdScheduleService {

    private static final Logger logger = LoggerFactory.getLogger(OpdScheduleService.class);
    private static final DateTimeFormatter TIME_FORMATTER = DateTimeFormatter.ofPattern("hh:mm a", Locale.ENGLISH);

    public record TentativeSlot(
            String timeWindow,      // e.g. "10:30 AM - 10:45 AM"
            String reportingTime,   // e.g. "10:15 AM"
            LocalTime startTime,
            LocalTime endTime,
            int estimatedWaitMinutes
    ) {}

    /**
     * Calculates an individualized, staggered tentative consultation window for Token #N.
     * Prevents hospital waiting room surges by distributing patients smoothly across the OPD shift.
     */
    public TentativeSlot calculateTentativeWindow(Doctor doctor, int tokenNumber) {
        LocalTime shiftStart = LocalTime.of(9, 30);
        LocalTime shiftEnd = LocalTime.of(13, 30);

        // 1. Parse doctor available time if defined
        if (doctor != null && doctor.getAvailableTime() != null && !doctor.getAvailableTime().isBlank()) {
            LocalTime[] parsed = parseShiftTimes(doctor.getAvailableTime());
            if (parsed != null && parsed.length == 2) {
                shiftStart = parsed[0];
                shiftEnd = parsed[1];
            }
        }

        // 2. Determine slot duration in minutes per patient
        int limit = (doctor != null && doctor.getDailyTokenLimit() > 0) ? doctor.getDailyTokenLimit() : 20;
        long totalShiftMinutes = ChronoUnit.MINUTES.between(shiftStart, shiftEnd);
        if (totalShiftMinutes <= 0) totalShiftMinutes = 240; // Default 4 hours if wrap-around

        int slotDuration = (int) Math.max(10, Math.min(20, totalShiftMinutes / limit));
        if (slotDuration <= 0) slotDuration = 15;

        // 3. Staggered theoretical start time for Token #tokenNumber
        int offsetMinutes = Math.max(0, tokenNumber - 1) * slotDuration;
        LocalTime slotStart = shiftStart.plusMinutes(offsetMinutes);
        LocalTime slotEnd = slotStart.plusMinutes(slotDuration);

        // 4. Mid-Day Booking Adaptation: Ensure we never assign a slot in the past today
        LocalTime nowIst = LocalTime.now(ZoneId.of("Asia/Kolkata"));
        if (nowIst.isAfter(slotStart) && nowIst.isBefore(shiftEnd)) {
            // Buffer patient 15 minutes to reach hospital and check-in
            slotStart = roundUpToNextFiveMinutes(nowIst.plusMinutes(15));
            slotEnd = slotStart.plusMinutes(slotDuration);
        }

        // Reporting time: 15 minutes before the tentative start time
        LocalTime reportingTime = slotStart.minusMinutes(15);
        if (reportingTime.isBefore(shiftStart)) {
            reportingTime = shiftStart;
        }

        String windowStr = slotStart.format(TIME_FORMATTER) + " - " + slotEnd.format(TIME_FORMATTER);
        String reportingStr = reportingTime.format(TIME_FORMATTER);

        int estimatedWait = (int) Math.max(0, ChronoUnit.MINUTES.between(nowIst, slotStart));

        return new TentativeSlot(windowStr, reportingStr, slotStart, slotEnd, estimatedWait);
    }

    /**
     * Parses time range string like "10:00 AM - 2:00 PM" or "09:00 AM - 01:00 PM".
     */
    private LocalTime[] parseShiftTimes(String availableTime) {
        try {
            String[] parts = availableTime.split("-");
            if (parts.length != 2) return null;

            LocalTime start = parseSingleTime(parts[0].trim());
            LocalTime end = parseSingleTime(parts[1].trim());

            if (start != null && end != null) {
                return new LocalTime[]{start, end};
            }
        } catch (Exception e) {
            logger.warn("Could not parse doctor available time '{}': {}", availableTime, e.getMessage());
        }
        return null;
    }

    private LocalTime parseSingleTime(String timeStr) {
        String clean = timeStr.replaceAll("[^0-9a-zA-Z:]", "").toUpperCase();
        try {
            boolean isPm = clean.endsWith("PM");
            boolean isAm = clean.endsWith("AM");
            String digits = clean.replaceAll("[A-Z]", "");

            int hours = 0;
            int mins = 0;
            if (digits.contains(":")) {
                String[] p = digits.split(":");
                hours = Integer.parseInt(p[0]);
                mins = Integer.parseInt(p[1]);
            } else {
                hours = Integer.parseInt(digits);
            }

            if (isPm && hours < 12) hours += 12;
            if (isAm && hours == 12) hours = 0;

            return LocalTime.of(hours, mins);
        } catch (Exception e) {
            return null;
        }
    }

    private LocalTime roundUpToNextFiveMinutes(LocalTime time) {
        int minute = time.getMinute();
        int remainder = minute % 5;
        if (remainder != 0) {
            time = time.plusMinutes(5 - remainder);
        }
        return time.withSecond(0).withNano(0);
    }
}
