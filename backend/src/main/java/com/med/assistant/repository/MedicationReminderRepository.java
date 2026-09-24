package com.med.assistant.repository;

import com.med.assistant.model.MedicationReminder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MedicationReminderRepository extends JpaRepository<MedicationReminder, Long> {
    List<MedicationReminder> findByPatientPhoneAndActiveTrue(String patientPhone);
    List<MedicationReminder> findByActiveTrue();
}
