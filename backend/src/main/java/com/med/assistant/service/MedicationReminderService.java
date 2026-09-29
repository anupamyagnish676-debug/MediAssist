package com.med.assistant.service;

import com.med.assistant.model.MedicationReminder;
import com.med.assistant.repository.MedicationReminderRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
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
        Set<String> variants = getPhoneVariants(patientPhone);
        Map<Long, MedicationReminder> map = new LinkedHashMap<>();
        for (String p : variants) {
            for (MedicationReminder r : reminderRepository.findByPatientPhoneAndActiveTrue(p)) {
                map.put(r.getId(), r);
            }
        }
        return new ArrayList<>(map.values());
    }

    public boolean deleteReminder(Long id) {
        if (reminderRepository.existsById(id)) {
            reminderRepository.deleteById(id);
            return true;
        }
        return false;
    }

    public int cancelAllReminders(String patientPhone) {
        List<MedicationReminder> active = getActiveReminders(patientPhone);
        if (active.isEmpty()) return 0;
        for (MedicationReminder r : active) {
            r.setActive(false);
        }
        reminderRepository.saveAll(active);
        return active.size();
    }

    private Set<String> getPhoneVariants(String phone) {
        Set<String> variants = new LinkedHashSet<>();
        if (phone == null || phone.isBlank()) return variants;
        variants.add(phone.trim());
        String digits = phone.replaceAll("[^0-9]", "");
        if (!digits.isBlank()) {
            variants.add(digits);
            variants.add("+" + digits);
            if (digits.length() == 10) {
                variants.add("+91" + digits);
                variants.add("91" + digits);
            } else if (digits.length() == 12 && digits.startsWith("91")) {
                variants.add(digits.substring(2));
                variants.add("+" + digits.substring(2));
            }
        }
        return variants;
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

    public record AlarmDetail(
            Long id,
            String medicineName,
            String dosageInstruction,
            String reminderTime,
            int hour,
            int minute,
            String formattedTime12h,
            String androidAlarmIntentUrl,
            String status
    ) {}

    /**
     * Generates a standard RFC 5545 iCalendar (.ics) file with VALARM triggers
     * to import recurring audio alarms directly into Android Clock/Calendar & Apple Calendar.
     */
    public String generateIcsCalendar(String patientPhone) {
        List<MedicationReminder> reminders = getActiveReminders(patientPhone);
        StringBuilder ics = new StringBuilder();
        ics.append("BEGIN:VCALENDAR\r\n");
        ics.append("VERSION:2.0\r\n");
        ics.append("PRODID:-//MediAssist Healthcare//Prescription Medication Alarms v1.0//EN\r\n");
        ics.append("CALSCALE:GREGORIAN\r\n");
        ics.append("METHOD:PUBLISH\r\n");
        ics.append("X-WR-CALNAME:MediAssist Medication Alarms\r\n");
        ics.append("X-WR-TIMEZONE:Asia/Kolkata\r\n");

        LocalDate today = LocalDate.now();
        String datePrefix = today.format(DateTimeFormatter.ofPattern("yyyyMMdd"));

        for (MedicationReminder r : reminders) {
            String time = (r.getReminderTime() != null && !r.getReminderTime().isBlank()) ? r.getReminderTime() : "08:00";
            String[] timeParts = time.split(":");
            int hour = 8;
            int min = 0;
            try {
                hour = Integer.parseInt(timeParts[0]);
                min = timeParts.length > 1 ? Integer.parseInt(timeParts[1]) : 0;
            } catch (Exception ignored) {}

            String dtStart = String.format("%sT%02d%02d00", datePrefix, hour, min);
            String medName = r.getMedicineName() != null ? r.getMedicineName() : "Prescribed Medication";
            String dosage = r.getDosageInstruction() != null ? r.getDosageInstruction() : "As directed by doctor";

            ics.append("BEGIN:VEVENT\r\n");
            ics.append("UID:med-alarm-").append(r.getId()).append("-").append(System.currentTimeMillis()).append("@mediassist.com\r\n");
            ics.append("SUMMARY:💊 Take ").append(escapeIcs(medName)).append("\r\n");
            ics.append("DESCRIPTION:Dosage: ").append(escapeIcs(dosage)).append("\\nScheduled by MediAssist Vision Prescription-to-Alarm.\\nConfirm taken via WhatsApp.\r\n");
            ics.append("DTSTART:").append(dtStart).append("\r\n");
            ics.append("DURATION:PT10M\r\n");
            ics.append("RRULE:FREQ=DAILY\r\n");

            // High-priority audio alarm
            ics.append("BEGIN:VALARM\r\n");
            ics.append("TRIGGER:-PT0M\r\n");
            ics.append("ACTION:AUDIO\r\n");
            ics.append("ATTACH;VALUE=URI:Basso\r\n");
            ics.append("DESCRIPTION:⏰ MEDICATION ALARM: Time to take ").append(escapeIcs(medName)).append("\r\n");
            ics.append("END:VALARM\r\n");

            // Pop-up notification alert
            ics.append("BEGIN:VALARM\r\n");
            ics.append("TRIGGER:-PT0M\r\n");
            ics.append("ACTION:DISPLAY\r\n");
            ics.append("DESCRIPTION:⏰ MEDICATION ALARM: Time to take ").append(escapeIcs(medName)).append(" (").append(escapeIcs(dosage)).append(")\r\n");
            ics.append("END:VALARM\r\n");

            ics.append("END:VEVENT\r\n");
        }

        ics.append("END:VCALENDAR\r\n");
        return ics.toString();
    }

    private String escapeIcs(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace(";", "\\;")
                .replace(",", "\\,")
                .replace("\n", "\\n");
    }

    /**
     * Returns rich alarm data for mobile companion page including native Android clock intents.
     */
    public List<AlarmDetail> getAlarmDetails(String phone) {
        List<MedicationReminder> reminders = getActiveReminders(phone);
        List<AlarmDetail> details = new ArrayList<>();
        for (MedicationReminder r : reminders) {
            String time = (r.getReminderTime() != null && !r.getReminderTime().isBlank()) ? r.getReminderTime() : "08:00";
            String[] timeParts = time.split(":");
            int hour = 8;
            int min = 0;
            try {
                hour = Integer.parseInt(timeParts[0]);
                min = timeParts.length > 1 ? Integer.parseInt(timeParts[1]) : 0;
            } catch (Exception ignored) {}

            int displayHour = (hour == 0 || hour == 12) ? 12 : hour % 12;
            String ampm = hour < 12 ? "AM" : "PM";
            String formatted12h = String.format("%02d:%02d %s", displayHour, min, ampm);

            String msg = "Take " + (r.getMedicineName() != null ? r.getMedicineName() : "Medicine");
            String encodedMsg;
            try {
                encodedMsg = java.net.URLEncoder.encode(msg, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
            } catch (Exception e) {
                encodedMsg = "Medication";
            }

            String androidIntent = String.format(
                    "intent:#Intent;action=android.intent.action.SET_ALARM;S.android.intent.extra.MESSAGE=%s;i.android.intent.extra.HOUR=%d;i.android.intent.extra.MINUTES=%d;B.android.intent.extra.SKIP_UI=false;end",
                    encodedMsg, hour, min
            );

            details.add(new AlarmDetail(
                    r.getId(),
                    r.getMedicineName(),
                    r.getDosageInstruction(),
                    r.getReminderTime(),
                    hour,
                    min,
                    formatted12h,
                    androidIntent,
                    r.getStatus() != null ? r.getStatus().name() : "PENDING"
            ));
        }
        return details;
    }
}
