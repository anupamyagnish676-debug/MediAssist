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

    public record AddDoctorRequest(String name, String department, String roomNumber, double consultationFee, int dailyTokenLimit, String availableTime) {}
    public record UpdateScheduleRequest(String roomNumber, double consultationFee, int dailyTokenLimit, String availableTime) {}
    public record UpdateHospitalSettingsRequest(String name, String address, String phone, String brandColor, String logoUrl) {}

    /**
     * Get details of the hospital assigned to this manager.
     */
    @GetMapping("/my-hospital")
    public ResponseEntity<Hospital> getMyHospital() {
        return ResponseEntity.ok(getManagerHospital());
    }

    /**
     * Update hospital settings (name, address, phone, brandColor, logoUrl).
     */
    @PutMapping("/settings")
    public ResponseEntity<?> updateSettings(@RequestBody UpdateHospitalSettingsRequest req) {
        Hospital h = getManagerHospital();
        if (req.name() != null && !req.name().isBlank()) h.setName(req.name().trim());
        if (req.address() != null) h.setAddress(req.address().trim());
        if (req.phone() != null) h.setPhone(req.phone().trim());
        if (req.brandColor() != null && !req.brandColor().isBlank()) h.setBrandColor(req.brandColor().trim());
        if (req.logoUrl() != null) h.setLogoUrl(req.logoUrl().trim());
        hospitalRepository.save(h);
        return ResponseEntity.ok(Map.of("success", true, "hospital", h));
    }

    /**
     * Upload medical hospital logo image file.
     */
    @PostMapping(value = "/settings/logo", consumes = org.springframework.http.MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> uploadLogo(@RequestParam("file") org.springframework.web.multipart.MultipartFile file) {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Uploaded file is empty"));
        }
        try {
            byte[] bytes = file.getBytes();
            String contentType = file.getContentType() != null ? file.getContentType() : "image/png";
            String base64 = "data:" + contentType + ";base64," + java.util.Base64.getEncoder().encodeToString(bytes);
            Hospital h = getManagerHospital();
            h.setLogoUrl(base64);
            hospitalRepository.save(h);
            return ResponseEntity.ok(Map.of("success", true, "logoUrl", base64));
        } catch (Exception e) {
            return ResponseEntity.status(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to upload logo: " + e.getMessage()));
        }
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
        if (req.availableTime() != null && !req.availableTime().isBlank()) doc.setAvailableTime(req.availableTime());
        return ResponseEntity.ok(doctorRepository.save(doc));
    }

    /**
     * Delete a doctor belonging to this hospital.
     */
    @DeleteMapping("/doctors/{doctorId}")
    public ResponseEntity<?> deleteDoctor(@PathVariable Long doctorId) {
        Hospital h = getManagerHospital();
        Optional<Doctor> opt = doctorRepository.findById(doctorId);
        if (opt.isEmpty()) return ResponseEntity.notFound().build();

        Doctor doc = opt.get();
        if (!doc.getHospital().getId().equals(h.getId())) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of("message", "Access denied: Doctor does not belong to your hospital."));
        }

        doctorRepository.delete(doc);
        return ResponseEntity.ok(Map.of("success", true, "message", "Doctor deleted successfully."));
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
                "availableToday", doc.isAvailableToday(),
                "available", doc.isAvailableToday()
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
        if (req.availableTime() != null && !req.availableTime().isBlank()) doc.setAvailableTime(req.availableTime());

        doctorRepository.save(doc);
        return ResponseEntity.ok(doc);
    }

    /**
     * View today's appointments for this hospital with all fields mapped for frontend convenience.
     */
    @GetMapping("/today-appointments")
    public ResponseEntity<List<Map<String, Object>>> getTodayAppointments() {
        Hospital h = getManagerHospital();
        List<Appointment> list = appointmentRepository.findByHospitalIdAndAppointmentDate(h.getId(), LocalDate.now());
        List<Map<String, Object>> result = new ArrayList<>();

        for (Appointment a : list) {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", a.getId());
            map.put("tokenNumber", a.getSerialNumber());
            map.put("serialNumber", a.getSerialNumber());
            map.put("patientPhone", a.getPatientPhone());
            map.put("patientName", a.getPatientName());
            map.put("doctorName", a.getDoctor() != null ? a.getDoctor().getName() : "N/A");
            map.put("department", a.getDoctor() != null ? a.getDoctor().getDepartment() : "General");
            map.put("timeSlot", a.getTimeSlot());
            map.put("appointmentTime", a.getTimeSlot());
            map.put("status", a.getStatus().name());
            map.put("qrToken", a.getQrCodeToken());
            map.put("qrCodeToken", a.getQrCodeToken());
            result.add(map);
        }

        return ResponseEntity.ok(result);
    }
}
