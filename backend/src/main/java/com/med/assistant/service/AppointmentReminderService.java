package com.med.assistant.service;

import com.med.assistant.model.Appointment;
import com.med.assistant.repository.AppointmentRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * Proactive appointment reminder scheduler.
 * Uses persistent DB flags (reminded24h, reminded2h, remindedFollowup) and
 * Asia/Kolkata (IST) timezone to guarantee zero redundant reminder dispatches across redeploys.
 */
@Service
public class AppointmentReminderService {

    private static final Logger logger = LoggerFactory.getLogger(AppointmentReminderService.class);
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("dd MMM yyyy");
    private static final ZoneId IST_ZONE = ZoneId.of("Asia/Kolkata");

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
     * Runs every 30 minutes. Checks for appointments tomorrow (24h reminder)
     * and appointments today within the next 2 hours (2h reminder).
     */
    @Scheduled(fixedDelay = 1800000) // every 30 minutes
    public void sendAppointmentReminders() {
        if (!remindersEnabled) {
            return;
        }

        try {
            ZonedDateTime nowIst = ZonedDateTime.now(IST_ZONE);
            LocalDate today = nowIst.toLocalDate();
            LocalDate tomorrow = today.plusDays(1);

            // 1. 24-hour reminder: appointments tomorrow that are CONFIRMED
            List<Appointment> tomorrowAppts = appointmentRepository.findByAppointmentDate(tomorrow);
            for (Appointment appt : tomorrowAppts) {
                if (appt.getStatus() != Appointment.Status.CONFIRMED) continue;
                if (appt.isReminded24h()) continue;
                if (appt.getPatientPhone() == null || appt.getPatientPhone().isBlank()) continue;

                String msg = build24hReminderMessage(appt);
                boolean sent = whatsAppClient.sendTextMessage(appt.getPatientPhone(), msg);
                if (sent) {
                    appt.setReminded24h(true);
                    appointmentRepository.save(appt);
                    logger.info("24h reminder sent for appointment {} to {}", appt.getId(), appt.getPatientPhone());
                }
            }

            // 2. 2-hour reminder: appointments today where timeSlot matches upcoming 2 hours
            List<Appointment> todayAppts = appointmentRepository.findByAppointmentDate(today);
            for (Appointment appt : todayAppts) {
                if (appt.getStatus() != Appointment.Status.CONFIRMED) continue;
                if (appt.isReminded2h()) continue;
                if (appt.getPatientPhone() == null || appt.getPatientPhone().isBlank()) continue;

                // Check if within 2 hours in IST timezone
                if (isWithinTwoHours(appt.getTimeSlot(), nowIst)) {
                    String msg = build2hReminderMessage(appt);
                    boolean sent = whatsAppClient.sendTextMessage(appt.getPatientPhone(), msg);
                    if (sent) {
                        appt.setReminded2h(true);
                        appointmentRepository.save(appt);
                        logger.info("2h reminder sent for appointment {} to {}", appt.getId(), appt.getPatientPhone());
                    }
                }
            }

        } catch (Exception e) {
            logger.error("Error in appointment reminder scheduler: {}", e.getMessage(), e);
        }
    }

