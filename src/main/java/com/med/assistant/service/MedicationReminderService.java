package com.med.assistant.service;

import com.med.assistant.model.MedicationReminder;
import com.med.assistant.repository.MedicationReminderRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class MedicationReminderService {

    private final MedicationReminderRepository reminderRepository;

    public MedicationReminderService(MedicationReminderRepository reminderRepository) {
        this.reminderRepository = reminderRepository;
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
            reminderRepository.save(reminder);
            return true;
        }
        return false;
    }

    public List<MedicationReminder> getActiveReminders(String patientPhone) {
        return reminderRepository.findByPatientPhoneAndActiveTrue(patientPhone);
    }
}
