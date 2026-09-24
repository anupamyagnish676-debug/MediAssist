package com.med.assistant.repository;

import com.med.assistant.model.Appointment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

@Repository
public interface AppointmentRepository extends JpaRepository<Appointment, Long> {
    Optional<Appointment> findByQrCodeToken(String qrCodeToken);
    List<Appointment> findByPatientPhoneOrderByAppointmentDateDesc(String patientPhone);
    List<Appointment> findByDoctorIdAndAppointmentDateOrderBySerialNumberAsc(Long doctorId, LocalDate appointmentDate);
    List<Appointment> findByHospitalIdAndAppointmentDate(Long hospitalId, LocalDate appointmentDate);
    List<Appointment> findByHospitalId(Long hospitalId);
    int countByDoctorIdAndAppointmentDate(Long doctorId, LocalDate appointmentDate);
}
