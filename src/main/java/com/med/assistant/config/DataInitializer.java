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

    @Bean
    public CommandLineRunner initDatabase(HospitalRepository hospitalRepo,
                                          DoctorRepository doctorRepo,
                                          AppointmentRepository apptRepo,
                                          MedicationReminderRepository reminderRepo,
                                          UserRepository userRepo,
                                          org.springframework.security.crypto.password.PasswordEncoder passwordEncoder,
                                          AppointmentSlipPdfService pdfService) {
        return args -> {
            try {
                if (hospitalRepo.count() > 0) return;

                logger.info("Seeding demo hospitals, doctors, and appointments...");

            // 1. Hospital 1
            Hospital h1 = new Hospital(
                    "City Care Multispeciality Hospital",
                    "104 Health Avenue, Central District",
                    28.6139, 77.2090, // Central coordinates
                    "+1 800-555-0199",
                    "#0284c7"
            );
            hospitalRepo.save(h1);

            Doctor d1 = new Doctor("Dr. Sarah Jenkins", "Cardiology", h1, 30, 60.0);
            d1.setRoomNumber("201");
            Doctor d2 = new Doctor("Dr. Rajiv Mehta", "Pediatrics", h1, 25, 45.0);
            d2.setRoomNumber("105");
            Doctor d3 = new Doctor("Dr. Elena Rostova", "General Medicine", h1, 40, 35.0);
            d3.setRoomNumber("101");
            doctorRepo.save(d1);
            doctorRepo.save(d2);
            doctorRepo.save(d3);

            // 2. Hospital 2
            Hospital h2 = new Hospital(
                    "Apollo Metro Health Clinic",
                    "58 Parkside Plaza, North Wing",
                    28.6250, 77.2150,
                    "+1 800-555-0244",
                    "#0d9488"
            );
            hospitalRepo.save(h2);

            Doctor d4 = new Doctor("Dr. Michael Chang", "Orthopedics", h2, 20, 70.0);
            d4.setRoomNumber("302");
            Doctor d5 = new Doctor("Dr. Aisha Patel", "Dermatology", h2, 25, 50.0);
            d5.setRoomNumber("204");
            doctorRepo.save(d4);
            doctorRepo.save(d5);

            // 3. Hospital 3
            Hospital h3 = new Hospital(
                    "Sunrise Children & Family Hospital",
                    "12 Riverbank Road, South Extension",
                    28.6010, 77.1950,
                    "+1 800-555-0377",
                    "#f97316"
            );
            hospitalRepo.save(h3);

            Doctor d6 = new Doctor("Dr. David Miller", "General Medicine", h3, 35, 40.0);
            d6.setRoomNumber("102");
            doctorRepo.save(d6);

            // 4. Sample Confirmed Appointments with QR tokens
            String qrToken1 = "DEMO-QR-001";
            Appointment a1 = new Appointment(h1, d1, "+919876543210", "John Doe",
                    LocalDate.now(), "10:30 AM", 1, qrToken1);
            apptRepo.save(a1);
            try {
                pdfService.generatePdfSlip(a1);
            } catch (Exception ignored) {}

            String qrToken2 = "DEMO-QR-002";
            Appointment a2 = new Appointment(h1, d1, "+919876543211", "Alice Smith",
                    LocalDate.now(), "11:00 AM", 2, qrToken2);
            apptRepo.save(a2);

            // 5. Sample Medication Reminder
            MedicationReminder rem1 = new MedicationReminder("+919876543210", "Metformin 500mg", "1 tablet post-dinner", "21:00");
            reminderRepo.save(rem1);

            // 6. Seed Super Admin & Hospital Managers
            com.med.assistant.model.User admin = new com.med.assistant.model.User(
                    "admin@mediassist.com",
                    passwordEncoder.encode("Admin@123"),
                    "Platform Administrator",
                    com.med.assistant.model.User.Role.SUPER_ADMIN,
                    null
            );
            userRepo.save(admin);

            com.med.assistant.model.User manager1 = new com.med.assistant.model.User(
                    "manager@citycare.com",
                    passwordEncoder.encode("Manager@123"),
                    "City Care Operations Manager",
                    com.med.assistant.model.User.Role.HOSPITAL_MANAGER,
                    h1
            );
            userRepo.save(manager1);

            com.med.assistant.model.User manager2 = new com.med.assistant.model.User(
                    "manager@apollo.com",
                    passwordEncoder.encode("Manager@123"),
                    "Apollo Clinic Desk Incharge",
                    com.med.assistant.model.User.Role.HOSPITAL_MANAGER,
                    h2
            );
            userRepo.save(manager2);

            logger.info("Demo database & accounts seeded successfully! Admin: admin@mediassist.com / Admin@123");
            } catch (Exception ex) {
                logger.warn("DataInitializer note: Database initialization or check skipped: {}", ex.getMessage());
            }
        };
    }
}
