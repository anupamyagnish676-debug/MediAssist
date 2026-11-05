package com.med.assistant.model;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.Period;

/**
 * Persistent patient demographic profile linked to WhatsApp phone number.
 * Collected via conversational WhatsApp onboarding flow.
 * Used to enrich appointment slips, improve AI triage accuracy, and personalise care.
 */
@Entity
@Table(name = "patient_profiles")
public class PatientProfile {

    @Id
    @Column(name = "phone_number", nullable = false, unique = true)
    private String phoneNumber;

    @Column(name = "full_name")
    private String fullName;

    @Column(name = "date_of_birth")
    private LocalDate dateOfBirth;

    @Column(name = "gender") // MALE, FEMALE, OTHER
    private String gender;

    @Column(name = "blood_group") // A+, A-, B+, B-, O+, O-, AB+, AB-
    private String bloodGroup;

    @Column(name = "city")
    private String city;

    @Column(name = "email")
    private String email;

    @Column(name = "known_allergies", length = 512)
    private String knownAllergies;

    @Column(name = "chronic_conditions", length = 512)
    private String chronicConditions;

    @Column(name = "emergency_contact_name")
    private String emergencyContactName;

    @Column(name = "emergency_contact_phone")
    private String emergencyContactPhone;

    @Column(name = "profile_complete")
    private boolean profileComplete = false;

    @Column(name = "onboarding_step")
    private int onboardingStep = 0; // 0=not started, 1=name, 2=dob, 3=gender, 4=bloodgroup, 5=allergies, 6=done

    @Column(name = "created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at")
    private LocalDateTime updatedAt = LocalDateTime.now();

    public PatientProfile() {}

    public PatientProfile(String phoneNumber) {
        this.phoneNumber = phoneNumber;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    /** Calculates age from dateOfBirth. Returns null if not set. */
    public Integer getAge() {
        if (dateOfBirth == null) return null;
        return Period.between(dateOfBirth, LocalDate.now()).getYears();
    }

    /** Returns a formatted one-line profile summary for appointment slips. */
    public String getProfileSummary() {
        StringBuilder sb = new StringBuilder();
        if (getAge() != null) sb.append(getAge()).append("Y");
        if (gender != null) sb.append("/").append(gender.charAt(0));
        if (bloodGroup != null) sb.append(" | ").append(bloodGroup);
        if (knownAllergies != null && !knownAllergies.isBlank() && !knownAllergies.equalsIgnoreCase("none") && !knownAllergies.equalsIgnoreCase("skip"))
            sb.append(" | Allergy: ").append(knownAllergies);
        return sb.toString();
    }

    // Getters and Setters
    public String getPhoneNumber() { return phoneNumber; }
    public void setPhoneNumber(String phoneNumber) { this.phoneNumber = phoneNumber; }
    public String getFullName() { return fullName; }
    public void setFullName(String fullName) { this.fullName = fullName; }
    public LocalDate getDateOfBirth() { return dateOfBirth; }
    public void setDateOfBirth(LocalDate dateOfBirth) { this.dateOfBirth = dateOfBirth; }
    public String getGender() { return gender; }
    public void setGender(String gender) { this.gender = gender; }
    public String getBloodGroup() { return bloodGroup; }
    public void setBloodGroup(String bloodGroup) { this.bloodGroup = bloodGroup; }
    public String getCity() { return city; }
    public void setCity(String city) { this.city = city; }
    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }
    public String getKnownAllergies() { return knownAllergies; }
    public void setKnownAllergies(String knownAllergies) { this.knownAllergies = knownAllergies; }
    public String getChronicConditions() { return chronicConditions; }
    public void setChronicConditions(String chronicConditions) { this.chronicConditions = chronicConditions; }
    public String getEmergencyContactName() { return emergencyContactName; }
    public void setEmergencyContactName(String emergencyContactName) { this.emergencyContactName = emergencyContactName; }
    public String getEmergencyContactPhone() { return emergencyContactPhone; }
    public void setEmergencyContactPhone(String emergencyContactPhone) { this.emergencyContactPhone = emergencyContactPhone; }
    public boolean isProfileComplete() { return profileComplete; }
    public void setProfileComplete(boolean profileComplete) { this.profileComplete = profileComplete; }
    public int getOnboardingStep() { return onboardingStep; }
    public void setOnboardingStep(int onboardingStep) { this.onboardingStep = onboardingStep; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
