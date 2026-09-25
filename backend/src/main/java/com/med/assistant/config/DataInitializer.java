package com.med.assistant.config;

import com.med.assistant.model.Appointment;
import com.med.assistant.model.Doctor;
import com.med.assistant.model.Hospital;
import com.med.assistant.model.MedicationReminder;
import com.med.assistant.model.User;
import com.med.assistant.repository.AppointmentRepository;
import com.med.assistant.repository.DoctorRepository;
import com.med.assistant.repository.HospitalRepository;
import com.med.assistant.repository.MedicationReminderRepository;
import com.med.assistant.repository.UserRepository;
import com.med.assistant.service.AppointmentSlipPdfService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.LocalDate;
import java.util.UUID;

@Configuration
public class DataInitializer {

    private static final Logger logger = LoggerFactory.getLogger(DataInitializer.class);

    @org.springframework.beans.factory.annotation.Value("${app.seed-demo-data:false}")
    private boolean seedDemoData;

    @Bean
    public CommandLineRunner initDatabase(HospitalRepository hospitalRepo,
                                          DoctorRepository doctorRepo,
                                          AppointmentRepository apptRepo,
                                          MedicationReminderRepository reminderRepo,
                                          UserRepository userRepo,
                                          org.springframework.security.crypto.password.PasswordEncoder passwordEncoder,
                                          AppointmentSlipPdfService pdfService,
                                          org.springframework.jdbc.core.JdbcTemplate jdbcTemplate) {
        return args -> {
            try {
                // Ensure schema columns exist across all tables (self-healing for PostgreSQL / Supabase)
                try {
                    // medication_reminders
                    jdbcTemplate.execute("ALTER TABLE medication_reminders ADD COLUMN IF NOT EXISTS snooze_count INTEGER DEFAULT 0");
                    jdbcTemplate.execute("ALTER TABLE medication_reminders ADD COLUMN IF NOT EXISTS snooze_until TIMESTAMP");

                    // doctors
                    jdbcTemplate.execute("ALTER TABLE doctors ADD COLUMN IF NOT EXISTS consultation_duration_minutes INTEGER DEFAULT 15");
                    jdbcTemplate.execute("ALTER TABLE doctors ADD COLUMN IF NOT EXISTS qualification VARCHAR(255) DEFAULT 'MBBS, MD'");
                    jdbcTemplate.execute("ALTER TABLE doctors ADD COLUMN IF NOT EXISTS available_days VARCHAR(255) DEFAULT 'MON,TUE,WED,THU,FRI,SAT'");
                    jdbcTemplate.execute("ALTER TABLE doctors ADD COLUMN IF NOT EXISTS first_half_time VARCHAR(255) DEFAULT '09:00 AM - 01:00 PM'");
                    jdbcTemplate.execute("ALTER TABLE doctors ADD COLUMN IF NOT EXISTS second_half_time VARCHAR(255) DEFAULT '05:00 PM - 09:00 PM'");
                    jdbcTemplate.execute("ALTER TABLE doctors ADD COLUMN IF NOT EXISTS first_half_limit INTEGER DEFAULT 15");
                    jdbcTemplate.execute("ALTER TABLE doctors ADD COLUMN IF NOT EXISTS second_half_limit INTEGER DEFAULT 15");
                    jdbcTemplate.execute("ALTER TABLE doctors ADD COLUMN IF NOT EXISTS available_time VARCHAR(255)");
                    jdbcTemplate.execute("ALTER TABLE doctors ADD COLUMN IF NOT EXISTS rating DOUBLE PRECISION DEFAULT 4.9");
                    jdbcTemplate.execute("ALTER TABLE doctors ADD COLUMN IF NOT EXISTS total_reviews INTEGER DEFAULT 42");
                    jdbcTemplate.execute("ALTER TABLE doctors ADD COLUMN IF NOT EXISTS room_number VARCHAR(255) DEFAULT '101'");
                    jdbcTemplate.execute("ALTER TABLE doctors ADD COLUMN IF NOT EXISTS consultation_fee DOUBLE PRECISION DEFAULT 500.0");
                    jdbcTemplate.execute("ALTER TABLE doctors ADD COLUMN IF NOT EXISTS available_today BOOLEAN DEFAULT TRUE");
                    jdbcTemplate.execute("ALTER TABLE doctors ADD COLUMN IF NOT EXISTS current_token_count INTEGER DEFAULT 0");
                    jdbcTemplate.execute("ALTER TABLE doctors ADD COLUMN IF NOT EXISTS daily_token_limit INTEGER DEFAULT 25");

                    // appointments
                    jdbcTemplate.execute("ALTER TABLE appointments ADD COLUMN IF NOT EXISTS shift VARCHAR(50)");
                    jdbcTemplate.execute("ALTER TABLE appointments ADD COLUMN IF NOT EXISTS pdf_file_path VARCHAR(255)");
                    jdbcTemplate.execute("ALTER TABLE appointments ADD COLUMN IF NOT EXISTS consultation_start_time TIMESTAMP");
                    jdbcTemplate.execute("ALTER TABLE appointments ADD COLUMN IF NOT EXISTS consultation_end_time TIMESTAMP");
                    jdbcTemplate.execute("ALTER TABLE appointments ADD COLUMN IF NOT EXISTS rating INTEGER");
                    jdbcTemplate.execute("ALTER TABLE appointments ADD COLUMN IF NOT EXISTS feedback_text TEXT");

                    // hospitals
                    jdbcTemplate.execute("ALTER TABLE hospitals ADD COLUMN IF NOT EXISTS logo_url TEXT");
                    jdbcTemplate.execute("ALTER TABLE hospitals ADD COLUMN IF NOT EXISTS brand_color VARCHAR(50) DEFAULT '#0284c7'");
                    jdbcTemplate.execute("ALTER TABLE hospitals ADD COLUMN IF NOT EXISTS registration_number VARCHAR(255)");
                    jdbcTemplate.execute("ALTER TABLE hospitals ADD COLUMN IF NOT EXISTS contact_person_name VARCHAR(255)");
                    jdbcTemplate.execute("ALTER TABLE hospitals ADD COLUMN IF NOT EXISTS contact_person_email VARCHAR(255)");
                    jdbcTemplate.execute("ALTER TABLE hospitals ADD COLUMN IF NOT EXISTS contact_person_phone VARCHAR(255)");
                    jdbcTemplate.execute("ALTER TABLE hospitals ADD COLUMN IF NOT EXISTS specialties VARCHAR(255)");
                    jdbcTemplate.execute("ALTER TABLE hospitals ADD COLUMN IF NOT EXISTS number_of_beds INTEGER DEFAULT 0");
                    jdbcTemplate.execute("ALTER TABLE hospitals ADD COLUMN IF NOT EXISTS application_note TEXT");
                    jdbcTemplate.execute("ALTER TABLE hospitals ADD COLUMN IF NOT EXISTS city VARCHAR(255)");
                    jdbcTemplate.execute("ALTER TABLE hospitals ADD COLUMN IF NOT EXISTS state VARCHAR(255)");
                    jdbcTemplate.execute("ALTER TABLE hospitals ADD COLUMN IF NOT EXISTS applied_at TIMESTAMP");
                    jdbcTemplate.execute("ALTER TABLE hospitals ADD COLUMN IF NOT EXISTS reviewed_at TIMESTAMP");
                    jdbcTemplate.execute("ALTER TABLE hospitals ADD COLUMN IF NOT EXISTS rejection_reason TEXT");
                    jdbcTemplate.execute("ALTER TABLE hospitals ADD COLUMN IF NOT EXISTS status VARCHAR(50) DEFAULT 'ACTIVE'");
                    jdbcTemplate.execute("ALTER TABLE hospitals ADD COLUMN IF NOT EXISTS active BOOLEAN DEFAULT TRUE");

                    logger.info("Successfully verified and self-healed database schema columns across all tables");
                } catch (Exception se) {
                    logger.warn("Schema self-healing notice (can be ignored on fresh DB): {}", se.getMessage());
                }

                // Always ensure Super Admin account exists
                if (userRepo.findByEmailIgnoreCase("admin@mediassist.com").isEmpty()) {
                    com.med.assistant.model.User admin = new com.med.assistant.model.User(
                            "admin@mediassist.com",
                            passwordEncoder.encode("Admin@123"),
                            "Platform Administrator",
                            com.med.assistant.model.User.Role.SUPER_ADMIN,
                            null
                    );
                    userRepo.save(admin);
                    logger.info("Super admin account initialized: admin@mediassist.com / Admin@123");
                }

                // 1. Hospital 1: City Care (Local, ~0 km)
                Hospital h1 = hospitalRepo.findByNameIgnoreCase("City Care Multispeciality Hospital").orElseGet(() -> {
                    Hospital h = new Hospital(
                            "City Care Multispeciality Hospital",
                            "104 Health Avenue, Central District, New Delhi",
                            28.6139, 77.2090,
                            "+91 11-2334-1100",
                            "#0284c7"
                    );
                    h.setSpecialties("Cardiology, Pediatrics, General Medicine, Dermatology");
                    h = hospitalRepo.save(h);

                    Doctor d1 = new Doctor("Dr. Sarah Jenkins", "Cardiology", h, 30, 600.0);
                    d1.setRoomNumber("201");
                    d1.setAvailableTime("10:00 AM - 2:00 PM");
                    Doctor d2 = new Doctor("Dr. Rajiv Mehta", "Pediatrics", h, 25, 450.0);
                    d2.setRoomNumber("105");
                    d2.setAvailableTime("11:00 AM - 3:00 PM");
                    Doctor d3 = new Doctor("Dr. Elena Rostova", "General Medicine", h, 40, 350.0);
                    d3.setRoomNumber("101");
                    d3.setAvailableTime("09:00 AM - 1:00 PM");
                    doctorRepo.save(d1);
                    doctorRepo.save(d2);
                    doctorRepo.save(d3);

                    // Sample Confirmed Appointments with QR tokens
                    String qrToken1 = "DEMO-QR-001";
                    Appointment a1 = new Appointment(h, d1, "+919876543210", "John Doe",
                            LocalDate.now(), "10:30 AM", 1, qrToken1);
                    apptRepo.save(a1);
                    try { pdfService.generatePdfSlip(a1); } catch (Exception ignored) {}

                    return h;
                });

                // 2. Hospital 2: Apollo Metro (Local, ~1.4 km)
                Hospital h2 = hospitalRepo.findByNameIgnoreCase("Apollo Metro Health Clinic").orElseGet(() -> {
                    Hospital h = new Hospital(
                            "Apollo Metro Health Clinic",
                            "58 Parkside Plaza, North Wing, New Delhi",
                            28.6250, 77.2150,
                            "+91 11-2338-2200",
                            "#0d9488"
                    );
                    h.setSpecialties("Orthopedics, Dermatology, ENT, General Medicine");
                    h = hospitalRepo.save(h);

                    Doctor d4 = new Doctor("Dr. Michael Chang", "Orthopedics", h, 20, 700.0);
                    d4.setRoomNumber("302");
                    d4.setAvailableTime("10:00 AM - 1:00 PM");
                    Doctor d5 = new Doctor("Dr. Aisha Patel", "Dermatology", h, 25, 500.0);
                    d5.setRoomNumber("204");
                    d5.setAvailableTime("02:00 PM - 6:00 PM");
                    Doctor d6 = new Doctor("Dr. Vikas Gupta", "ENT", h, 25, 550.0);
                    d6.setRoomNumber("208");
                    d6.setAvailableTime("11:00 AM - 3:00 PM");
                    doctorRepo.save(d4);
                    doctorRepo.save(d5);
                    doctorRepo.save(d6);
                    return h;
                });

                // 3. Hospital 3: Sunrise Children & Family (Local, ~2.0 km)
                Hospital h3 = hospitalRepo.findByNameIgnoreCase("Sunrise Children & Family Hospital").orElseGet(() -> {
                    Hospital h = new Hospital(
                            "Sunrise Children & Family Hospital",
                            "12 Riverbank Road, South Extension, New Delhi",
                            28.6010, 77.1950,
                            "+91 11-2460-3300",
                            "#f97316"
                    );
                    h.setSpecialties("General Medicine, Gynecology & Obstetrics, Pediatrics");
                    h = hospitalRepo.save(h);

                    Doctor d7 = new Doctor("Dr. David Miller", "General Medicine", h, 35, 400.0);
                    d7.setRoomNumber("102");
                    d7.setAvailableTime("09:00 AM - 2:00 PM");
                    Doctor d8 = new Doctor("Dr. Sunita Rao", "Gynecology & Obstetrics", h, 25, 650.0);
                    d8.setRoomNumber("108");
                    d8.setAvailableTime("10:30 AM - 2:30 PM");
                    doctorRepo.save(d7);
                    doctorRepo.save(d8);
                    return h;
                });

                // 4. Hospital 4: Apex Regional Super-Specialty Institute (Regional Referral, ~34 km away)
                Hospital h4 = hospitalRepo.findByNameIgnoreCase("Apex Regional Super-Specialty Institute").orElseGet(() -> {
                    Hospital h = new Hospital(
                            "Apex Regional Super-Specialty Institute",
                            "Plot 9, Expressway Institutional Area, Greater Noida",
                            28.4744, 77.5040,
                            "+91 120-499-5500",
                            "#7c3aed"
                    );
                    h.setSpecialties("Neurology, Oncology, Cardiology, Nephrology");
                    h = hospitalRepo.save(h);

                    Doctor d9 = new Doctor("Dr. Arvind Swamy", "Neurology", h, 20, 900.0);
                    d9.setRoomNumber("401");
                    d9.setAvailableTime("10:00 AM - 1:00 PM");
                    Doctor d10 = new Doctor("Dr. Reema Sen", "Oncology", h, 15, 1000.0);
                    d10.setRoomNumber("405");
                    d10.setAvailableTime("01:00 PM - 4:00 PM");
                    doctorRepo.save(d9);
                    doctorRepo.save(d10);
                    return h;
                });

                // 5. Hospital 5: Metro Fortis Tertiary Care Center (Regional Referral, ~28 km away)
                Hospital h5 = hospitalRepo.findByNameIgnoreCase("Metro Fortis Tertiary Care Center").orElseGet(() -> {
                    Hospital h = new Hospital(
                            "Metro Fortis Tertiary Care Center",
                            "Sector 44 Institutional Area, Golf Course Ext, Gurugram",
                            28.4595, 77.0266,
                            "+91 124-455-8800",
                            "#059669"
                    );
                    h.setSpecialties("Gastroenterology, Pulmonology, Urology, Nephrology");
                    h = hospitalRepo.save(h);

                    Doctor d11 = new Doctor("Dr. Sanjay Kapoor", "Gastroenterology", h, 20, 800.0);
                    d11.setRoomNumber("310");
                    d11.setAvailableTime("10:00 AM - 2:00 PM");
                    Doctor d12 = new Doctor("Dr. Ananya Sen", "Pulmonology", h, 20, 750.0);
                    d12.setRoomNumber("315");
                    d12.setAvailableTime("02:00 PM - 5:00 PM");
                    doctorRepo.save(d11);
                    doctorRepo.save(d12);
                    return h;
                });

                // 6. Hospital 6: KIMS Hospital (Kalinga Institute of Medical Sciences) - Bhubaneswar / KIIT
                Hospital h6 = hospitalRepo.findByNameIgnoreCase("KIMS Hospital (Kalinga Institute of Medical Sciences)").orElseGet(() -> {
                    Hospital h = new Hospital(
                            "KIMS Hospital (Kalinga Institute of Medical Sciences)",
                            "KIMS Hospital Road, KIIT Campus 5, Patia, Bhubaneswar, Odisha 751024",
                            20.3535, 85.8155,
                            "+91 674-710-5300",
                            "#0284c7"
                    );
                    h.setSpecialties("General Medicine, Cardiology, Pediatrics, Orthopedics, ENT, Dermatology, Gastroenterology, Neurology");
                    h = hospitalRepo.save(h);

                    Doctor d13 = new Doctor("Dr. Rajesh Mohapatra", "General Medicine", h, 40, 400.0);
                    d13.setRoomNumber("101");
                    d13.setAvailableTime("09:00 AM - 1:00 PM");
                    Doctor d14 = new Doctor("Dr. Subrat Mishra", "Cardiology", h, 30, 600.0);
                    d14.setRoomNumber("205");
                    d14.setAvailableTime("10:00 AM - 2:00 PM");
                    Doctor d15 = new Doctor("Dr. Priyadarshini Panda", "Pediatrics", h, 25, 450.0);
                    d15.setRoomNumber("108");
                    d15.setAvailableTime("11:00 AM - 3:00 PM");
                    Doctor d16 = new Doctor("Dr. Alok Nayak", "Orthopedics", h, 25, 500.0);
                    d16.setRoomNumber("302");
                    d16.setAvailableTime("10:00 AM - 1:00 PM");
                    Doctor d17 = new Doctor("Dr. Vikas Gupta", "ENT", h, 25, 450.0);
                    d17.setRoomNumber("204");
                    d17.setAvailableTime("02:00 PM - 5:00 PM");
                    doctorRepo.save(d13);
                    doctorRepo.save(d14);
                    doctorRepo.save(d15);
                    doctorRepo.save(d16);
                    doctorRepo.save(d17);
                    return h;
                });

                // 7. Hospital 7: Apollo Hospitals Bhubaneswar
                Hospital h7 = hospitalRepo.findByNameIgnoreCase("Apollo Hospitals Bhubaneswar").orElseGet(() -> {
                    Hospital h = new Hospital(
                            "Apollo Hospitals Bhubaneswar",
                            "Plot No. 251, Sainik School Road, Unit 15, Bhubaneswar, Odisha 751005",
                            20.3056, 85.8316,
                            "+91 674-666-1066",
                            "#0d9488"
                    );
                    h.setSpecialties("General Medicine, Cardiology, Neurology, Oncology, Nephrology");
                    h = hospitalRepo.save(h);

                    Doctor d18 = new Doctor("Dr. Tanmay Ray", "General Medicine", h, 35, 600.0);
                    d18.setRoomNumber("102");
                    d18.setAvailableTime("10:00 AM - 2:00 PM");
                    Doctor d19 = new Doctor("Dr. Debasis Patnaik", "Cardiology", h, 25, 800.0);
                    d19.setRoomNumber("201");
                    d19.setAvailableTime("11:00 AM - 3:00 PM");
                    doctorRepo.save(d18);
                    doctorRepo.save(d19);
                    return h;
                });

                // 8. Hospital 8: AIIMS Bhubaneswar
                Hospital h8 = hospitalRepo.findByNameIgnoreCase("AIIMS Bhubaneswar").orElseGet(() -> {
                    Hospital h = new Hospital(
                            "AIIMS Bhubaneswar",
                            "Sijua, Patrapada, Bhubaneswar, Odisha 751019",
                            20.2312, 85.7766,
                            "+91 674-247-6789",
                            "#7c3aed"
                    );
                    h.setSpecialties("General Medicine, Cardiology, Orthopedics, Pediatrics, Neurology");
                    h = hospitalRepo.save(h);

                    Doctor d20 = new Doctor("Dr. Manoj Kumar Mohanty", "General Medicine", h, 50, 100.0);
                    d20.setRoomNumber("OPD-12");
                    d20.setAvailableTime("09:00 AM - 1:00 PM");
                    doctorRepo.save(d20);
                    return h;
                });

                // Sample Medication Reminder
                if (reminderRepo.count() == 0) {
                    MedicationReminder rem1 = new MedicationReminder("+919876543210", "Metformin 500mg", "1 tablet post-dinner", "21:00");
                    reminderRepo.save(rem1);
                }

                // Hospital Managers
                if (userRepo.findByEmailIgnoreCase("manager@citycare.com").isEmpty()) {
                    com.med.assistant.model.User manager1 = new com.med.assistant.model.User(
                            "manager@citycare.com",
                            passwordEncoder.encode("Manager@123"),
                            "City Care Operations Manager",
                            com.med.assistant.model.User.Role.HOSPITAL_MANAGER,
                            h1
                    );
                    userRepo.save(manager1);
                }

                if (userRepo.findByEmailIgnoreCase("manager@apollo.com").isEmpty()) {
                    com.med.assistant.model.User manager2 = new com.med.assistant.model.User(
                            "manager@apollo.com",
                            passwordEncoder.encode("Manager@123"),
                            "Apollo Clinic Desk Incharge",
                            com.med.assistant.model.User.Role.HOSPITAL_MANAGER,
                            h2
                    );
                    userRepo.save(manager2);
                }

                if (userRepo.findByEmailIgnoreCase("manager@kims.com").isEmpty()) {
                    com.med.assistant.model.User managerKims = new com.med.assistant.model.User(
                            "manager@kims.com",
                            passwordEncoder.encode("Manager@123"),
                            "KIMS Medical Superintendent & OPD Desk",
                            com.med.assistant.model.User.Role.HOSPITAL_MANAGER,
                            h6
                    );
                    userRepo.save(managerKims);
                }

                // Ensure all hospitals have a valid medical logo in database
                for (Hospital hosp : hospitalRepo.findAll()) {
                    if (hosp.getLogoUrl() == null || hosp.getLogoUrl().isBlank()) {
                        hosp.setLogoUrl("/images/default_hospital_crest.png");
                        hospitalRepo.save(hosp);
                    }
                }

                // Ensure all doctors have shift details and available days populated
                for (Doctor doc : doctorRepo.findAll()) {
                    boolean changed = false;
                    if (doc.getQualification() == null || doc.getQualification().isBlank()) {
                        doc.setQualification("MBBS, MD");
                        changed = true;
                    }
                    if (doc.getAvailableDays() == null || doc.getAvailableDays().isBlank()) {
                        doc.setAvailableDays("MON,TUE,WED,THU,FRI,SAT");
                        changed = true;
                    }
                    if (doc.getFirstHalfTime() == null || doc.getFirstHalfTime().isBlank()) {
                        doc.setFirstHalfTime("09:00 AM - 01:00 PM");
                        changed = true;
                    }
                    if (doc.getSecondHalfTime() == null || doc.getSecondHalfTime().isBlank()) {
                        doc.setSecondHalfTime("05:00 PM - 09:00 PM");
                        changed = true;
                    }
                    if (doc.getConsultationDurationMinutes() <= 0) {
                        doc.setConsultationDurationMinutes(15);
                        changed = true;
                    }
                    if (changed) {
                        doc.refreshCombinedAvailableTime();
                        doctorRepo.save(doc);
                    }
                }

                logger.info("Demo database & accounts seeded successfully! Admin: admin@mediassist.com / Admin@123, KIMS: manager@kims.com / Manager@123");
            } catch (Exception ex) {
                logger.warn("DataInitializer note: Database initialization or check skipped: {}", ex.getMessage());
            }
        };
    }
}
