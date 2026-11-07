package com.med.assistant.service;

import com.med.assistant.model.PatientProfile;
import com.med.assistant.repository.PatientProfileRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

/**
 * Manages patient demographic profiles.
 * Drives the conversational WhatsApp onboarding flow step-by-step.
 */
@Service
public class PatientProfileService {

    private static final Logger logger = LoggerFactory.getLogger(PatientProfileService.class);
    private static final Set<String> VALID_BLOOD_GROUPS = Set.of("A+", "A-", "B+", "B-", "O+", "O-", "AB+", "AB-");

    private final PatientProfileRepository profileRepository;

    public PatientProfileService(PatientProfileRepository profileRepository) {
        this.profileRepository = profileRepository;
    }

    public Optional<PatientProfile> getProfile(String phone) {
        return profileRepository.findByPhoneNumber(phone);
    }

    public PatientProfile getOrCreateProfile(String phone) {
        return profileRepository.findByPhoneNumber(phone)
                .orElseGet(() -> {
                    PatientProfile p = new PatientProfile(phone);
                    return profileRepository.save(p);
                });
    }

    public PatientProfile save(PatientProfile profile) {
        return profileRepository.save(profile);
    }

    public boolean isProfileComplete(String phone) {
        return profileRepository.findByPhoneNumber(phone)
                .map(PatientProfile::isProfileComplete)
                .orElse(false);
    }

    public boolean isOnboardingInProgress(String phone) {
        return profileRepository.findByPhoneNumber(phone)
                .map(p -> p.getOnboardingStep() > 0 && !p.isProfileComplete())
                .orElse(false);
    }

    public void cancelOnboarding(String phone) {
        profileRepository.findByPhoneNumber(phone).ifPresent(p -> {
            p.setOnboardingStep(0);
            profileRepository.save(p);
        });
    }

    /**
     * Processes user input during the onboarding flow.
     * Returns the next question to ask, or null if onboarding is complete.
     */
    public String processOnboardingInput(String phone, String userInput) {
        PatientProfile profile = getOrCreateProfile(phone);
        int step = profile.getOnboardingStep();
        String input = userInput.trim();
        String lower = input.toLowerCase();

        switch (step) {
            case 1 -> { // Expecting: Full Name
                if (input.length() < 2) return "Please enter your full name (at least 2 characters):";
                profile.setFullName(sanitizeName(input));
                profile.setOnboardingStep(2);
                profileRepository.save(profile);
                return "📅 *Date of Birth*\n\nPlease enter your date of birth (e.g. *25 Mar 1990* or *1990-03-25* or *skip* to skip):";
            }
            case 2 -> { // Expecting: Date of Birth
                if (!lower.equals("skip")) {
                    LocalDate dob = parseDob(input);
                    if (dob == null) return "❌ Could not read that date. Please try again (e.g. *25 Mar 1990*) or reply *skip*:";
                    if (dob.isAfter(LocalDate.now().minusYears(1))) return "❌ Please enter a valid date of birth:";
                    profile.setDateOfBirth(dob);
                }
                profile.setOnboardingStep(3);
                profileRepository.save(profile);
                return "👤 *Gender*\n\nPlease reply:\n• *M* — Male\n• *F* — Female\n• *O* — Other\n• *skip* — Skip";
            }
            case 3 -> { // Expecting: Gender
                if (!lower.equals("skip")) {
                    if (lower.startsWith("m")) profile.setGender("MALE");
                    else if (lower.startsWith("f")) profile.setGender("FEMALE");
                    else if (lower.startsWith("o")) profile.setGender("OTHER");
                    else return "Please reply *M* (Male), *F* (Female), *O* (Other), or *skip*:";
                }
                profile.setOnboardingStep(4);
                profileRepository.save(profile);
                return "🩸 *Blood Group*\n\nPlease enter your blood group:\n*A+, A-, B+, B-, O+, O-, AB+, AB-*\nor reply *skip*:";
            }
            case 4 -> { // Expecting: Blood Group
                if (!lower.equals("skip")) {
                    String bg = input.toUpperCase().replace(" ", "");
                    if (!VALID_BLOOD_GROUPS.contains(bg)) return "❌ Invalid blood group. Please enter one of: A+, A-, B+, B-, O+, O-, AB+, AB-, or reply *skip*:";
                    profile.setBloodGroup(bg);
                }
                profile.setOnboardingStep(5);
                profileRepository.save(profile);
                return "⚕️ *Known Allergies*\n\nDo you have any known medication or food allergies?\n(e.g. *Penicillin, Sulfa drugs* or reply *none* or *skip*):";
            }
            case 5 -> { // Expecting: Allergies
                if (!lower.equals("skip") && !lower.equals("none") && !lower.isBlank()) {
                    profile.setKnownAllergies(input.substring(0, Math.min(input.length(), 200)));
                }
                // Complete onboarding
                profile.setOnboardingStep(6);
                profile.setProfileComplete(true);
                profile.setUpdatedAt(LocalDateTime.now());
                profileRepository.save(profile);
                return null; // Signal: onboarding done
            }
            default -> {
                return null;
            }
        }
    }

    /** Starts the onboarding flow. Returns the first question. */
    public String startOnboarding(String phone) {
        PatientProfile profile = getOrCreateProfile(phone);
        profile.setOnboardingStep(1);
        profileRepository.save(profile);
        return "👋 *Welcome to MediAssist!*\n\nTo provide you with better personalised healthcare, please complete your health profile. It takes just 1 minute!\n\n📝 *What is your full name?*\n(This will appear on your appointment slips)";
    }

    private LocalDate parseDob(String input) {
        String[] patterns = {"d MMM yyyy", "dd MMM yyyy", "d-MM-yyyy", "dd-MM-yyyy", "yyyy-MM-dd", "d/MM/yyyy", "dd/MM/yyyy"};
        for (String pattern : patterns) {
            try {
                return LocalDate.parse(input, DateTimeFormatter.ofPattern(pattern, Locale.ENGLISH));
            } catch (DateTimeParseException ignored) {}
        }
        return null;
    }

    private String sanitizeName(String name) {
        return name.replaceAll("[<>\"';&|]", "").trim().substring(0, Math.min(name.length(), 100));
    }
}
