package com.med.assistant.service;

import com.med.assistant.model.Appointment;
import com.med.assistant.repository.AppointmentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Intelligent OPD Appointment Reminder Service.
 *
 * Rules:
 * 1. Reminders are dispatched ONLY TWICE before the appointment:
 *    - Once at ~1 hour before (between 45 and 75 mins before start time)
 *    - Once at ~30 minutes before (between 15 and 40 mins before start time)
 * 2. If the appointment time passes and patient has not arrived (still CONFIRMED):
 *    - Exactly ONE single polite reschedule message is sent.
 *    - No further reminders or alarms are ever sent.
 * 3. All states are stored persistently in the database (reminded_1h, reminded_30m, missed_reschedule_sent)
 *    and evaluated in Asia/Kolkata (IST) to eliminate duplicate notifications.
 */
@Service
public class AppointmentReminderService {

    private static final Logger logger = LoggerFactory.getLogger(AppointmentReminderService.class);
    private static final ZoneId IST_ZONE = ZoneId.of("Asia/Kolkata");
    private static final Pattern TIME_PATTERN = Pattern.compile("(\\d{1,2}):(\\d{2})\\s*(AM|PM|am|pm)", Pattern.CASE_INSENSITIVE);

    @Value("${app.reminders.enabled:true}")
    private boolean remindersEnabled;

    private final AppointmentRepository appointmentRepository;
    private final WhatsAppClientService whatsAppClient;

    public AppointmentReminderService(AppointmentRepository appointmentRepository,
                                      WhatsAppClientService whatsAppClient) {
        this.appointmentRepository = appointmentRepository;
        this.whatsAppClient = whatsAppClient;
    }

    /**
     * Runs every 5 minutes to check upcoming appointments for today.
     */
    @Scheduled(fixedDelay = 300000) // every 5 minutes
    public void processAppointmentReminders() {
        if (!remindersEnabled) {
            return;
        }

        try {
            ZonedDateTime nowIst = ZonedDateTime.now(IST_ZONE);
            LocalDate today = nowIst.toLocalDate();

            List<Appointment> todayAppts = appointmentRepository.findByAppointmentDate(today);

            for (Appointment appt : todayAppts) {
                if (appt.getPatientPhone() == null || appt.getPatientPhone().isBlank()) continue;

                // Stop immediately if appointment is already completed, cancelled, or checked in
                if (appt.getStatus() != Appointment.Status.CONFIRMED) {
                    continue;
                }

                SlotTimeRange range = parseSlotTimes(appt.getTimeSlot(), today);
                long minutesUntilStart = Duration.between(nowIst, range.start()).toMinutes();
                long minutesAfterEnd = Duration.between(range.end(), nowIst).toMinutes();

                // 1. First reminder: 1 hour before appointment (between 45 and 75 min before)
                if (!appt.isReminded1h() && minutesUntilStart >= 45 && minutesUntilStart <= 75) {
                    String msg = build1HourReminderMessage(appt);
                    boolean sent = whatsAppClient.sendTextMessage(appt.getPatientPhone(), msg);
                    if (sent) {
                        appt.setReminded1h(true);
                        appointmentRepository.save(appt);
                        logger.info("Dispatched 1-hour reminder for appointment #{} to {}", appt.getId(), appt.getPatientPhone());
                    }
                    continue;
                }

                // 2. Second reminder: 30 minutes before appointment (between 15 and 40 min before)
                if (!appt.isReminded30m() && minutesUntilStart >= 15 && minutesUntilStart <= 40) {
                    String msg = build30MinReminderMessage(appt);
                    boolean sent = whatsAppClient.sendTextMessage(appt.getPatientPhone(), msg);
                    if (sent) {
                        appt.setReminded30m(true);
                        appointmentRepository.save(appt);
                        logger.info("Dispatched 30-min reminder for appointment #{} to {}", appt.getId(), appt.getPatientPhone());
                    }
                    continue;
                }

                // 3. Post-appointment missed check: exactly ONE reschedule message after slot has ended
                if (!appt.isMissedRescheduleSent() && minutesAfterEnd >= 15) {
                    String msg = buildMissedRescheduleMessage(appt);
                    boolean sent = whatsAppClient.sendTextMessage(appt.getPatientPhone(), msg);
                    if (sent) {
                        appt.setMissedRescheduleSent(true);
                        // Mark as cancelled/missed so no further scheduler checks or alarms run
                        appt.setStatus(Appointment.Status.CANCELLED);
                        appointmentRepository.save(appt);
                        logger.info("Dispatched single missed/reschedule message for appointment #{} to {}", appt.getId(), appt.getPatientPhone());
                    }
                }
            }

        } catch (Exception e) {
            logger.error("Error in appointment reminder processing: {}", e.getMessage(), e);
        }
    }

    private String formatDoctorName(String docName) {
        if (docName == null || docName.isBlank()) return "your specialist";
        String clean = docName.trim();
        return clean.toLowerCase().startsWith("dr.") ? clean : "Dr. " + clean;
    }

