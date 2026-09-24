package com.med.assistant.controller;

import com.med.assistant.model.Hospital;
import com.med.assistant.repository.HospitalRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Public endpoint for hospital applications. No authentication required.
 */
@RestController
@RequestMapping("/api/v1/apply")
@CrossOrigin(origins = "*")
public class HospitalApplicationController {

    private static final Logger logger = LoggerFactory.getLogger(HospitalApplicationController.class);

    private final HospitalRepository hospitalRepository;

    public HospitalApplicationController(HospitalRepository hospitalRepository) {
        this.hospitalRepository = hospitalRepository;
    }

    public record ApplicationRequest(
        String name,
        String hospitalName,
        String registrationNumber,
        String address,
        String city,
        String state,
        String phone,
        String contactPersonName,
        String contactName,
        String contactPersonEmail,
        String contactEmail,
        String contactPersonPhone,
        String contactPhone,
        String specialties,
        Object numberOfBeds,
        Object latitude,
        Object longitude,
        String brandColor,
        String applicationNote,
        String notes
    ) {
        public String getEffectiveName() {
            if (name != null && !name.isBlank()) return name.trim();
            if (hospitalName != null && !hospitalName.isBlank()) return hospitalName.trim();
            return null;
        }

        public String getEffectiveEmail() {
            if (contactPersonEmail != null && !contactPersonEmail.isBlank()) return contactPersonEmail.trim().toLowerCase();
            if (contactEmail != null && !contactEmail.isBlank()) return contactEmail.trim().toLowerCase();
            return null;
        }

        public String getEffectiveContactName() {
            if (contactPersonName != null && !contactPersonName.isBlank()) return contactPersonName.trim();
            if (contactName != null && !contactName.isBlank()) return contactName.trim();
            return null;
        }

        public String getEffectiveContactPhone() {
            if (contactPersonPhone != null && !contactPersonPhone.isBlank()) return contactPersonPhone.trim();
            if (contactPhone != null && !contactPhone.isBlank()) return contactPhone.trim();
            return null;
        }

        public String getEffectiveNotes() {
            if (applicationNote != null && !applicationNote.isBlank()) return applicationNote.trim();
            if (notes != null && !notes.isBlank()) return notes.trim();
            return null;
        }

        public int parseBeds() {
            if (numberOfBeds == null) return 0;
            try { return Integer.parseInt(numberOfBeds.toString().trim()); } catch (Exception e) { return 0; }
        }

        public double parseLat() {
            if (latitude == null) return 0.0;
            try { return Double.parseDouble(latitude.toString().trim()); } catch (Exception e) { return 0.0; }
        }

        public double parseLng() {
            if (longitude == null) return 0.0;
            try { return Double.parseDouble(longitude.toString().trim()); } catch (Exception e) { return 0.0; }
        }
    }

    @PostMapping
    public ResponseEntity<?> submitApplication(@RequestBody ApplicationRequest req) {
        String hName = req.getEffectiveName();
        String cEmail = req.getEffectiveEmail();

        if (hName == null || hName.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "Hospital name is required."));
        }
        if (cEmail == null || cEmail.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "Contact person email is required."));
        }
        if (req.address() == null || req.address().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("message", "Hospital address is required."));
        }

        Hospital hospital = new Hospital();
        hospital.setName(hName);
        hospital.setRegistrationNumber(req.registrationNumber());
        hospital.setAddress(req.address().trim());
        hospital.setCity(req.city());
        hospital.setState(req.state());
        hospital.setPhone(req.phone());
        hospital.setContactPersonName(req.getEffectiveContactName());
        hospital.setContactPersonEmail(cEmail);
        hospital.setContactPersonPhone(req.getEffectiveContactPhone());
        hospital.setSpecialties(req.specialties());
        hospital.setNumberOfBeds(req.parseBeds());
        hospital.setLatitude(req.parseLat());
        hospital.setLongitude(req.parseLng());
        hospital.setBrandColor(req.brandColor() != null ? req.brandColor() : "#0066FF");
        hospital.setApplicationNote(req.getEffectiveNotes());
        hospital.setStatus(Hospital.Status.PENDING_REVIEW);
        hospital.setActive(false);
        hospital.setAppliedAt(LocalDateTime.now());

        try {
            hospitalRepository.save(hospital);
            logger.info("New hospital application submitted: '{}' by {} ({})",
                    hospital.getName(), hospital.getContactPersonName(), hospital.getContactPersonEmail());

            return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Application submitted successfully! You will receive an email once reviewed.",
                "referenceId", "HOSP-" + hospital.getId(),
                "id", hospital.getId()
            ));
        } catch (Exception e) {
            logger.error("Failed to save hospital application: {}", e.getMessage(), e);
            return ResponseEntity.status(500).body(Map.of(
                "success", false,
                "message", "Failed to save application: " + e.getMessage()
            ));
        }
    }
}
