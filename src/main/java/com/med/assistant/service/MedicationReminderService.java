package com.med.assistant.service;

import com.med.assistant.model.MedicationReminder;
import com.med.assistant.repository.MedicationReminderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

@Service
public class MedicationReminderService {

    private static final Logger logger = LoggerFactory.getLogger(MedicationReminderService.class);

    private final MedicationReminderRepository reminderRepository;
    private final WhatsAppClientService whatsAppClient;

    public MedicationReminderService(MedicationReminderRepository reminderRepository,
                                     WhatsAppClientService whatsAppClient) {
        this.reminderRepository = reminderRepository;
        this.whatsAppClient = whatsAppClient;
    }

    public MedicationReminder createReminder(String patientPhone, String medicineName, String dosage, String time) {
        MedicationReminder reminder = new MedicationReminder(patientPhone, medicineName, dosage, time);
        return reminderRepository.save(reminder);
    }

    public boolean markAsTaken(Long reminderId) {
        Optional<MedicationReminder> opt = reminderRepository.findById(reminderId);
        if (opt.isPresent()) {
            MedicationReminder reminder = opt.get();
            reminder.setStatus(MedicationReminder.AdherenceStatus.TAKEN);
            reminder.setLastTakenAt(LocalDateTime.now());
            reminderRepository.save(reminder);
            return true;
        }
        return false;
    }

    public boolean markAsSnoozed(Long reminderId) {
        Optional<MedicationReminder> opt = reminderRepository.findById(reminderId);
        if (opt.isPresent()) {
            MedicationReminder reminder = opt.get();
            reminder.setStatus(MedicationReminder.AdherenceStatus.SNOOZED);
            reminder.setLastAlertSent(LocalDateTime.now());
            reminderRepository.save(reminder);
            return true;
        }
        return false;
    }

    public List<MedicationReminder> getActiveReminders(String patientPhone) {
        return reminderRepository.findByPatientPhoneAndActiveTrue(patientPhone);
    }

    /**
     * Automated Background Dispatcher: Runs every minute to trigger WhatsApp alerts.
     */
    @Scheduled(cron = "0 * * * * *")
    public void checkAndSendReminders() {
        try {
            LocalTime nowIst = LocalTime.now(ZoneId.of("Asia/Kolkata"));
            String currentHHmm = nowIst.format(DateTimeFormatter.ofPattern("HH:mm"));

            List<MedicationReminder> activeList = reminderRepository.findByActiveTrue();
            for (MedicationReminder reminder : activeList) {
                boolean shouldSend = false;

                // Match exact scheduled time (HH:mm)
                if (reminder.getReminderTime() != null && reminder.getReminderTime().equals(currentHHmm)) {
                    if (reminder.getLastAlertSent() == null || reminder.getLastAlertSent().isBefore(LocalDateTime.now().minusMinutes(1))) {
                        shouldSend = true;
                    }
                }

                // Check 15-minute snooze
                if (reminder.getStatus() == MedicationReminder.AdherenceStatus.SNOOZED && reminder.getLastAlertSent() != null) {
                    if (reminder.getLastAlertSent().isBefore(LocalDateTime.now().minusMinutes(15))) {
                        shouldSend = true;
                    }
                }

                if (shouldSend) {
                    dispatchReminderAlert(reminder);
                }
            }
        } catch (Exception e) {
            logger.error("Error in medication reminder scheduler: {}", e.getMessage(), e);
        }
    }

    private void dispatchReminderAlert(MedicationReminder reminder) {
        logger.info("Sending medication alert for reminder ID: {} to {}", reminder.getId(), reminder.getPatientPhone());

        String dosage = (reminder.getDosageInstruction() != null && !reminder.getDosageInstruction().isBlank())
                ? " (" + reminder.getDosageInstruction() + ")" : "";

        String body = "⏰ *Medication Alert!* 💊\n\n" +
                "It is time to take your prescribed medicine:\n" +
                "👉 *" + reminder.getMedicineName() + "*" + dosage + "\n\n" +
                "Please confirm once taken:";

        List<WhatsAppClientService.ButtonOption> buttons = List.of(
                new WhatsAppClientService.ButtonOption("MED_TAKEN_" + reminder.getId(), "✅ Taken"),
                new WhatsAppClientService.ButtonOption("MED_SNOOZE_" + reminder.getId(), "⏰ Snooze 15m")
        );

        try {
            whatsAppClient.sendInteractiveButtons(reminder.getPatientPhone(), body, buttons);
            reminder.setLastAlertSent(LocalDateTime.now());
            reminder.setStatus(MedicationReminder.AdherenceStatus.PENDING);
            reminderRepository.save(reminder);
        } catch (Exception e) {
            logger.error("Failed to send WhatsApp reminder alert: {}", e.getMessage(), e);
        }
    }
}
