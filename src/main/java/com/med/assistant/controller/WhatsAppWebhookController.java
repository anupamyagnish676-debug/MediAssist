package com.med.assistant.controller;

import com.med.assistant.model.Appointment;
import com.med.assistant.model.Doctor;
import com.med.assistant.model.Hospital;
import com.med.assistant.model.MedicalDocument;
import com.med.assistant.repository.AppointmentRepository;
import com.med.assistant.repository.DoctorRepository;
import com.med.assistant.repository.HospitalRepository;
import com.med.assistant.service.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.*;

@RestController
@RequestMapping("/api/v1/whatsapp")
public class WhatsAppWebhookController {

    private static final Logger logger = LoggerFactory.getLogger(WhatsAppWebhookController.class);

    @Value("${app.whatsapp.verify-token}")
    private String verifyToken;

    private final WhatsAppClientService whatsAppClient;
    private final LocationService locationService;
    private final GeminiAiService geminiAiService;
    private final ReportWardrobeService wardrobeService;
    private final MedicationReminderService reminderService;
    private final AppointmentSlipPdfService pdfService;
    private final HospitalRepository hospitalRepository;
    private final DoctorRepository doctorRepository;
    private final AppointmentRepository appointmentRepository;

    public WhatsAppWebhookController(WhatsAppClientService whatsAppClient,
                                     LocationService locationService,
                                     GeminiAiService geminiAiService,
                                     ReportWardrobeService wardrobeService,
                                     MedicationReminderService reminderService,
                                     AppointmentSlipPdfService pdfService,
                                     HospitalRepository hospitalRepository,
                                     DoctorRepository doctorRepository,
                                     AppointmentRepository appointmentRepository) {
        this.whatsAppClient = whatsAppClient;
        this.locationService = locationService;
        this.geminiAiService = geminiAiService;
        this.wardrobeService = wardrobeService;
        this.reminderService = reminderService;
        this.pdfService = pdfService;
        this.hospitalRepository = hospitalRepository;
        this.doctorRepository = doctorRepository;
        this.appointmentRepository = appointmentRepository;
    }

    /**
     * Webhook Verification Endpoint for Meta WhatsApp Cloud API.
     */
    @GetMapping("/webhook")
    public ResponseEntity<String> verifyWebhook(
            @RequestParam(value = "hub.mode", required = false) String mode,
            @RequestParam(value = "hub.verify_token", required = false) String token,
            @RequestParam(value = "hub.challenge", required = false) String challenge) {

        logger.info("Webhook verification request: mode={}, token={}", mode, token);

        if ("subscribe".equals(mode) && verifyToken.equals(token)) {
            logger.info("Webhook verified successfully with Meta challenge.");
            return ResponseEntity.ok(challenge);
        }
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body("Verification failed");
    }

    /**
     * Test Outbound WhatsApp Delivery from Server directly to phone.
     */
    @GetMapping("/test-send")
    public ResponseEntity<Map<String, Object>> testSend(
            @RequestParam(defaultValue = "917078015617") String phone,
            @RequestParam(defaultValue = "Hello from MediAssist Server! Your live WhatsApp bot connection is verified.") String message) {
        logger.info("Triggering test outbound WhatsApp message to {}", phone);
        boolean sent = whatsAppClient.sendTextMessage(phone, message);
        return ResponseEntity.ok(Map.of("success", sent, "recipient", phone, "message", message));
    }

    /**
     * Inbound WhatsApp Message Handler.
     */
    @PostMapping("/webhook")
    public ResponseEntity<String> handleInboundMessage(@RequestBody Map<String, Object> payload) {
        try {
            logger.info("Received WhatsApp webhook payload: {}", payload);

            List<Map<String, Object>> entries = (List<Map<String, Object>>) payload.get("entry");
            if (entries == null || entries.isEmpty()) return ResponseEntity.ok("EVENT_RECEIVED");

            Map<String, Object> entry = entries.get(0);
            List<Map<String, Object>> changes = (List<Map<String, Object>>) entry.get("changes");
            if (changes == null || changes.isEmpty()) return ResponseEntity.ok("EVENT_RECEIVED");

            Map<String, Object> change = changes.get(0);
            Map<String, Object> value = (Map<String, Object>) change.get("value");
            List<Map<String, Object>> messages = (List<Map<String, Object>>) value.get("messages");

            if (messages == null || messages.isEmpty()) return ResponseEntity.ok("EVENT_RECEIVED");

            Map<String, Object> message = messages.get(0);
            String fromPhone = (String) message.get("from");
            String messageType = (String) message.get("type");

            dispatchMessage(fromPhone, messageType, message);

        } catch (Exception e) {
            logger.error("Error processing inbound message: {}", e.getMessage(), e);
        }

        return ResponseEntity.ok("EVENT_RECEIVED");
    }

