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
import java.util.*;
import java.util.stream.Collectors;

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
            reminder.setSnoozeUntil(null);
            reminderRepository.save(reminder);
            return true;
        }
        return false;
    }

    public void markBatchAsTaken(List<Long> reminderIds) {
        for (Long id : reminderIds) {
            markAsTaken(id);
        }
    }

    public boolean markAsSnoozed(Long reminderId, int minutes) {
        Optional<MedicationReminder> opt = reminderRepository.findById(reminderId);
        if (opt.isPresent()) {
            MedicationReminder reminder = opt.get();
            reminder.setStatus(MedicationReminder.AdherenceStatus.SNOOZED);
            reminder.setLastAlertSent(LocalDateTime.now());
            reminder.setSnoozeUntil(LocalDateTime.now().plusMinutes(minutes));
            reminder.setSnoozeCount(reminder.getSnoozeCount() + 1);
            reminderRepository.save(reminder);
            return true;
        }
        return false;
    }

    public void markBatchAsSnoozed(List<Long> reminderIds, int minutes) {
        for (Long id : reminderIds) {
            markAsSnoozed(id, minutes);
        }
    }

    public List<MedicationReminder> getActiveReminders(String patientPhone) {
        return reminderRepository.findByPatientPhoneAndActiveTrue(patientPhone);
    }

    public boolean deleteReminder(Long id) {
        if (reminderRepository.existsById(id)) {
            reminderRepository.deleteById(id);
            return true;
        }
        return false;
    }

    /**
     * Automated Background Dispatcher: Runs every minute to trigger WhatsApp alerts.
     * Groups multiple medications scheduled for the exact same time into a single unified alert!
     */
    @Scheduled(cron = "0 * * * * *")
    public void checkAndSendReminders() {
        try {
            LocalTime nowIst = LocalTime.now(ZoneId.of("Asia/Kolkata"));
            String currentHHmm = nowIst.format(DateTimeFormatter.ofPattern("HH:mm"));
            LocalDateTime now = LocalDateTime.now();

            List<MedicationReminder> activeList = reminderRepository.findByActiveTrue();

            // Group by patientPhone
            Map<String, List<MedicationReminder>> byPhone = activeList.stream()
                    .collect(Collectors.groupingBy(MedicationReminder::getPatientPhone));

            for (Map.Entry<String, List<MedicationReminder>> entry : byPhone.entrySet()) {
                String phone = entry.getKey();
                List<MedicationReminder> patientReminders = entry.getValue();

                // Check due reminders
                List<MedicationReminder> dueNow = new ArrayList<>();
                for (MedicationReminder reminder : patientReminders) {
                    boolean shouldSend = false;

                    // 1. Regular scheduled time (HH:mm)
                    if (reminder.getReminderTime() != null && reminder.getReminderTime().equals(currentHHmm)) {
                        if (reminder.getLastAlertSent() == null || reminder.getLastAlertSent().isBefore(now.minusMinutes(1))) {
                            shouldSend = true;
                        }
                    }

                    // 2. Snoozed reminder whose snooze time has arrived
                    if (reminder.getStatus() == MedicationReminder.AdherenceStatus.SNOOZED) {
                        if (reminder.getSnoozeUntil() != null && now.isAfter(reminder.getSnoozeUntil())) {
                            shouldSend = true;
                        } else if (reminder.getLastAlertSent() != null && reminder.getLastAlertSent().isBefore(now.minusMinutes(15))) {
                            shouldSend = true;
                        }
                    }

                    if (shouldSend) {
                        dueNow.add(reminder);
                    }
                }

                if (!dueNow.isEmpty()) {
                    dispatchGroupedReminderAlert(phone, dueNow);
                }
            }
        } catch (Exception e) {
            logger.error("Error in medication reminder scheduler: {}", e.getMessage(), e);
        }
    }

    /**
     * Dispatches a single or multi-medication interactive alarm card with snooze options.
     */
    public void dispatchGroupedReminderAlert(String phone, List<MedicationReminder> reminders) {
        if (reminders == null || reminders.isEmpty()) return;

        LocalDateTime now = LocalDateTime.now();
        String timeStr = reminders.get(0).getReminderTime() != null ? reminders.get(0).getReminderTime() : "Now";

        StringBuilder body = new StringBuilder();
        body.append("⏰ *MEDICATION ALARM (").append(timeStr).append(" IST)* 💊\n\n");
        body.append("It is time to take your prescribed medicine:\n\n");

        List<String> ids = new ArrayList<>();
        for (int i = 0; i < reminders.size(); i++) {
            MedicationReminder r = reminders.get(i);
            ids.add(String.valueOf(r.getId()));
            String dosage = (r.getDosageInstruction() != null && !r.getDosageInstruction().isBlank())
                    ? " — " + r.getDosageInstruction() : "";
            body.append((i + 1)).append(". 💊 *").append(r.getMedicineName()).append("*").append(dosage).append("\n");
        }

        body.append("\n👉 Please confirm once taken or choose a snooze option:");

        String idPayload = String.join("_", ids);

        List<WhatsAppClientService.ButtonOption> buttons = List.of(
                new WhatsAppClientService.ButtonOption("MED_TAKEN_" + idPayload, "✅ Taken"),
                new WhatsAppClientService.ButtonOption("MED_SNOOZE_15_" + idPayload, "⏰ Snooze 15m"),
                new WhatsAppClientService.ButtonOption("MED_SNOOZE_30_" + idPayload, "⏰ Snooze 30m")
        );

        try {
            whatsAppClient.sendInteractiveButtons(phone, body.toString(), buttons);
            for (MedicationReminder r : reminders) {
                r.setLastAlertSent(now);
                r.setStatus(MedicationReminder.AdherenceStatus.PENDING);
                reminderRepository.save(r);
            }
            logger.info("Successfully dispatched interactive medication alarm for {} medicines to {}", reminders.size(), phone);
        } catch (Exception e) {
            logger.error("Failed to send WhatsApp reminder alert: {}", e.getMessage(), e);
        }
    }

    /**
     * Trigger alarm on-demand for testing / simulation.
     */
    public boolean triggerAlarmNow(String phone, Long reminderId) {
        if (reminderId != null) {
            Optional<MedicationReminder> opt = reminderRepository.findById(reminderId);
            if (opt.isPresent()) {
                dispatchGroupedReminderAlert(phone, List.of(opt.get()));
                return true;
            }
        }
        List<MedicationReminder> list = getActiveReminders(phone);
        if (!list.isEmpty()) {
            dispatchGroupedReminderAlert(phone, list);
            return true;
        }
        // If none exist, create a sample one and fire
        MedicationReminder sample = createReminder(phone, "Cap. Rozad", "1 OD AC 7 AM", "07:00");
        dispatchGroupedReminderAlert(phone, List.of(sample));
        return true;
    }
}
