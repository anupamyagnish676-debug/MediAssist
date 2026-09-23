package com.med.assistant.controller;

import com.med.assistant.model.Hospital;
import com.med.assistant.model.User;
import com.med.assistant.repository.AppointmentRepository;
import com.med.assistant.repository.DoctorRepository;
import com.med.assistant.repository.HospitalRepository;
import com.med.assistant.repository.UserRepository;
import com.med.assistant.service.EmailService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.*;

@RestController
@RequestMapping("/api/v1/admin")
@PreAuthorize("hasRole('SUPER_ADMIN')")
@CrossOrigin(origins = "*")
public class SuperAdminApiController {

    private static final Logger logger = LoggerFactory.getLogger(SuperAdminApiController.class);

    private final HospitalRepository hospitalRepository;
    private final DoctorRepository doctorRepository;
    private final UserRepository userRepository;
    private final AppointmentRepository appointmentRepository;
    private final PasswordEncoder passwordEncoder;
    private final EmailService emailService;

    public SuperAdminApiController(HospitalRepository hospitalRepository,
                                  DoctorRepository doctorRepository,
                                  UserRepository userRepository,
                                  AppointmentRepository appointmentRepository,
                                  PasswordEncoder passwordEncoder,
                                  EmailService emailService) {
        this.hospitalRepository = hospitalRepository;
        this.doctorRepository = doctorRepository;
        this.userRepository = userRepository;
        this.appointmentRepository = appointmentRepository;
        this.passwordEncoder = passwordEncoder;
        this.emailService = emailService;
    }

    public record CreateManagerRequest(String email, String password, String fullName) {}
    public record RejectRequest(String reason) {}

    // ==================== APPLICATIONS ====================

    /**
     * List all pending and recently reviewed applications.
     */
    @GetMapping("/applications")
    public ResponseEntity<List<Map<String, Object>>> getApplications() {
        List<Hospital> all = hospitalRepository.findAll();
        List<Map<String, Object>> result = new ArrayList<>();

        for (Hospital h : all) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", h.getId());
            map.put("name", h.getName());
            map.put("hospitalName", h.getName());
            map.put("registrationNumber", h.getRegistrationNumber());
            map.put("address", h.getAddress());
            map.put("city", h.getCity());
            map.put("state", h.getState());
            map.put("phone", h.getPhone());
            map.put("contactPersonName", h.getContactPersonName());
            map.put("contactPersonEmail", h.getContactPersonEmail());
            map.put("contactEmail", h.getContactPersonEmail());
            map.put("contactPersonPhone", h.getContactPersonPhone());
            map.put("specialties", h.getSpecialties());
            map.put("numberOfBeds", h.getNumberOfBeds());
            map.put("status", h.getStatus() != null ? h.getStatus().name() : "ACTIVE");
            map.put("appliedAt", h.getAppliedAt());
            map.put("reviewedAt", h.getReviewedAt());
            map.put("rejectionReason", h.getRejectionReason());
            map.put("applicationNote", h.getApplicationNote());
            result.add(map);
        }

        // Sort: PENDING_REVIEW or PENDING_APPROVAL first, then by appliedAt desc
        result.sort((a, b) -> {
            String statusA = (String) a.get("status");
            String statusB = (String) b.get("status");
            boolean isPendingA = "PENDING_REVIEW".equals(statusA) || "PENDING_APPROVAL".equals(statusA);
            boolean isPendingB = "PENDING_REVIEW".equals(statusB) || "PENDING_APPROVAL".equals(statusB);
            if (isPendingA && !isPendingB) return -1;
            if (!isPendingA && isPendingB) return 1;
            return 0;
        });