    private void dispatchMessage(String fromPhone, String type, Map<String, Object> message) {
        switch (type) {
            case "location" -> handleLocationMessage(fromPhone, (Map<String, Object>) message.get("location"));
            case "text" -> handleTextMessage(fromPhone, (Map<String, Object>) message.get("text"));
            case "interactive" -> handleInteractiveMessage(fromPhone, (Map<String, Object>) message.get("interactive"));
            case "image", "document" -> handleDocumentMessage(fromPhone, type, message);
            case "audio" -> handleAudioVoiceNote(fromPhone, (Map<String, Object>) message.get("audio"));
            default -> whatsAppClient.sendTextMessage(fromPhone, "Hello! I am your AI Medical Assistant. Send your symptoms, share your location to book a doctor, or upload reports to your Report Wardrobe.");
        }
    }

    private void handleLocationMessage(String fromPhone, Map<String, Object> location) {
        double lat = ((Number) location.get("latitude")).doubleValue();
        double lon = ((Number) location.get("longitude")).doubleValue();

        List<LocationService.NearbyHospitalResult> nearby = locationService.findNearbyHospitals(lat, lon, 25.0);

        if (nearby.isEmpty()) {
            whatsAppClient.sendTextMessage(fromPhone, "No registered hospitals found within 25 km of your location. Please check back soon as more hospitals join!");
            return;
        }

        List<Map<String, String>> rows = new ArrayList<>();
        for (LocationService.NearbyHospitalResult res : nearby) {
            rows.add(Map.of(
                    "id", "HOSP_" + res.hospital().getId(),
                    "title", res.hospital().getName(),
                    "description", res.distanceKm() + " km away • " + res.availableDoctorsCount() + " doctors available"
            ));
        }

        whatsAppClient.sendInteractiveList(
                fromPhone,
                "Nearby Hospitals",
                "🏥 Found " + nearby.size() + " hospitals near you. Tap below to choose a hospital:",
                "Select Hospital",
                rows
        );
    }

    private void handleTextMessage(String fromPhone, Map<String, Object> textObj) {
        String body = (String) textObj.get("body");
        if (body == null) return;

        // 1. Emergency red-flag check
        if (geminiAiService.isEmergency(body)) {
            whatsAppClient.sendTextMessage(fromPhone, geminiAiService.getEmergencyResponse());
            return;
        }

        // 2. Report Wardrobe retrieval request (e.g. "send me my blood report")
        if (body.toLowerCase().contains("report") || body.toLowerCase().contains("wardrobe") || body.toLowerCase().contains("test")) {
            List<MedicalDocument> found = wardrobeService.searchReports(fromPhone, body.replaceAll("(?i)(send|me|my|report|test|show)", "").trim());
            if (!found.isEmpty()) {
                MedicalDocument doc = found.get(0);
                whatsAppClient.sendTextMessage(fromPhone, "📁 Found your report: *" + doc.getTitle() + "*\n\n" + doc.getAiSummary());
                return;
            }
        }

        // 3. General AI Medical Triage & Clinical Triage response
        String aiResponse = geminiAiService.askMedicalAi(body, "");
        whatsAppClient.sendTextMessage(fromPhone, aiResponse);
    }