    /**
     * Runs every hour. Sends a follow-up message 48 hours after appointment date.
     */
    @Scheduled(fixedDelay = 3600000) // every hour
    public void sendPostVisitFollowUps() {
        if (!remindersEnabled) {
            return;
        }

        try {
            ZonedDateTime nowIst = ZonedDateTime.now(IST_ZONE);
            LocalDate followUpDate = nowIst.toLocalDate().minusDays(2);
            List<Appointment> pastAppts = appointmentRepository.findByAppointmentDate(followUpDate);

            for (Appointment appt : pastAppts) {
                if (appt.getStatus() == Appointment.Status.CANCELLED) continue;
                if (appt.isRemindedFollowup()) continue;
                if (appt.getPatientPhone() == null || appt.getPatientPhone().isBlank()) continue;

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
                    appt.setRemindedFollowup(true);
                    appointmentRepository.save(appt);
                    logger.info("Post-visit follow-up sent for appointment {} to {}", appt.getId(), appt.getPatientPhone());
                }
            }

        } catch (Exception e) {
            logger.error("Error in post-visit follow-up scheduler: {}", e.getMessage(), e);
        }
    }

    private String formatDoctorName(String docName) {
        if (docName == null || docName.isBlank()) return "your specialist";
        String clean = docName.trim();
        return clean.toLowerCase().startsWith("dr.") ? clean : "Dr. " + clean;
    }

    private String build24hReminderMessage(Appointment appt) {
        String docName = (appt.getDoctor() != null) ? formatDoctorName(appt.getDoctor().getName()) : "your doctor";
        String dept = (appt.getDoctor() != null && appt.getDoctor().getDepartment() != null) ? " (" + appt.getDoctor().getDepartment() + ")" : "";
        String hospital = (appt.getHospital() != null) ? appt.getHospital().getName() : "the clinic";
        String timeSlot = (appt.getTimeSlot() != null) ? appt.getTimeSlot() : "your scheduled time";
        String patientName = (appt.getPatientName() != null && !appt.getPatientName().isBlank()) ? appt.getPatientName() : "Patient";

        return String.format("""
                ⏰ *Appointment Reminder — Tomorrow*

                Hello %s! You have an OPD appointment scheduled for *tomorrow*.

                🏥 *Hospital:* %s
                👨‍⚕️ *Doctor:* %s%s
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
        String docName = (appt.getDoctor() != null) ? formatDoctorName(appt.getDoctor().getName()) : "your doctor";
        String hospital = (appt.getHospital() != null) ? appt.getHospital().getName() : "the clinic";
        String patientName = (appt.getPatientName() != null && !appt.getPatientName().isBlank()) ? appt.getPatientName() : "Patient";

        return String.format("""
                🔔 *Your appointment is in ~2 hours!*

                Hello %s! This is your 2-hour reminder.

                🏥 *Hospital:* %s
                👨‍⚕️ *Doctor:* %s
                🎫 *Token No.:* *#%02d*

                Please start heading to the hospital now. Type *queue* to check how many patients are ahead of you.
                Reply *CANCEL* if you cannot make it.
                """,
                patientName, hospital, docName, appt.getSerialNumber()
        );
    }

    private String buildFollowUpMessage(Appointment appt) {
        String docName = (appt.getDoctor() != null) ? formatDoctorName(appt.getDoctor().getName()) : "your doctor";
        String patientName = (appt.getPatientName() != null && !appt.getPatientName().isBlank()) ? appt.getPatientName() : "there";

        return String.format("""
                💙 *Post-Visit Check-in from MediAssist*

                Hi %s! It's been 2 days since your visit with %s.

                How are you feeling? Please let us know:
                """,
                patientName, docName
        );
    }

    /**
     * Checks if an appointment's timeSlot falls within 2 hours in IST timezone.
     */
    private boolean isWithinTwoHours(String timeSlot, ZonedDateTime nowIst) {
        if (timeSlot == null) return false;
        String lower = timeSlot.toLowerCase();
        int currentHour = nowIst.getHour();

        // If slot is morning (typically 09:00 - 13:00)
        if (lower.contains("morning") || lower.contains("1st half") || lower.contains("09:") || lower.contains("10:") || lower.contains("11:")) {
            return currentHour >= 7 && currentHour <= 10;
        }

        // If slot is evening (typically 17:00 - 20:00)
        if (lower.contains("evening") || lower.contains("2nd half") || lower.contains("05:") || lower.contains("06:") || lower.contains("07:") || lower.contains("17:") || lower.contains("18:")) {
            return currentHour >= 15 && currentHour <= 17;
        }

        // Afternoon (14:00 - 17:00)
        if (lower.contains("afternoon") || lower.contains("02:") || lower.contains("03:") || lower.contains("14:") || lower.contains("15:")) {
            return currentHour >= 12 && currentHour <= 14;
        }

        return false;
    }
}
