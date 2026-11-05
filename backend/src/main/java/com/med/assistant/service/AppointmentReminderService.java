package com.med.assistant.service;

import com.med.assistant.model.Appointment;
import com.med.assistant.repository.AppointmentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Proactive appointment reminder scheduler.
 * Sends WhatsApp reminders 24 hours and 2 hours before appointments.
 * Also sends 48-hour post-visit follow-up messages.
 */
@Service
public class AppointmentReminderService {

    private static final Logger logger = LoggerFactory.getLogger(AppointmentReminderService.class);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd MMM yyyy");

    // Track already-sent reminders to avoid duplicates across scheduler runs
    private final Set<String> sent24hReminders = ConcurrentHashMap.newKeySet();
    private final Set<String> sent2hReminders = ConcurrentHashMap.newKeySet();
    private final Set<String> sentFollowUps = ConcurrentHashMap.newKeySet();

    private final AppointmentRepository appointmentRepository;
    private final WhatsAppClientService whatsAppClient;

    public AppointmentReminderService(AppointmentRepository appointmentRepository,
                                      WhatsAppClientService whatsAppClient) {
        this.appointmentRepository = appointmentRepository;
        this.whatsAppClient = whatsAppClient;
    }

    /**
     * Runs every 30 minutes. Checks for appointments tomorrow (24h reminder)
     * and appointments today within the next 2-3 hours (2h reminder).
     */
    @Scheduled(fixedDelay = 1800000) // every 30 minutes
    public void sendAppointmentReminders() {
        try {
            LocalDate today = LocalDate.now();
            LocalDate tomorrow = today.plusDays(1);

            // 24-hour reminder: appointments tomorrow
            List<Appointment> tomorrowAppts = appointmentRepository.findByAppointmentDate(tomorrow);
            for (Appointment appt : tomorrowAppts) {
                if (appt.getStatus() == Appointment.Status.CANCELLED) continue;
                if (appt.getPatientPhone() == null || appt.getPatientPhone().isBlank()) continue;
                String reminderKey = "24H:" + appt.getId();
                if (sent24hReminders.contains(reminderKey)) continue;

                String msg = build24hReminderMessage(appt);
                boolean sent = whatsAppClient.sendTextMessage(appt.getPatientPhone(), msg);
                if (sent) {
                    sent24hReminders.add(reminderKey);
                    logger.info("24h reminder sent for appointment {} to {}", appt.getId(), appt.getPatientPhone());
                }
            }

            // 2-hour reminder: appointments today where timeSlot matches the next 2-3 hour window
            List<Appointment> todayAppts = appointmentRepository.findByAppointmentDate(today);
            LocalDateTime now = LocalDateTime.now();
            for (Appointment appt : todayAppts) {
                if (appt.getStatus() == Appointment.Status.CANCELLED) continue;
                if (appt.getPatientPhone() == null || appt.getPatientPhone().isBlank()) continue;
                String reminderKey = "2H:" + appt.getId();
                if (sent2hReminders.contains(reminderKey)) continue;

                // Parse timeSlot to check if within 2-3 hours from now
                if (isWithinTwoHours(appt.getTimeSlot(), now)) {
                    String msg = build2hReminderMessage(appt);
                    boolean sent = whatsAppClient.sendTextMessage(appt.getPatientPhone(), msg);
                    if (sent) {
                        sent2hReminders.add(reminderKey);
                        logger.info("2h reminder sent for appointment {} to {}", appt.getId(), appt.getPatientPhone());
                    }
                }
            }

            // Clean up old sent reminder keys (keep memory bounded)
            if (sent24hReminders.size() > 5000) sent24hReminders.clear();
            if (sent2hReminders.size() > 5000) sent2hReminders.clear();

        } catch (Exception e) {
            logger.error("Error in appointment reminder scheduler: {}", e.getMessage(), e);
        }
    }

