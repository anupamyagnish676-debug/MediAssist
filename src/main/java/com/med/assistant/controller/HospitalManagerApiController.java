package com.med.assistant.controller;

import com.med.assistant.model.Appointment;
import com.med.assistant.model.Doctor;
import com.med.assistant.model.Hospital;
import com.med.assistant.model.User;
import com.med.assistant.repository.AppointmentRepository;
import com.med.assistant.repository.DoctorRepository;
import com.med.assistant.repository.HospitalRepository;
import com.med.assistant.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.*;

@RestController
@RequestMapping("/api/v1/hospital-manager")
@PreAuthorize("hasRole('HOSPITAL_MANAGER')")
@CrossOrigin(origins = "*")
public class HospitalManagerApiController {

    private final HospitalRepository hospitalRepository;
    private final DoctorRepository doctorRepository;
    private final AppointmentRepository appointmentRepository;
    private final UserRepository userRepository;

    public HospitalManagerApiController(HospitalRepository hospitalRepository,
                                       DoctorRepository doctorRepository,
                                       AppointmentRepository appointmentRepository,
                                       UserRepository userRepository) {
        this.hospitalRepository = hospitalRepository;
        this.doctorRepository = doctorRepository;
        this.appointmentRepository = appointmentRepository;
        this.userRepository = userRepository;
    }

    private User getAuthenticatedManager() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null) throw new IllegalStateException("Not authenticated");
        return userRepository.findByEmailIgnoreCase(auth.getName())
                .orElseThrow(() -> new IllegalStateException("User not found"));
    }

    private Hospital getManagerHospital() {
        User manager = getAuthenticatedManager();
        if (manager.getHospital() == null) {
            throw new IllegalStateException("Manager is not assigned to any hospital");
        }
        return manager.getHospital();
    }

    public record AddDoctorRequest(String name, String department, String roomNumber, double consultationFee, int dailyTokenLimit) {}
    public record UpdateScheduleRequest(String roomNumber, double consultationFee, int dailyTokenLimit) {}

    /**
     * Get details of the hospital assigned to this manager.
     */
    @GetMapping("/my-hospital")
    public ResponseEntity<Hospital> getMyHospital() {
        return ResponseEntity.ok(getManagerHospital());
    }

    /**
     * List doctors belonging ONLY to this manager's hospital.
     */
    @GetMapping("/doctors")
    public ResponseEntity<List<Doctor>> getMyDoctors() {
        Hospital h = getManagerHospital();
        return ResponseEntity.ok(doctorRepository.findByHospitalId(h.getId()));
    }

    /**
     * Add a doctor to this manager's hospital.
     */
    @PostMapping("/doctors")
    public ResponseEntity<Doctor> addDoctor(@RequestBody AddDoctorRequest req) {
        Hospital h = getManagerHospital();
        Doctor doc = new Doctor(req.name(), req.department(), h,
                req.dailyTokenLimit() > 0 ? req.dailyTokenLimit() : 25, req.consultationFee());
        doc.setRoomNumber(req.roomNumber() != null ? req.roomNumber() : "101");
        return ResponseEntity.ok(doctorRepository.save(doc));
    }

    /**
     * Toggle doctor availability (Available Today / On Leave) with strict hospital ownership check.
     */
    @PostMapping("/doctors/{doctorId}/availability")
    public ResponseEntity<?> toggleDoctorAvailability(@PathVariable Long doctorId) {
        Hospital h = getManagerHospital();
        Doctor doc = doctorRepository.findById(doctorId).orElseThrow();

        // Enforce hospital boundary
        if (!doc.getHospital().getId().equals(h.getId())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", "Access denied: Doctor does not belong to your hospital."));
        }

        doc.setAvailableToday(!doc.isAvailableToday());
        doctorRepository.save(doc);

        return ResponseEntity.ok(Map.of(
                "doctorId", doc.getId(),
                "name", doc.getName(),
                "availableToday", doc.isAvailableToday()
        ));
    }

    /**
     * Update doctor OPD room, timetable, fees, and token limits.
     */
    @PostMapping("/doctors/{doctorId}/schedule")
    public ResponseEntity<?> updateDoctorSchedule(@PathVariable Long doctorId, @RequestBody UpdateScheduleRequest req) {
        Hospital h = getManagerHospital();
        Doctor doc = doctorRepository.findById(doctorId).orElseThrow();

        if (!doc.getHospital().getId().equals(h.getId())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", "Access denied: Doctor does not belong to your hospital."));
        }

        if (req.roomNumber() != null && !req.roomNumber().isBlank()) doc.setRoomNumber(req.roomNumber());
        if (req.consultationFee() >= 0) doc.setConsultationFee(req.consultationFee());
        if (req.dailyTokenLimit() > 0) doc.setDailyTokenLimit(req.dailyTokenLimit());

        doctorRepository.save(doc);
        return ResponseEntity.ok(doc);
    }

    /**
     * View today's appointments for this hospital.
     */
    @GetMapping("/today-appointments")
    public ResponseEntity<List<Appointment>> getTodayAppointments() {
        Hospital h = getManagerHospital();
        return ResponseEntity.ok(appointmentRepository.findByHospitalIdAndAppointmentDate(h.getId(), LocalDate.now()));
    }
}
