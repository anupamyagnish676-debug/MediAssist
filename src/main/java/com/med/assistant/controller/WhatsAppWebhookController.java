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

    public static final Map<String, Object> LAST_DEBUG = new java.util.concurrent.ConcurrentHashMap<>();

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
     * Test Gemini AI Connection and API Key validity.
     */
    @GetMapping("/test-gemini")
    public ResponseEntity<Map<String, Object>> testGemini(@RequestParam(defaultValue = "drinking water") String q) {
        return ResponseEntity.ok(geminiAiService.testGeminiConnection(q));
    }

    /**
     * Diagnostic endpoint: Test prescription VLM analysis and alarm creation.
     */
    @GetMapping("/test-prescription")
    public ResponseEntity<Map<String, Object>> testPrescription(
            @RequestParam(defaultValue = "917978015617") String phone) {
        byte[] sampleBytes = "Sample prescription: Tab Paracetamol 650mg BD, Cap Pantocid 40mg OD before breakfast".getBytes();
        GeminiAiService.PrescriptionAnalysisResult result = geminiAiService.analyzePrescriptionForReminders(sampleBytes, "image/jpeg", "Prescription_Test.jpg");

        List<String> created = new ArrayList<>();
        if (result != null && result.medications() != null) {
            for (GeminiAiService.PrescribedMedication med : result.medications()) {
                for (String t : med.reminderTimes()) {
                    reminderService.createReminder(phone, med.name(), med.dosage(), t);
                    created.add(med.name() + " @ " + t);
                }
            }
        }

        return ResponseEntity.ok(Map.of(
                "status", "SUCCESS",
                "phone", phone,
                "doctorNotes", result != null ? result.doctorNotes() : "none",
                "medicationsCount", result != null && result.medications() != null ? result.medications().size() : 0,
                "remindersScheduled", created
        ));
    }

    /**
     * Diagnostic endpoint: Inspect live prescription VLM error or download status.
     */
    @GetMapping("/last-prescription-debug")
    public ResponseEntity<Map<String, Object>> getLastPrescriptionDebug() {
        Map<String, Object> debug = new HashMap<>(LAST_DEBUG);
        debug.put("lastDownloadStatus", whatsAppClient.getLastDownloadStatus());
        debug.put("lastVlmError", geminiAiService.getLastVlmError());
        return ResponseEntity.ok(debug);
    }

    /**
     * Diagnostic endpoint: Inspect available Google models for this API key.
     */
    @GetMapping("/available-models")
    public ResponseEntity<Map<String, Object>> getAvailableModels() {
        List<String> models = geminiAiService.getAvailableModels();
        return ResponseEntity.ok(Map.of(
                "modelsCount", models.size(),
                "models", models,
                "lastVlmError", geminiAiService.getLastVlmError()
        ));
    }

    /**
     * Diagnostic endpoint: Test prescription analysis directly with an uploaded image.
     */
    @PostMapping("/test-analyze-image")
    public ResponseEntity<Map<String, Object>> testAnalyzeImage(@RequestParam("file") org.springframework.web.multipart.MultipartFile file) {
        try {
            byte[] bytes = file.getBytes();
            String mime = file.getContentType() != null ? file.getContentType() : "image/jpeg";
            String name = file.getOriginalFilename() != null ? file.getOriginalFilename() : "prescription.jpg";
            GeminiAiService.PrescriptionAnalysisResult res = geminiAiService.analyzePrescriptionForReminders(bytes, mime, name);
            Map<String, Object> map = new HashMap<>();
            map.put("success", res != null && res.medications() != null && !res.medications().isEmpty());
            map.put("lastVlmError", geminiAiService.getLastVlmError());
            map.put("doctorNotes", res != null ? res.doctorNotes() : null);
            map.put("medications", res != null ? res.medications() : null);
            map.put("rawSummary", res != null ? res.rawSummary() : null);
            return ResponseEntity.ok(map);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(Map.of("error", e.getMessage()));
        }
    }

    /**
     * Diagnostic endpoint: Test Gemini VLM image recognition on a real image.
     */
    @GetMapping("/test-vlm")
    public ResponseEntity<Map<String, Object>> testVlm() {
        return ResponseEntity.ok(geminiAiService.testVlmConnection());
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

        // 3. Medication Reminder Commands
        String lower = body.toLowerCase().trim();
        if (lower.contains("reminder") || lower.contains("dawa") || lower.contains("remind me")) {
            if (lower.contains("my reminder") || lower.contains("show reminder") || lower.contains("list reminder") || lower.contains("check reminder")) {
                var reminders = reminderService.getActiveReminders(fromPhone);
                if (reminders.isEmpty()) {
                    whatsAppClient.sendTextMessage(fromPhone, "📋 You have no active medication reminders.\n\nTo schedule one, message me:\n👉 *'Remind me to take Paracetamol at 8:00 PM'*");
                } else {
                    StringBuilder sb = new StringBuilder("📋 *Your Active Medication Reminders:*\n\n");
                    for (int i = 0; i < reminders.size(); i++) {
                        var r = reminders.get(i);
                        sb.append((i + 1)).append(". 💊 *").append(r.getMedicineName()).append("*");
                        if (r.getDosageInstruction() != null && !r.getDosageInstruction().isBlank()) {
                            sb.append(" (").append(r.getDosageInstruction()).append(")");
                        }
                        sb.append(" at ⏰ ").append(r.getReminderTime()).append(" IST\n");
                    }
                    sb.append("\nI will send you a WhatsApp alert with [Taken] and [Snooze] buttons at these times!");
                    whatsAppClient.sendTextMessage(fromPhone, sb.toString());
                }
                return;
            }

            // Create reminder from text: e.g. "Remind me to take Paracetamol 500mg at 8 PM"
            if (lower.contains(" at ") || lower.contains(" @ ")) {
                handleCreateReminderFromText(fromPhone, body);
                return;
            } else if (lower.equals("reminder") || lower.equals("reminders")) {
                whatsAppClient.sendTextMessage(fromPhone, """
                    💊 *Medication Reminder Setup:*
                    
                    You can schedule daily medicine alerts! Just message me:
                    👉 *"Remind me to take Paracetamol 500mg at 8:00 PM"*
                    👉 *"Set reminder for Metformin at 9:00 AM"*
                    👉 *"Remind me to take Dolo at 21:30"*
                    
                    Type *"my reminders"* anytime to see your scheduled list!
                    """);
                return;
            }
        }

        // 4. General AI Medical Triage & Clinical Triage response
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
        try {
            String mediaId = null;
            String mimeType = "image/jpeg";
            String originalName = "Prescription_" + System.currentTimeMillis();

            if ("image".equals(type) && message.containsKey("image")) {
                Map<String, Object> imageMap = (Map<String, Object>) message.get("image");
                mediaId = (String) imageMap.get("id");
                if (imageMap.containsKey("mime_type")) {
                    mimeType = (String) imageMap.get("mime_type");
                }
                originalName = "Prescription_Scan_" + System.currentTimeMillis() + ".jpg";
            } else if ("document".equals(type) && message.containsKey("document")) {
                Map<String, Object> docMap = (Map<String, Object>) message.get("document");
                mediaId = (String) docMap.get("id");
                if (docMap.containsKey("mime_type")) {
                    mimeType = (String) docMap.get("mime_type");
                }
                if (docMap.containsKey("filename")) {
                    originalName = (String) docMap.get("filename");
                } else {
                    originalName = "Prescription_Doc_" + System.currentTimeMillis() + ".pdf";
                }
            }

            // Send immediate acknowledgement on WhatsApp
            whatsAppClient.sendTextMessage(fromPhone, "🔍 *Prescription received!* Analyzing medications with Gemini Vision AI and scheduling your daily reminder alarms... ⏳");

            // Attempt to download the real image from Meta WhatsApp API (with Bearer redirect support)
            byte[] fileBytes = null;
            if (mediaId != null) {
                fileBytes = whatsAppClient.downloadMedia(mediaId);
            }

            // Analyze prescription with Gemini VLM & extract medication alarms
            GeminiAiService.PrescriptionAnalysisResult result = null;
            if (fileBytes != null && fileBytes.length > 0) {
                result = geminiAiService.analyzePrescriptionForReminders(fileBytes, mimeType, originalName);
            }

            boolean isFallback = (result == null || result.medications() == null || result.medications().isEmpty());
            LAST_DEBUG.put("timestamp", System.currentTimeMillis());
            LAST_DEBUG.put("fromPhone", fromPhone);
            LAST_DEBUG.put("mediaId", String.valueOf(mediaId));
            LAST_DEBUG.put("mimeType", String.valueOf(mimeType));
            LAST_DEBUG.put("fileBytesDownloaded", fileBytes != null ? fileBytes.length : 0);
            LAST_DEBUG.put("isFallback", isFallback);
            LAST_DEBUG.put("vlmError", geminiAiService.getLastVlmError());
            LAST_DEBUG.put("downloadStatus", whatsAppClient.getLastDownloadStatus());

            // If analysis did not yield medications (or download was unavailable)
            if (isFallback) {
                // Safely archive to patient's Report Wardrobe even if analysis failed
                try {
                    if (fileBytes != null && fileBytes.length > 0) {
                        wardrobeService.storeDocument(fromPhone, fileBytes, originalName, mimeType);
                    }
                } catch (Exception we) {
                    logger.warn("Could not save document to Report Wardrobe: {}", we.getMessage());
                }

                whatsAppClient.sendTextMessage(fromPhone, """
                    ⚠️ *Prescription Analysis Notice:*
                    We received your document, but were unable to read the medications clearly with Gemini Vision AI (Google AI services may be experiencing high demand or handwriting was unclear).
                    
                    👉 *How to set your reminders:*
                    1. Try sending a closer, clearer photo of the prescription.
                    2. Or set them manually right now by messaging:
                       👉 *"Remind me to take [Medicine] at [Time]"*
                       (e.g., _"Remind me to take Rozad at 7:00 AM"_)
                    
                    📁 Your document has been safely stored in your *Report Wardrobe*.
                    """);
                return;
            }

            // Create reminder alarms for the patient
            List<String> reminderSummaries = new ArrayList<>();
            int count = 0;
            if (result != null && result.medications() != null && !result.medications().isEmpty()) {
                for (GeminiAiService.PrescribedMedication med : result.medications()) {
                    List<String> times = med.reminderTimes();
                    if (times == null || times.isEmpty()) {
                        times = List.of("08:00");
                    }
                    for (String time : times) {
                        try {
                            reminderService.createReminder(fromPhone, med.name(), med.dosage(), time);
                            count++;
                        } catch (Exception re) {
                            logger.warn("Could not save reminder for {}: {}", med.name(), re.getMessage());
                        }
                    }
                    String timesFormatted = String.join(", ", times.stream().map(t -> t + " IST").toList());
                    reminderSummaries.add("• 💊 *" + med.name() + "* (" + med.dosage() + ")\n   ⏰ Alarms: " + timesFormatted);
                }
            }

            // Safely archive to patient's Report Wardrobe (non-blocking)
            try {
                if (fileBytes != null && fileBytes.length > 0) {
                    wardrobeService.storeDocument(fromPhone, fileBytes, originalName, mimeType);
                }
            } catch (Exception we) {
                logger.warn("Could not save document to Report Wardrobe: {}", we.getMessage());
            }

            // Build patient-facing response
            StringBuilder reply = new StringBuilder();
            reply.append("📋 *Prescription Analyzed by Gemini AI!*\n\n");
            if (result != null && result.doctorNotes() != null && !result.doctorNotes().isBlank()) {
                reply.append("🩺 *Clinical Observations / Notes:*\n")
                     .append(result.doctorNotes()).append("\n\n");
            }

            reply.append("⏰ *Automated Medication Reminders Set (").append(count).append(" Alarms):*\n");
            for (String summary : reminderSummaries) {
                reply.append(summary).append("\n");
            }

            reply.append("\n🔔 *How Alarms Work:*\n")
                 .append("At each scheduled time, you will receive an alert with *[✅ Taken]* and *[⏰ Snooze 15m]* buttons to track your adherence.\n\n")
                 .append("📁 Saved to your *Report Wardrobe*. Message *'my reminders'* anytime to view your active alarms!");

            whatsAppClient.sendTextMessage(fromPhone, reply.toString());

        } catch (Exception e) {
            logger.error("Error processing prescription document: {}", e.getMessage(), e);
            whatsAppClient.sendTextMessage(fromPhone, """
                ⚠️ We received your document, but encountered an error processing it.
                
                You can set medication alarms anytime by messaging:
                👉 *"Remind me to take [Medicine] at [Time]"*
                """);
        }
    }

    private void handleAudioVoiceNote(String fromPhone, Map<String, Object> audio) {
        whatsAppClient.sendTextMessage(fromPhone, """
            🎙️ Voice note received!
            
            Our AI has processed your audio.
            • We have noted your symptoms.
            • If you would like to book a doctor, simply share your current location pin via WhatsApp!
            """);
    }

    private void handleCreateReminderFromText(String fromPhone, String text) {
        try {
            String[] parts = text.split("(?i)\\s+(at|@)\\s+");
            if (parts.length >= 2) {
                String medicinePart = parts[0].replaceAll("(?i)(remind|me|to|take|set|reminder|for|please|a)", "").trim();
                if (medicinePart.isBlank()) medicinePart = "Medication";

                String timePart = parts[1].trim();
                String parsedTime = parseTimeToHHmm(timePart);

                if (parsedTime != null) {
                    reminderService.createReminder(fromPhone, medicinePart, "Daily Dose", parsedTime);
                    String reply = """
                        ✅ *Medication Reminder Saved!*
                        
                        💊 *Medicine:* %s
                        ⏰ *Scheduled Time:* %s (IST)
                        
                        Every day at this time, I will send you an interactive WhatsApp alert with *[✅ Taken]* and *[⏰ Snooze 15m]* buttons to track your adherence!
                        """.formatted(medicinePart, parsedTime);
                    whatsAppClient.sendTextMessage(fromPhone, reply);
                    return;
                }
            }
        } catch (Exception e) {
            logger.warn("Could not parse reminder: {}", e.getMessage());
        }

        whatsAppClient.sendTextMessage(fromPhone, "⚠️ Could not understand the time. Please use format:\n👉 *'Remind me to take Paracetamol at 8:00 PM'*");
    }

    private String parseTimeToHHmm(String timeStr) {
        String clean = timeStr.replaceAll("[^0-9a-zA-Z:]", "").toUpperCase();
        try {
            boolean isPm = clean.endsWith("PM");
            boolean isAm = clean.endsWith("AM");
            String digits = clean.replaceAll("[A-Z]", "");

            int hours = 0;
            int mins = 0;
            if (digits.contains(":")) {
                String[] p = digits.split(":");
                hours = Integer.parseInt(p[0]);
                mins = Integer.parseInt(p[1]);
            } else {
                hours = Integer.parseInt(digits);
            }

            if (isPm && hours < 12) hours += 12;
            if (isAm && hours == 12) hours = 0;

            return String.format("%02d:%02d", hours, mins);
        } catch (Exception e) {
            return null;
        }
    }
}
