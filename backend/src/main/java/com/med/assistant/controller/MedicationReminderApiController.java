package com.med.assistant.controller;

import com.med.assistant.model.MedicationReminder;
import com.med.assistant.service.MedicationReminderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/reminders")
@CrossOrigin(origins = "*")
public class MedicationReminderApiController {

    private static final Logger logger = LoggerFactory.getLogger(MedicationReminderApiController.class);

    private final MedicationReminderService reminderService;

    public MedicationReminderApiController(MedicationReminderService reminderService) {
        this.reminderService = reminderService;
    }

    public record CreateReminderRequest(
            String patientPhone,
            String medicineName,
            String dosageInstruction,
            String reminderTime
    ) {}

    public record SnoozeRequest(
            List<Long> reminderIds,
            Integer minutes
    ) {}

    public record TakenRequest(
            List<Long> reminderIds
    ) {}

    public record TimeNodeGroup(
            String reminderTime,
            String periodLabel,
            int medicineCount,
            List<MedicationReminder> medications
    ) {}

    /**
     * Retrieve all active reminders for a patient phone number.
     */
    @GetMapping
    public ResponseEntity<List<MedicationReminder>> getActiveReminders(
            @RequestParam(defaultValue = "+919876543210") String phone) {
        return ResponseEntity.ok(reminderService.getActiveReminders(phone));
    }

    /**
     * Retrieve active reminders neatly grouped by Time Nodes (e.g. 08:00, 13:30, 20:00).
     */
    @GetMapping("/nodes")
    public ResponseEntity<List<TimeNodeGroup>> getTimeNodes(
            @RequestParam(defaultValue = "+919876543210") String phone) {
        List<MedicationReminder> all = reminderService.getActiveReminders(phone);

        // Group by reminderTime
        Map<String, List<MedicationReminder>> grouped = all.stream()
                .collect(Collectors.groupingBy(
                        r -> r.getReminderTime() != null ? r.getReminderTime() : "08:00",
                        TreeMap::new,
                        Collectors.toList()
                ));

        List<TimeNodeGroup> result = new ArrayList<>();
        for (Map.Entry<String, List<MedicationReminder>> entry : grouped.entrySet()) {
            String time = entry.getKey();
            String label = getPeriodLabel(time);
            result.add(new TimeNodeGroup(time, label, entry.getValue().size(), entry.getValue()));
        }

        return ResponseEntity.ok(result);
    }

    private String getPeriodLabel(String time) {
        try {
            int hour = Integer.parseInt(time.split(":")[0]);
            if (hour >= 5 && hour < 12) return "Morning";
            if (hour >= 12 && hour < 17) return "Afternoon";
            if (hour >= 17 && hour < 21) return "Evening";
            return "Night / Bedtime";
        } catch (Exception e) {
            return "Scheduled";
        }
    }

    /**
     * Create a new reminder node.
     */
    @PostMapping
    public ResponseEntity<MedicationReminder> createReminder(@RequestBody CreateReminderRequest req) {
        String phone = req.patientPhone() != null && !req.patientPhone().isBlank() ? req.patientPhone() : "+919876543210";
        String med = req.medicineName() != null && !req.medicineName().isBlank() ? req.medicineName() : "Prescribed Medicine";
        String dose = req.dosageInstruction() != null ? req.dosageInstruction() : "1 Dose";
        String time = req.reminderTime() != null && !req.reminderTime().isBlank() ? req.reminderTime() : "08:00";

        MedicationReminder created = reminderService.createReminder(phone, med, dose, time);
        return ResponseEntity.ok(created);
    }

    /**
     * Trigger alarm notification immediately on WhatsApp or for testing.
     */
    @PostMapping("/trigger")
    public ResponseEntity<Map<String, Object>> triggerAlarm(
            @RequestParam(defaultValue = "+919876543210") String phone,
            @RequestParam(required = false) Long reminderId,
            @RequestParam(required = false) String timeNode) {

        if (timeNode != null && !timeNode.isBlank()) {
            List<MedicationReminder> all = reminderService.getActiveReminders(phone);
            List<MedicationReminder> matching = all.stream()
                    .filter(r -> timeNode.equals(r.getReminderTime()))
                    .toList();
            if (!matching.isEmpty()) {
                reminderService.dispatchGroupedReminderAlert(phone, matching);
                return ResponseEntity.ok(Map.of(
                        "success", true,
                        "message", "Alarm triggered for time node: " + timeNode,
                        "medicationsDispatched", matching.size()
                ));
            }
        }

        boolean success = reminderService.triggerAlarmNow(phone, reminderId);
        return ResponseEntity.ok(Map.of(
                "success", success,
                "message", "Medication alarm successfully fired to " + phone
        ));
    }

    /**
     * Snooze reminder(s) for 15, 30, or custom minutes.
     */
    @PostMapping("/snooze")
    public ResponseEntity<Map<String, Object>> snoozeReminders(@RequestBody SnoozeRequest req) {
        int mins = req.minutes() != null && req.minutes() > 0 ? req.minutes() : 15;
        List<Long> ids = req.reminderIds() != null ? req.reminderIds() : Collections.emptyList();

        reminderService.markBatchAsSnoozed(ids, mins);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "snoozedCount", ids.size(),
                "snoozedMinutes", mins,
                "message", "Alarm snoozed for " + mins + " minutes."
        ));
    }

    /**
     * Mark reminder(s) as taken.
     */
    @PostMapping("/taken")
    public ResponseEntity<Map<String, Object>> markAsTaken(@RequestBody TakenRequest req) {
        List<Long> ids = req.reminderIds() != null ? req.reminderIds() : Collections.emptyList();
        reminderService.markBatchAsTaken(ids);
        return ResponseEntity.ok(Map.of(
                "success", true,
                "takenCount", ids.size(),
                "message", "Marked " + ids.size() + " medications as taken!"
        ));
    }

    /**
     * Delete a reminder node.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, Object>> deleteReminder(@PathVariable Long id) {
        boolean deleted = reminderService.deleteReminder(id);
        return ResponseEntity.ok(Map.of("success", deleted, "deletedId", id));
    }
}
