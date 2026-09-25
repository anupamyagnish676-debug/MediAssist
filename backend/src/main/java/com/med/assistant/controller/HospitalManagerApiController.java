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
        return hospitalRepository.findById(manager.getHospital().getId())
                .orElseThrow(() -> new IllegalStateException("Hospital not found"));
    }

    public record AddDoctorRequest(
            String name,
            String department,
            String qualification,
            String roomNumber,
            double consultationFee,
            int dailyTokenLimit,
            String availableDays,
            String firstHalfTime,
            String secondHalfTime,
            int firstHalfLimit,
            int secondHalfLimit,
            int consultationDurationMinutes,
            String availableTime
    ) {}

    public record UpdateScheduleRequest(
            String roomNumber,
            double consultationFee,
            int dailyTokenLimit,
            String qualification,
            String availableDays,
            String firstHalfTime,
            String secondHalfTime,
            int firstHalfLimit,
            int secondHalfLimit,
            int consultationDurationMinutes,
            String availableTime
    ) {}
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
        if (req.logoUrl() != null && !req.logoUrl().isBlank()) {
            h.setLogoUrl(compressLogoIfBase64(req.logoUrl().trim()));
        }
        h = hospitalRepository.saveAndFlush(h);
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
            String compressed = compressLogoIfBase64(base64);

            Hospital h = getManagerHospital();
            h.setLogoUrl(compressed);
            h = hospitalRepository.saveAndFlush(h);
            return ResponseEntity.ok(Map.of("success", true, "logoUrl", compressed));
        } catch (Exception e) {
            return ResponseEntity.status(org.springframework.http.HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Failed to upload logo: " + e.getMessage()));
        }
    }

    private String compressLogoIfBase64(String logoUrl) {
        if (logoUrl == null || !logoUrl.startsWith("data:image")) return logoUrl;
        try {
            int commaIdx = logoUrl.indexOf(",");
            if (commaIdx < 0) return logoUrl;
            String base64Data = logoUrl.substring(commaIdx + 1);
            byte[] bytes = java.util.Base64.getDecoder().decode(base64Data);
            java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(bytes));
            if (img == null) return logoUrl;

            int maxDim = 300;
            int w = img.getWidth();
            int h = img.getHeight();
            if (w > maxDim || h > maxDim) {
                double scale = Math.min((double) maxDim / w, (double) maxDim / h);
                int targetW = Math.max(1, (int) (w * scale));
                int targetH = Math.max(1, (int) (h * scale));
                java.awt.image.BufferedImage scaled = new java.awt.image.BufferedImage(targetW, targetH, java.awt.image.BufferedImage.TYPE_INT_ARGB);
                java.awt.Graphics2D g2 = scaled.createGraphics();
                g2.setRenderingHint(java.awt.RenderingHints.KEY_INTERPOLATION, java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g2.drawImage(img, 0, 0, targetW, targetH, null);
                g2.dispose();
                img = scaled;
            }

            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            javax.imageio.ImageIO.write(img, "png", baos);
            return "data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(baos.toByteArray());
        } catch (Exception e) {
            return logoUrl;
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
        int totalLimit = req.dailyTokenLimit();
        if (totalLimit <= 0) {
            totalLimit = (req.firstHalfLimit() > 0 ? req.firstHalfLimit() : 15) + (req.secondHalfLimit() > 0 ? req.secondHalfLimit() : 15);
        }
        Doctor doc = new Doctor(req.name(), req.department(), h, totalLimit, req.consultationFee());
        doc.setRoomNumber(req.roomNumber() != null ? req.roomNumber() : "101");
        if (req.qualification() != null && !req.qualification().isBlank()) doc.setQualification(req.qualification().trim());
        if (req.availableDays() != null && !req.availableDays().isBlank()) doc.setAvailableDays(req.availableDays().trim());
        if (req.firstHalfTime() != null && !req.firstHalfTime().isBlank()) doc.setFirstHalfTime(req.firstHalfTime().trim());
        if (req.secondHalfTime() != null && !req.secondHalfTime().isBlank()) doc.setSecondHalfTime(req.secondHalfTime().trim());
        if (req.firstHalfLimit() > 0) doc.setFirstHalfLimit(req.firstHalfLimit());
        if (req.secondHalfLimit() > 0) doc.setSecondHalfLimit(req.secondHalfLimit());
        if (req.consultationDurationMinutes() > 0) doc.setConsultationDurationMinutes(req.consultationDurationMinutes());
        if (req.availableTime() != null && !req.availableTime().isBlank()) doc.setAvailableTime(req.availableTime().trim());
        else doc.refreshCombinedAvailableTime();

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
        if (req.qualification() != null && !req.qualification().isBlank()) doc.setQualification(req.qualification().trim());
        if (req.availableDays() != null && !req.availableDays().isBlank()) doc.setAvailableDays(req.availableDays().trim());
        if (req.firstHalfTime() != null && !req.firstHalfTime().isBlank()) doc.setFirstHalfTime(req.firstHalfTime().trim());
        if (req.secondHalfTime() != null && !req.secondHalfTime().isBlank()) doc.setSecondHalfTime(req.secondHalfTime().trim());
        if (req.firstHalfLimit() > 0) doc.setFirstHalfLimit(req.firstHalfLimit());
        if (req.secondHalfLimit() > 0) doc.setSecondHalfLimit(req.secondHalfLimit());
        if (req.consultationDurationMinutes() > 0) doc.setConsultationDurationMinutes(req.consultationDurationMinutes());
        if (req.availableTime() != null && !req.availableTime().isBlank()) doc.setAvailableTime(req.availableTime().trim());
        else doc.refreshCombinedAvailableTime();

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
