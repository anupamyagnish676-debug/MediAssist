package com.med.assistant.service;

import com.med.assistant.model.Appointment;
import com.med.assistant.model.Doctor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;

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

    public record DoctorSlot(
            int slotNumber,         // Token/Slot 1, 2, 3...
            String shiftName,       // "Morning (1st Half)" or "Evening (2nd Half)"
            LocalTime startTime,
            LocalTime endTime,
            String timeWindow,      // "09:30 AM - 09:45 AM"
            String reportingTime,   // "09:15 AM"
            boolean isBooked
    ) {}

    public record SlotMatchResult(
            DoctorSlot slot,
            boolean isExactMatch,
            String requestedTimeDisplay,
            String noticeMessage
    ) {}

    /**
     * Calculates all theoretical discrete slots for a doctor on a given day across both shifts.
     */
    public List<DoctorSlot> generateDoctorSlots(Doctor doctor, LocalDate date, List<Appointment> bookedAppts) {
        List<DoctorSlot> slots = new ArrayList<>();
        if (doctor == null) return slots;

        int duration = doctor.getConsultationDurationMinutes() > 0 ? doctor.getConsultationDurationMinutes() : 15;
        int tokenNum = 1;

        // Set of already booked slot start times or tokens
        Set<String> bookedTimeStarts = new HashSet<>();
        Set<Integer> bookedTokens = new HashSet<>();
        if (bookedAppts != null) {
            for (Appointment a : bookedAppts) {
                if (a.getStatus() != Appointment.Status.CANCELLED) {
                    bookedTokens.add(a.getSerialNumber());
                    if (a.getTimeSlot() != null) {
                        String ts = a.getTimeSlot();
                        int dash = ts.indexOf("-");
                        if (dash > 0) {
                            bookedTimeStarts.add(ts.substring(0, dash).trim().toUpperCase());
                        }
                    }
                }
            }
        }

        // 1. First Half (Morning Shift)
        String firstShift = (doctor.getFirstHalfTime() != null && !doctor.getFirstHalfTime().isBlank())
                ? doctor.getFirstHalfTime() : "09:00 AM - 01:00 PM";
        LocalTime[] firstRange = parseShiftTimes(firstShift);
        if (firstRange == null) firstRange = new LocalTime[]{LocalTime.of(9, 0), LocalTime.of(13, 0)};

        int firstLimit = doctor.getFirstHalfLimit() > 0 ? doctor.getFirstHalfLimit() : 15;
        LocalTime cur = firstRange[0];
        int shift1Slots = 0;
        while (cur.plusMinutes(duration).isBefore(firstRange[1].plusSeconds(1)) && shift1Slots < firstLimit) {
            LocalTime end = cur.plusMinutes(duration);
            LocalTime rep = cur.minusMinutes(15).isBefore(firstRange[0]) ? firstRange[0] : cur.minusMinutes(15);
            String window = cur.format(TIME_FORMATTER) + " - " + end.format(TIME_FORMATTER);
            String repStr = rep.format(TIME_FORMATTER);

            boolean isBooked = bookedTokens.contains(tokenNum) || bookedTimeStarts.contains(cur.format(TIME_FORMATTER).toUpperCase());

            slots.add(new DoctorSlot(tokenNum, "Morning (1st Half)", cur, end, window, repStr, isBooked));
            tokenNum++;
            shift1Slots++;
            cur = end;
        }

        // 2. Second Half (Evening Shift)
        String secondShift = (doctor.getSecondHalfTime() != null && !doctor.getSecondHalfTime().isBlank())
                ? doctor.getSecondHalfTime() : "05:00 PM - 09:00 PM";
        LocalTime[] secondRange = parseShiftTimes(secondShift);
        if (secondRange == null) secondRange = new LocalTime[]{LocalTime.of(17, 0), LocalTime.of(21, 0)};

        int secondLimit = doctor.getSecondHalfLimit() > 0 ? doctor.getSecondHalfLimit() : 15;
        cur = secondRange[0];
        int shift2Slots = 0;
        while (cur.plusMinutes(duration).isBefore(secondRange[1].plusSeconds(1)) && shift2Slots < secondLimit) {
            LocalTime end = cur.plusMinutes(duration);
            LocalTime rep = cur.minusMinutes(15).isBefore(secondRange[0]) ? secondRange[0] : cur.minusMinutes(15);
            String window = cur.format(TIME_FORMATTER) + " - " + end.format(TIME_FORMATTER);
            String repStr = rep.format(TIME_FORMATTER);

            boolean isBooked = bookedTokens.contains(tokenNum) || bookedTimeStarts.contains(cur.format(TIME_FORMATTER).toUpperCase());

            slots.add(new DoctorSlot(tokenNum, "Evening (2nd Half)", cur, end, window, repStr, isBooked));
            tokenNum++;
            shift2Slots++;
            cur = end;
        }

        return slots;
    }

    /**
     * Maps user's preferred time or preferred shift to the nearest available open slot.
     */
    public SlotMatchResult findNearestAvailableSlot(
            Doctor doctor,
            LocalDate date,
            List<Appointment> bookedAppts,
            String userTimeOrShift
    ) {
        List<DoctorSlot> allSlots = generateDoctorSlots(doctor, date, bookedAppts);

        // Filter out past slots if booking is for TODAY
        LocalTime nowIst = LocalTime.now(ZoneId.of("Asia/Kolkata"));
        boolean isToday = date.equals(LocalDate.now(ZoneId.of("Asia/Kolkata")));

        List<DoctorSlot> availableSlots = new ArrayList<>();
        for (DoctorSlot s : allSlots) {
            if (s.isBooked()) continue;
            if (isToday && s.startTime().isBefore(nowIst.plusMinutes(15))) continue;
            availableSlots.add(s);
        }

        // If completely full for the day
        if (availableSlots.isEmpty()) {
            return null;
        }

        // Try parsing user input as a specific time (e.g. "10:30 AM", "6:00 PM", "11:15")
        LocalTime parsedTime = parseSingleTime(userTimeOrShift);
        String shiftChoice = (userTimeOrShift != null) ? userTimeOrShift.trim().toUpperCase() : "";

        if (parsedTime != null) {
            DoctorSlot closest = null;
            long minDiff = Long.MAX_VALUE;

            for (DoctorSlot slot : availableSlots) {
                long diff = Math.abs(ChronoUnit.MINUTES.between(slot.startTime(), parsedTime));
                // If equidistant, prefer the slot on or after requested time (forward in time)
                if (diff < minDiff || (diff == minDiff && !slot.startTime().isBefore(parsedTime))) {
                    minDiff = diff;
                    closest = slot;
                }
            }

            if (closest != null) {
                boolean exact = (minDiff == 0);
                String reqStr = parsedTime.format(TIME_FORMATTER);
                String notice = exact
                        ? "✅ Exact time available: " + closest.timeWindow()
                        : "🎯 Requested: " + reqStr + " ➡️ Nearest available: " + closest.timeWindow() + " (" + closest.shiftName() + ")";
                return new SlotMatchResult(closest, exact, reqStr, notice);
            }
        }

        // Check if user selected Evening / Second Half
        if (shiftChoice.contains("EVENING") || shiftChoice.contains("SECOND") || shiftChoice.contains("NIGHT") || shiftChoice.contains("PM")) {
            for (DoctorSlot s : availableSlots) {
                if (s.shiftName().contains("Evening")) {
                    return new SlotMatchResult(s, true, "Evening Shift", "🌆 Assigned Evening Shift slot: " + s.timeWindow());
                }
            }
            DoctorSlot fallback = availableSlots.get(0);
            return new SlotMatchResult(fallback, false, "Evening Shift", "⚠️ Evening shift is full; mapped to nearest available: " + fallback.timeWindow() + " (" + fallback.shiftName() + ")");
        }

        // Check if user selected Morning / First Half
        if (shiftChoice.contains("MORNING") || shiftChoice.contains("FIRST") || shiftChoice.contains("AM")) {
            for (DoctorSlot s : availableSlots) {
                if (s.shiftName().contains("Morning")) {
                    return new SlotMatchResult(s, true, "Morning Shift", "🌅 Assigned Morning Shift slot: " + s.timeWindow());
                }
            }
            DoctorSlot fallback = availableSlots.get(0);
            return new SlotMatchResult(fallback, false, "Morning Shift", "⚠️ Morning shift is full; mapped to nearest available: " + fallback.timeWindow() + " (" + fallback.shiftName() + ")");
        }

        // Default: return the earliest open slot
        DoctorSlot defaultSlot = availableSlots.get(0);
        return new SlotMatchResult(defaultSlot, true, "Next Available", "🟢 Earliest open slot: " + defaultSlot.timeWindow() + " (" + defaultSlot.shiftName() + ")");
    }

    /**
     * Staggered fallback computation for Token #N (maintains backward compatibility).
     */
    public TentativeSlot calculateTentativeWindow(Doctor doctor, int tokenNumber) {
        LocalTime shiftStart = LocalTime.of(9, 30);
        LocalTime shiftEnd = LocalTime.of(13, 30);

        if (doctor != null && doctor.getAvailableTime() != null && !doctor.getAvailableTime().isBlank()) {
            LocalTime[] parsed = parseShiftTimes(doctor.getAvailableTime());
            if (parsed != null && parsed.length == 2) {
                shiftStart = parsed[0];
                shiftEnd = parsed[1];
            }
        }

        int limit = (doctor != null && doctor.getDailyTokenLimit() > 0) ? doctor.getDailyTokenLimit() : 20;
        long totalShiftMinutes = ChronoUnit.MINUTES.between(shiftStart, shiftEnd);
        if (totalShiftMinutes <= 0) totalShiftMinutes = 240;

        int slotDuration = (int) Math.max(10, Math.min(20, totalShiftMinutes / limit));
        if (slotDuration <= 0) slotDuration = 15;

        int offsetMinutes = Math.max(0, tokenNumber - 1) * slotDuration;
        LocalTime slotStart = shiftStart.plusMinutes(offsetMinutes);
        LocalTime slotEnd = slotStart.plusMinutes(slotDuration);

        LocalTime nowIst = LocalTime.now(ZoneId.of("Asia/Kolkata"));
        if (nowIst.isAfter(slotStart) && nowIst.isBefore(shiftEnd)) {
            slotStart = roundUpToNextFiveMinutes(nowIst.plusMinutes(15));
            slotEnd = slotStart.plusMinutes(slotDuration);
        }

        LocalTime reportingTime = slotStart.minusMinutes(15);
        if (reportingTime.isBefore(shiftStart)) {
            reportingTime = shiftStart;
        }

        String windowStr = slotStart.format(TIME_FORMATTER) + " - " + slotEnd.format(TIME_FORMATTER);
        String reportingStr = reportingTime.format(TIME_FORMATTER);

        int estimatedWait = (int) Math.max(0, ChronoUnit.MINUTES.between(nowIst, slotStart));

        return new TentativeSlot(windowStr, reportingStr, slotStart, slotEnd, estimatedWait);
    }

    public LocalTime[] parseShiftTimes(String availableTime) {
        if (availableTime == null || availableTime.isBlank()) return null;
        try {
            String firstPart = availableTime.contains("&") ? availableTime.split("&")[0].trim() : availableTime;
            String[] parts = firstPart.split("-");
            if (parts.length != 2) return null;

            LocalTime start = parseSingleTime(parts[0].trim());
            LocalTime end = parseSingleTime(parts[1].trim());

            if (start != null && end != null) {
                return new LocalTime[]{start, end};
            }
        } catch (Exception e) {
            logger.warn("Could not parse shift time '{}': {}", availableTime, e.getMessage());
        }
        return null;
    }

    /**
     * Parses time strings like "10:30 AM", "10:30", "10am", "6pm", "6:30 pm", "18:00".
     */
    public LocalTime parseSingleTime(String timeStr) {
        if (timeStr == null || timeStr.isBlank()) return null;
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
                if (digits.length() == 4) {
                    hours = Integer.parseInt(digits.substring(0, 2));
                    mins = Integer.parseInt(digits.substring(2));
                } else if (digits.length() <= 2) {
                    hours = Integer.parseInt(digits);
                    mins = 0;
                }
            }

            if (isPm && hours < 12) hours += 12;
            if (isAm && hours == 12) hours = 0;

            if (hours >= 0 && hours < 24 && mins >= 0 && mins < 60) {
                return LocalTime.of(hours, mins);
            }
            return null;
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