    private String build1HourReminderMessage(Appointment appt) {
        String docName = (appt.getDoctor() != null) ? formatDoctorName(appt.getDoctor().getName()) : "your doctor";
        String dept = (appt.getDoctor() != null && appt.getDoctor().getDepartment() != null) ? " (" + appt.getDoctor().getDepartment() + ")" : "";
        String hospital = (appt.getHospital() != null) ? appt.getHospital().getName() : "the clinic";
        String timeSlot = (appt.getTimeSlot() != null) ? appt.getTimeSlot() : "your scheduled time";
        String patientName = (appt.getPatientName() != null && !appt.getPatientName().isBlank()) ? appt.getPatientName() : "Patient";

        return String.format("""
                ⏰ *Appointment in 1 Hour*

                Hello %s! This is your 1-hour reminder for your OPD visit today.

                🏥 *Hospital:* %s
                👨‍⚕️ *Doctor:* %s%s
                🎫 *Token No.:* *#%02d*
                🕐 *Time Slot:* %s

                Please start heading to the hospital now. Type *queue* to check live patient status.
                Reply *CANCEL* if you cannot attend.
                """,
                patientName, hospital, docName, dept,
                appt.getSerialNumber(),
                timeSlot
        );
    }

    private String build30MinReminderMessage(Appointment appt) {
        String docName = (appt.getDoctor() != null) ? formatDoctorName(appt.getDoctor().getName()) : "your doctor";
        String hospital = (appt.getHospital() != null) ? appt.getHospital().getName() : "the clinic";
        String patientName = (appt.getPatientName() != null && !appt.getPatientName().isBlank()) ? appt.getPatientName() : "Patient";

        return String.format("""
                🔔 *Appointment in 30 Minutes!*

                Hello %s! Your OPD consultation starts in ~30 minutes.

                🏥 *Hospital:* %s
                👨‍⚕️ *Doctor:* %s
                🎫 *Token No.:* *#%02d*

                Please be present at the hospital OPD desk shortly.
                Reply *CANCEL* if you cannot make it.
                """,
                patientName, hospital, docName, appt.getSerialNumber()
        );
    }

    private String buildMissedRescheduleMessage(Appointment appt) {
        String docName = (appt.getDoctor() != null) ? formatDoctorName(appt.getDoctor().getName()) : "your doctor";
        String hospital = (appt.getHospital() != null) ? appt.getHospital().getName() : "the hospital";
        String patientName = (appt.getPatientName() != null && !appt.getPatientName().isBlank()) ? appt.getPatientName() : "there";

        return String.format("""
                🗓️ *Missed Your Appointment?*

                Hello %s, we noticed you were unable to make it to your OPD appointment today with %s at %s.

                Would you like to reschedule your consultation for another date?
                Reply *RESCHEDULE* to select a convenient new date and time slot.
                """,
                patientName, docName, hospital
        );
    }

    public record SlotTimeRange(ZonedDateTime start, ZonedDateTime end) {}

    /**
     * Parses the appointment's timeSlot string into explicit start and end ZonedDateTime in IST.
     */
    private SlotTimeRange parseSlotTimes(String timeSlot, LocalDate date) {
        if (timeSlot == null || timeSlot.isBlank()) {
            return new SlotTimeRange(date.atTime(10, 0).atZone(IST_ZONE), date.atTime(10, 30).atZone(IST_ZONE));
        }

        try {
            Matcher m = TIME_PATTERN.matcher(timeSlot);
            LocalTime startLt = null;
            LocalTime endLt = null;

            if (m.find()) {
                int h = Integer.parseInt(m.group(1));
                int min = Integer.parseInt(m.group(2));
                String ampm = m.group(3).toUpperCase();
                if ("PM".equals(ampm) && h < 12) h += 12;
                if ("AM".equals(ampm) && h == 12) h = 0;
                startLt = LocalTime.of(h, min);

                if (m.find()) {
                    int eh = Integer.parseInt(m.group(1));
                    int emin = Integer.parseInt(m.group(2));
                    String eampm = m.group(3).toUpperCase();
                    if ("PM".equals(eampm) && eh < 12) eh += 12;
                    if ("AM".equals(eampm) && eh == 12) eh = 0;
                    endLt = LocalTime.of(eh, emin);
                }
            }

            if (startLt != null) {
                if (endLt == null) endLt = startLt.plusMinutes(20);
                return new SlotTimeRange(date.atTime(startLt).atZone(IST_ZONE), date.atTime(endLt).atZone(IST_ZONE));
            }

            String lower = timeSlot.toLowerCase();
            if (lower.contains("morning") || lower.contains("1st half")) {
                return new SlotTimeRange(date.atTime(9, 0).atZone(IST_ZONE), date.atTime(13, 0).atZone(IST_ZONE));
            }
            if (lower.contains("afternoon")) {
                return new SlotTimeRange(date.atTime(14, 0).atZone(IST_ZONE), date.atTime(17, 0).atZone(IST_ZONE));
            }
            if (lower.contains("evening") || lower.contains("2nd half")) {
                return new SlotTimeRange(date.atTime(17, 0).atZone(IST_ZONE), date.atTime(20, 0).atZone(IST_ZONE));
            }
        } catch (Exception e) {
            logger.warn("Failed to parse slot range from '{}': {}", timeSlot, e.getMessage());
        }

        return new SlotTimeRange(date.atTime(10, 0).atZone(IST_ZONE), date.atTime(10, 30).atZone(IST_ZONE));
    }
}
