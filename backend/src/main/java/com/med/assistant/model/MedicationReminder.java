package com.med.assistant.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "medication_reminders")
public class MedicationReminder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String patientPhone;

    @Column(nullable = false)
    private String medicineName; // e.g. Metformin 500mg

    private String dosageInstruction; // e.g. 1 Tablet post-dinner
    private String reminderTime; // e.g. "21:00"

    @Enumerated(EnumType.STRING)
    private AdherenceStatus status = AdherenceStatus.PENDING;

    private boolean active = true;
    private LocalDateTime lastAlertSent;
    private LocalDateTime lastTakenAt;
    private LocalDateTime snoozeUntil;
    private int snoozeCount = 0;

    public enum AdherenceStatus {
        PENDING,
        TAKEN,
        SNOOZED,
        MISSED
    }

    public MedicationReminder() {}

    public MedicationReminder(String patientPhone, String medicineName, String dosageInstruction, String reminderTime) {
        this.patientPhone = patientPhone;
        this.medicineName = medicineName;
        this.dosageInstruction = dosageInstruction;
        this.reminderTime = reminderTime;
        this.status = AdherenceStatus.PENDING;
        this.active = true;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getPatientPhone() { return patientPhone; }
    public void setPatientPhone(String patientPhone) { this.patientPhone = patientPhone; }

    public String getMedicineName() { return medicineName; }
    public void setMedicineName(String medicineName) { this.medicineName = medicineName; }

    public String getDosageInstruction() { return dosageInstruction; }
    public void setDosageInstruction(String dosageInstruction) { this.dosageInstruction = dosageInstruction; }

    public String getReminderTime() { return reminderTime; }
    public void setReminderTime(String reminderTime) { this.reminderTime = reminderTime; }

    public AdherenceStatus getStatus() { return status; }
    public void setStatus(AdherenceStatus status) { this.status = status; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    public LocalDateTime getLastAlertSent() { return lastAlertSent; }
    public void setLastAlertSent(LocalDateTime lastAlertSent) { this.lastAlertSent = lastAlertSent; }

    public LocalDateTime getLastTakenAt() { return lastTakenAt; }
    public void setLastTakenAt(LocalDateTime lastTakenAt) { this.lastTakenAt = lastTakenAt; }

    public LocalDateTime getSnoozeUntil() { return snoozeUntil; }
    public void setSnoozeUntil(LocalDateTime snoozeUntil) { this.snoozeUntil = snoozeUntil; }

    public int getSnoozeCount() { return snoozeCount; }
    public void setSnoozeCount(int snoozeCount) { this.snoozeCount = snoozeCount; }
}