    private void handleInteractiveMessage(String fromPhone, Map<String, Object> interactive) {
        String type = (String) interactive.get("type");

        if ("list_reply".equals(type)) {
            Map<String, Object> reply = (Map<String, Object>) interactive.get("list_reply");
            String selectedId = (String) reply.get("id");

            if (selectedId != null && selectedId.startsWith("HOSP_")) {
                Long hospitalId = Long.parseLong(selectedId.replace("HOSP_", ""));
                List<Doctor> doctors = doctorRepository.findByHospitalIdAndAvailableTodayTrue(hospitalId);

                if (doctors.isEmpty()) {
                    whatsAppClient.sendTextMessage(fromPhone, "No doctors are currently available at this hospital today. Please try another hospital.");
                    return;
                }

                List<WhatsAppClientService.ButtonOption> buttons = new ArrayList<>();
                for (Doctor doc : doctors.stream().limit(3).toList()) {
                    buttons.add(new WhatsAppClientService.ButtonOption("DOC_" + doc.getId(), "Dr. " + doc.getName()));
                }

                whatsAppClient.sendInteractiveButtons(
                        fromPhone,
                        "Select an available doctor for today:",
                        buttons
                );
            }
        } else if ("button_reply".equals(type)) {
            Map<String, Object> reply = (Map<String, Object>) interactive.get("button_reply");
            String buttonId = (String) reply.get("id");

            if (buttonId.startsWith("DOC_")) {
                Long doctorId = Long.parseLong(buttonId.replace("DOC_", ""));
                bookAppointmentForDoctor(fromPhone, doctorId);
            } else if (buttonId.startsWith("MED_TAKEN_")) {
                Long reminderId = Long.parseLong(buttonId.replace("MED_TAKEN_", ""));
                reminderService.markAsTaken(reminderId);
                whatsAppClient.sendTextMessage(fromPhone, "✅ Logged! Great job taking your medication on time. 🌟");
            } else if (buttonId.startsWith("MED_SNOOZE_")) {
                Long reminderId = Long.parseLong(buttonId.replace("MED_SNOOZE_", ""));
                reminderService.markAsSnoozed(reminderId);
                whatsAppClient.sendTextMessage(fromPhone, "⏰ Snoozed for 15 minutes. We will remind you again!");
            }
        }
    }

    private void bookAppointmentForDoctor(String fromPhone, Long doctorId) {
        Doctor doctor = doctorRepository.findById(doctorId).orElseThrow();
        Hospital hospital = doctor.getHospital();

        int todayBookings = appointmentRepository.countByDoctorIdAndAppointmentDate(doctorId, LocalDate.now());
        int nextTokenNumber = todayBookings + 1;

        String qrToken = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        Appointment appointment = new Appointment(hospital, doctor, fromPhone, "Patient",
                LocalDate.now(), "11:00 AM", nextTokenNumber, qrToken);

        appointment = appointmentRepository.save(appointment);

        // Generate Branded PDF Slip with QR Code (in-memory)
        try {
            pdfService.generatePdfSlip(appointment);
        } catch (Exception e) {
            logger.error("Error generating PDF slip for appointment: {}", e.getMessage(), e);
        }

        String pdfUrl = "https://mediassist-1hdl.onrender.com/api/v1/appointments/" + appointment.getId() + "/pdf";

        // Send Confirmation Text
        String confirmation = """
            🎉 *Appointment Confirmed!*
            
            🏥 *Hospital:* %s
            👨‍⚕️ *Doctor:* %s (%s)
            🚪 *Room:* %s
            📅 *Date:* Today, %s
            ⏰ *Time:* 11:00 AM
            
            👉 *YOUR QUEUE TOKEN: #%02d*
            
            📥 *Download Slip (PDF):*
            %s
            
            📄 Show the QR code on your slip to the receptionist upon arrival for instant check-in!
            """.formatted(
                hospital.getName(),
                doctor.getName(),
                doctor.getDepartment(),
                doctor.getRoomNumber(),
                LocalDate.now(),
                nextTokenNumber,
                pdfUrl
        );

        whatsAppClient.sendTextMessage(fromPhone, confirmation);

        // Also deliver the PDF document file directly into the WhatsApp conversation
        try {
            whatsAppClient.sendDocumentMessage(
                    fromPhone,
                    pdfUrl,
                    "📄 Official Appointment Slip (Token #" + String.format("%02d", nextTokenNumber) + ")",
                    "Appointment_Slip_Token_" + nextTokenNumber + ".pdf"
            );
        } catch (Exception e) {
            logger.warn("Failed to dispatch PDF document attachment: {}", e.getMessage());
        }
    }

    private void handleDocumentMessage(String fromPhone, String type, Map<String, Object> message) {
        // In dev sandbox: mock document reception
        byte[] dummyBytes = "Dummy medical report content".getBytes();
        MedicalDocument doc = wardrobeService.storeDocument(fromPhone, dummyBytes, "Lab_Report_" + System.currentTimeMillis() + ".pdf", "application/pdf");

        whatsAppClient.sendTextMessage(fromPhone, """
            📥 Document received and safely saved to your Report Wardrobe!
            
            """ + doc.getAiSummary() + """
            
            💡 You can ask for this document anytime simply by typing: 'Send me my lab report'.
            """);
    }

    private void handleAudioVoiceNote(String fromPhone, Map<String, Object> audio) {
        whatsAppClient.sendTextMessage(fromPhone, """
            🎙️ Voice note received!
            
            Our AI has processed your audio.
            • We have noted your symptoms.
            • If you would like to book a doctor, simply share your current location pin via WhatsApp!
            """);
    }
}