        return ResponseEntity.ok(result);
    }

    /**
     * Approve an application: create User account, email credentials, activate hospital.
     */
    @PostMapping("/applications/{id}/approve")
    public ResponseEntity<?> approveApplication(@PathVariable Long id) {
        Optional<Hospital> opt = hospitalRepository.findById(id);
        if (opt.isEmpty()) return ResponseEntity.notFound().build();

        Hospital hospital = opt.get();
        if (hospital.getStatus() != Hospital.Status.PENDING_REVIEW && hospital.getStatus() != Hospital.Status.PENDING_APPROVAL) {
            return ResponseEntity.badRequest().body(Map.of("message", "This application has already been reviewed."));
        }

        // Generate temporary password
        String tempPassword = generateTempPassword();

        // Create hospital manager user account
        String email = hospital.getContactPersonEmail();
        if (email == null || email.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "No contact email found on this application."));
        }

        if (userRepository.findByEmailIgnoreCase(email).isPresent()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", "Email already in use: " + email));
        }

        // Update hospital status
        hospital.setStatus(Hospital.Status.ACTIVE);
        hospital.setActive(true);
        hospital.setReviewedAt(LocalDateTime.now());
        hospitalRepository.save(hospital);

        // Create user
        User manager = new User(
            email,
            passwordEncoder.encode(tempPassword),
            hospital.getContactPersonName() != null ? hospital.getContactPersonName() : "Hospital Admin",
            User.Role.HOSPITAL_MANAGER,
            hospital
        );
        manager.setPhoneNumber(hospital.getContactPersonPhone());
        manager.setMustChangePassword(true);
        userRepository.save(manager);

        // Send credentials email
        try {
            emailService.sendCredentials(email, hospital.getName(),
                    manager.getFullName(), tempPassword);
        } catch (Exception e) {
            logger.error("Failed to send email but approval succeeded: {}", e.getMessage());
        }

        logger.info("Application approved: '{}' (ID: {}). Manager: {}", hospital.getName(), id, email);

        return ResponseEntity.ok(Map.of(
            "success", true,
            "message", "Hospital approved! Credentials sent to " + email,
            "hospitalId", hospital.getId(),
            "managerEmail", email,
            "tempPassword", tempPassword // Return so admin can share manually if email fails
        ));
    }

    /**
     * Reject an application with a reason.
     */
    @PostMapping("/applications/{id}/reject")
    public ResponseEntity<?> rejectApplication(@PathVariable Long id, @RequestBody(required = false) RejectRequest req) {
        Optional<Hospital> opt = hospitalRepository.findById(id);
        if (opt.isEmpty()) return ResponseEntity.notFound().build();

        Hospital hospital = opt.get();
        if (hospital.getStatus() != Hospital.Status.PENDING_REVIEW && hospital.getStatus() != Hospital.Status.PENDING_APPROVAL) {
            return ResponseEntity.badRequest().body(Map.of("message", "This application has already been reviewed."));
        }

        String reason = req != null && req.reason() != null ? req.reason() : "Application does not meet current requirements.";

        hospital.setStatus(Hospital.Status.REJECTED);
        hospital.setActive(false);
        hospital.setReviewedAt(LocalDateTime.now());
        hospital.setRejectionReason(reason);
        hospitalRepository.save(hospital);

        // Send rejection email
        try {
            emailService.sendRejection(
                hospital.getContactPersonEmail(),
                hospital.getName(),
                hospital.getContactPersonName(),
                reason
            );
        } catch (Exception e) {
            logger.error("Failed to send rejection email: {}", e.getMessage());
        }

        logger.info("Application rejected: '{}' (ID: {}). Reason: {}", hospital.getName(), id, reason);

        return ResponseEntity.ok(Map.of("success", true, "message", "Application rejected."));
    }

    // ==================== HOSPITALS ====================

    @GetMapping("/hospitals")
    public ResponseEntity<List<Map<String, Object>>> getAllHospitals() {
        List<Hospital> hospitals = hospitalRepository.findAll().stream()
                .filter(h -> h.getStatus() == Hospital.Status.ACTIVE || h.getStatus() == Hospital.Status.APPROVED || h.getStatus() == Hospital.Status.SUSPENDED)
                .toList();

        List<Map<String, Object>> result = new ArrayList<>();
        for (Hospital h : hospitals) {
            List<User> managers = userRepository.findByHospitalId(h.getId());
            long docCount = doctorRepository.findByHospitalId(h.getId()).size();

            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", h.getId());
            map.put("name", h.getName());
            map.put("address", h.getAddress());
            map.put("phone", h.getPhone());
            map.put("city", h.getCity());
            map.put("specialties", h.getSpecialties());
            map.put("status", h.getStatus() != null ? h.getStatus().name() : "ACTIVE");
            map.put("active", h.isActive());
            map.put("brandColor", h.getBrandColor());
            map.put("doctorCount", docCount);
            map.put("managers", managers.stream().map(m -> Map.of(
                    "id", m.getId(),
                    "email", m.getEmail(),
                    "fullName", m.getFullName() != null ? m.getFullName() : "",
                    "active", m.isActive()
            )).toList());

            result.add(map);
        }

        return ResponseEntity.ok(result);
    }

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

            return ResponseEntity.ok(Map.of("hospitalId", h.getId(), "status", h.getStatus().name(), "active", h.isActive()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("message", "Invalid status: " + status));
        }
    }

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

        // Send credentials email
        try {
            emailService.sendCredentials(req.email().trim(), hospital.getName(), req.fullName(), req.password());
        } catch (Exception e) {
            logger.error("Email send failed: {}", e.getMessage());
        }

        return ResponseEntity.ok(Map.of(
                "success", true,
                "managerId", manager.getId(),
                "email", manager.getEmail(),
                "hospitalName", hospital.getName()
        ));
    }

    // ==================== USERS ====================

    @GetMapping("/users")
    public ResponseEntity<List<Map<String, Object>>> getAllUsers() {
        List<User> users = userRepository.findAll();
        List<Map<String, Object>> result = users.stream().map(u -> {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", u.getId());
            map.put("email", u.getEmail());
            map.put("fullName", u.getFullName());
            map.put("name", u.getFullName());
            map.put("role", u.getRole().name());
            map.put("active", u.isActive());
            map.put("hospitalName", u.getHospital() != null ? u.getHospital().getName() : null);
            map.put("createdAt", u.getCreatedAt());
            map.put("lastLoginAt", u.getLastLoginAt());
            return map;
        }).toList();

        return ResponseEntity.ok(result);
    }

    // ==================== STATS ====================

    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getPlatformStats() {
        List<Hospital> all = hospitalRepository.findAll();
        long totalHospitals = all.size();
        long activeHospitals = all.stream().filter(h -> h.getStatus() == Hospital.Status.ACTIVE).count();
        long pendingApplications = all.stream().filter(h -> h.getStatus() == Hospital.Status.PENDING_REVIEW || h.getStatus() == Hospital.Status.PENDING_APPROVAL).count();
        long totalDoctors = doctorRepository.count();
        long totalAppointments = appointmentRepository.count();
        long totalManagers = userRepository.findByRole(User.Role.HOSPITAL_MANAGER).size();

        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalHospitals", totalHospitals);
        stats.put("activeHospitals", activeHospitals);
        stats.put("pendingApplications", pendingApplications);
        stats.put("totalDoctors", totalDoctors);
        stats.put("totalAppointments", totalAppointments);
        stats.put("totalManagers", totalManagers);

        return ResponseEntity.ok(stats);
    }

    // ==================== HELPERS ====================

    private String generateTempPassword() {
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghjkmnpqrstuvwxyz23456789";
        StringBuilder sb = new StringBuilder();
        Random random = new Random();
        for (int i = 0; i < 10; i++) {
            sb.append(chars.charAt(random.nextInt(chars.length())));
        }
        return sb.toString() + "!";
    }
}