    /**
     * Runs every hour. Sends a follow-up message 48 hours after appointment date.
     */
    @Scheduled(fixedDelay = 3600000) // every hour
    public void sendPostVisitFollowUps() {
        try {
            LocalDate followUpDate = LocalDate.now().minusDays(2);
            List<Appointment> pastAppts = appointmentRepository.findByAppointmentDate(followUpDate);

            for (Appointment appt : pastAppts) {
                if (appt.getStatus() == Appointment.Status.CANCELLED) continue;
                if (appt.getPatientPhone() == null || appt.getPatientPhone().isBlank()) continue;
                String followUpKey = "FOLLOWUP:" + appt.getId();
                if (sentFollowUps.contains(followUpKey)) continue;

                String msg = buildFollowUpMessage(appt);
                boolean sent = whatsAppClient.sendInteractiveButtons(
                        appt.getPatientPhone(),
                        msg,
                        List.of(
                                new WhatsAppClientService.ButtonOption("FOLLOWUP_BETTER_" + appt.getId(), "😊 Feeling Better"),
                                new WhatsAppClientService.ButtonOption("FOLLOWUP_REBOOK_" + appt.getId(), "📅 Need Follow-up"),
                                new WhatsAppClientService.ButtonOption("FOLLOWUP_DISMISS_" + appt.getId(), "✅ All Good")
                        )
                );
                if (sent) {
                    sentFollowUps.add(followUpKey);
                    logger.info("Post-visit follow-up sent for appointment {} to {}", appt.getId(), appt.getPatientPhone());
                }
            }

            if (sentFollowUps.size() > 5000) sentFollowUps.clear();

        } catch (Exception e) {
            logger.error("Error in post-visit follow-up scheduler: {}", e.getMessage(), e);
        }
    }

    private String build24hReminderMessage(Appointment appt) {
        String docName = (appt.getDoctor() != null) ? appt.getDoctor().getName() : "your doctor";
        String dept = (appt.getDoctor() != null) ? appt.getDoctor().getDepartment() : "";
        String hospital = (appt.getHospital() != null) ? appt.getHospital().getName() : "the clinic";
        String timeSlot = (appt.getTimeSlot() != null) ? appt.getTimeSlot() : "your scheduled time";
        String patientName = (appt.getPatientName() != null && !appt.getPatientName().isBlank()) ? appt.getPatientName() : "Patient";

        return String.format("""
                ⏰ *Appointment Reminder — Tomorrow*

                Hello %s! You have an OPD appointment scheduled for *tomorrow*.

                🏥 *Hospital:* %s
                👨‍⚕️ *Doctor:* Dr. %s (%s)
                🎫 *Token No.:* #%02d
                📅 *Date:* %s
                🕐 *Time Slot:* %s

                📌 *Please remember to:*
                • Arrive 15 minutes early
                • Carry this WhatsApp chat for your token
                • Bring any previous prescriptions or reports

                Reply *CANCEL* to cancel your appointment.
                """,
                patientName, hospital, docName, dept,
                appt.getSerialNumber(),
                appt.getAppointmentDate().format(DATE_FMT),
                timeSlot
        );
    }

    private String build2hReminderMessage(Appointment appt) {
        String docName = (appt.getDoctor() != null) ? appt.getDoctor().getName() : "your doctor";
        String hospital = (appt.getHospital() != null) ? appt.getHospital().getName() : "the clinic";
        String patientName = (appt.getPatientName() != null && !appt.getPatientName().isBlank()) ? appt.getPatientName() : "Patient";

        return String.format("""
                🔔 *Your appointment is in ~2 hours!*

                Hello %s! This is your 2-hour reminder.

                🏥 *Hospital:* %s
                👨‍⚕️ *Doctor:* Dr. %s
                🎫 *Token No.:* *#%02d*

                Please start heading to the hospital now. Type *queue* to check how many patients are ahead of you.
                """,
                patientName, hospital, docName, appt.getSerialNumber()
        );
    }

    private String buildFollowUpMessage(Appointment appt) {
        String docName = (appt.getDoctor() != null) ? appt.getDoctor().getName() : "your doctor";
        String patientName = (appt.getPatientName() != null && !appt.getPatientName().isBlank()) ? appt.getPatientName() : "there";

        return String.format("""
                💙 *Post-Visit Check-in from MediAssist*

                Hi %s! It's been 2 days since your visit with Dr. %s.

                How are you feeling? Please let us know:
                """,
                patientName, docName
        );
    }

    /**
     * Checks if an appointment's timeSlot falls within 2-3 hours from now.
     * Handles common formats: "Morning (9-12)", "10:00 AM", "AM", "PM", etc.
     */
    private boolean isWithinTwoHours(String timeSlot, LocalDateTime now) {
        if (timeSlot == null) return false;
        String lower = timeSlot.toLowerCase();
        int currentHour = now.getHour();

        // Morning shift: roughly 9-12
        if (lower.contains("morning") || lower.contains("am") || lower.contains("9") || lower.contains("10")) {
            return currentHour >= 7 && currentHour <= 10;
        }
        // Afternoon shift: roughly 2-5
        if (lower.contains("afternoon") || lower.contains("evening") || lower.contains("pm") || lower.contains("14") || lower.contains("2")) {
            return currentHour >= 12 && currentHour <= 14;
        }
        return false;
    }
}
