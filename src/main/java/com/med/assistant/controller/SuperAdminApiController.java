package com.med.assistant.controller;

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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/v1/admin")
@PreAuthorize("hasRole('SUPER_ADMIN')")
@CrossOrigin(origins = "*")
public class SuperAdminApiController {

    private final HospitalRepository hospitalRepository;
    private final DoctorRepository doctorRepository;
    private final UserRepository userRepository;
    private final AppointmentRepository appointmentRepository;
    private final PasswordEncoder passwordEncoder;

    public SuperAdminApiController(HospitalRepository hospitalRepository,
                                  DoctorRepository doctorRepository,
                                  UserRepository userRepository,
                                  AppointmentRepository appointmentRepository,
                                  PasswordEncoder passwordEncoder) {
        this.hospitalRepository = hospitalRepository;
        this.doctorRepository = doctorRepository;
        this.userRepository = userRepository;
        this.appointmentRepository = appointmentRepository;
        this.passwordEncoder = passwordEncoder;
    }

    public record CreateManagerRequest(String email, String password, String fullName) {}

    /**
     * List all registered hospitals across the entire platform.
     */
    @GetMapping("/hospitals")
    public ResponseEntity<List<Map<String, Object>>> getAllHospitals() {
        List<Hospital> hospitals = hospitalRepository.findAll();
        List<Map<String, Object>> result = new ArrayList<>();

        for (Hospital h : hospitals) {
            List<User> managers = userRepository.findByHospitalId(h.getId());
            long docCount = doctorRepository.findByHospitalId(h.getId()).size();

            Map<String, Object> map = new HashMap<>();
            map.put("id", h.getId());
            map.put("name", h.getName());
            map.put("address", h.getAddress());
            map.put("phone", h.getPhone());
            map.put("status", h.getStatus() != null ? h.getStatus().name() : "ACTIVE");
            map.put("active", h.isActive());
            map.put("brandColor", h.getBrandColor());
            map.put("doctorCount", docCount);
            map.put("managers", managers.stream().map(m -> Map.of(
                    "id", m.getId(),
                    "email", m.getEmail(),
                    "fullName", m.getFullName(),
                    "active", m.isActive()
            )).toList());

            result.add(map);
        }

        return ResponseEntity.ok(result);
    }

    /**
     * Approve, Activate, or Suspend a hospital.
     */
    @PostMapping("/hospitals/{id}/status")
    public ResponseEntity<?> updateHospitalStatus(@PathVariable Long id, @RequestParam String status) {
        Optional<Hospital> opt = hospitalRepository.findById(id);
        if (opt.isEmpty()) return ResponseEntity.notFound().build();

        Hospital h = opt.get();
        try {
            Hospital.Status newStatus = Hospital.Status.valueOf(status.toUpperCase());
            h.setStatus(newStatus);
            h.setActive(newStatus == Hospital.Status.ACTIVE);
            hospitalRepository.save(h);

            return ResponseEntity.ok(Map.of(
                    "hospitalId", h.getId(),
                    "status", h.getStatus().name(),
                    "active", h.isActive()
            ));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", "Invalid status value: " + status));
        }
    }

    /**
     * Create a new Hospital Manager account assigned to a specific hospital.
     */
    @PostMapping("/hospitals/{id}/managers")
    public ResponseEntity<?> createHospitalManager(@PathVariable Long id, @RequestBody CreateManagerRequest req) {
        Optional<Hospital> opt = hospitalRepository.findById(id);
        if (opt.isEmpty()) return ResponseEntity.notFound().build();

        if (userRepository.findByEmailIgnoreCase(req.email().trim()).isPresent()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", "Email already in use."));
        }

        Hospital hospital = opt.get();
        User manager = new User(
                req.email().trim(),
                passwordEncoder.encode(req.password()),
                req.fullName(),
                User.Role.HOSPITAL_MANAGER,
                hospital
        );
        userRepository.save(manager);

        return ResponseEntity.ok(Map.of(
                "success", true,
                "managerId", manager.getId(),
                "email", manager.getEmail(),
                "hospitalId", hospital.getId(),
                "hospitalName", hospital.getName()
        ));
    }

    /**
     * Global Platform Stats for Super Admin.
     */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getPlatformStats() {
        long totalHospitals = hospitalRepository.count();
        long activeHospitals = hospitalRepository.findAll().stream().filter(h -> h.getStatus() == Hospital.Status.ACTIVE).count();
        long pendingHospitals = hospitalRepository.findAll().stream().filter(h -> h.getStatus() == Hospital.Status.PENDING_APPROVAL).count();
        long totalDoctors = doctorRepository.count();
        long totalAppointments = appointmentRepository.count();
        long totalManagers = userRepository.findByRole(User.Role.HOSPITAL_MANAGER).size();

        return ResponseEntity.ok(Map.of(
                "totalHospitals", totalHospitals,
                "activeHospitals", activeHospitals,
                "pendingHospitals", pendingHospitals,
                "totalDoctors", totalDoctors,
                "totalAppointments", totalAppointments,
                "totalManagers", totalManagers
        ));
    }
}
