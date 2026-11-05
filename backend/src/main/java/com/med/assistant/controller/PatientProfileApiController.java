package com.med.assistant.controller;

import com.med.assistant.model.PatientProfile;
import com.med.assistant.service.PatientProfileService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.Optional;

/**
 * REST API for patient demographic profile management.
 * Used by the patient web portal (my-records.html).
 */
@RestController
@CrossOrigin(origins = "*")
@RequestMapping("/api/v1/patient/profile")
public class PatientProfileApiController {

    private final PatientProfileService profileService;

    public PatientProfileApiController(PatientProfileService profileService) {
        this.profileService = profileService;
    }

    @GetMapping("/{phone}")
    public ResponseEntity<?> getProfile(@PathVariable String phone) {
        Optional<PatientProfile> profile = profileService.getProfile(phone);
        return profile.<ResponseEntity<?>>map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.ok(Map.of("profileComplete", false, "message", "No profile found")));
    }

    @PostMapping("/{phone}")
    public ResponseEntity<?> updateProfile(@PathVariable String phone, @RequestBody Map<String, String> updates) {
        PatientProfile profile = profileService.getOrCreateProfile(phone);

        if (updates.containsKey("fullName")) profile.setFullName(updates.get("fullName"));
        if (updates.containsKey("gender")) profile.setGender(updates.get("gender"));
        if (updates.containsKey("bloodGroup")) profile.setBloodGroup(updates.get("bloodGroup"));
        if (updates.containsKey("city")) profile.setCity(updates.get("city"));
        if (updates.containsKey("email")) profile.setEmail(updates.get("email"));
        if (updates.containsKey("knownAllergies")) profile.setKnownAllergies(updates.get("knownAllergies"));
        if (updates.containsKey("chronicConditions")) profile.setChronicConditions(updates.get("chronicConditions"));
        if (updates.containsKey("emergencyContactName")) profile.setEmergencyContactName(updates.get("emergencyContactName"));
        if (updates.containsKey("emergencyContactPhone")) profile.setEmergencyContactPhone(updates.get("emergencyContactPhone"));

        profile.setUpdatedAt(java.time.LocalDateTime.now());
        // Mark complete if at minimum name + gender are set
        if (profile.getFullName() != null && profile.getGender() != null) {
            profile.setProfileComplete(true);
            profile.setOnboardingStep(6);
        }

        profileService.save(profile);
        return ResponseEntity.ok(Map.of("success", true, "message", "Profile updated successfully"));
    }
}
