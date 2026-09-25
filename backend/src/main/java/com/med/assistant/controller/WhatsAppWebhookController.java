package com.med.assistant.controller;

import com.med.assistant.model.Appointment;
import com.med.assistant.model.Doctor;
import com.med.assistant.model.Hospital;
import com.med.assistant.model.MedicalDocument;
import com.med.assistant.model.PatientSession;
import com.med.assistant.repository.AppointmentRepository;
import com.med.assistant.repository.DoctorRepository;
import com.med.assistant.repository.HospitalRepository;
import com.med.assistant.repository.PatientSessionRepository;
import com.med.assistant.service.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.*;

@RestController
@RequestMapping("/api/v1/whatsapp")
public class WhatsAppWebhookController {

    private static final Logger logger = LoggerFactory.getLogger(WhatsAppWebhookController.class);

    public static final Map<String, Object> LAST_DEBUG = new java.util.concurrent.ConcurrentHashMap<>();

    // Keep recently processed WhatsApp message IDs to prevent Meta webhook retries from duplicate processing
    private static final Set<String> PROCESSED_MESSAGE_IDS = Collections.synchronizedSet(
            Collections.newSetFromMap(new java.util.LinkedHashMap<String, Boolean>() {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> eldest) {
                    return size() > 2000;
                }
            })
    );

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
    private final ExternalHospitalService externalHospitalService;
    private final PatientSessionRepository patientSessionRepository;
    private final OpdScheduleService opdScheduleService;
    private final Map<String, String> userTriageDept = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, double[]> userLocation = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, Long> userPendingDoctorBooking = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<String, List<Long>> userRecentHospitals = new java.util.concurrent.ConcurrentHashMap<>();

    public record PendingTimeBooking(Long doctorId, LocalDate chosenDate) {}
    private final Map<String, PendingTimeBooking> userPendingTimeBooking = new java.util.concurrent.ConcurrentHashMap<>();

    public WhatsAppWebhookController(WhatsAppClientService whatsAppClient,
                                     LocationService locationService,
                                     GeminiAiService geminiAiService,
                                     ReportWardrobeService wardrobeService,
                                     MedicationReminderService reminderService,
                                     AppointmentSlipPdfService pdfService,
                                     HospitalRepository hospitalRepository,
                                     DoctorRepository doctorRepository,
                                     AppointmentRepository appointmentRepository,
                                     ExternalHospitalService externalHospitalService,
                                     PatientSessionRepository patientSessionRepository,
                                     OpdScheduleService opdScheduleService) {
        this.whatsAppClient = whatsAppClient;
        this.locationService = locationService;
        this.geminiAiService = geminiAiService;
        this.wardrobeService = wardrobeService;
        this.reminderService = reminderService;
        this.pdfService = pdfService;
        this.hospitalRepository = hospitalRepository;
        this.doctorRepository = doctorRepository;
        this.appointmentRepository = appointmentRepository;
        this.externalHospitalService = externalHospitalService;
        this.patientSessionRepository = patientSessionRepository;
        this.opdScheduleService = opdScheduleService;
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
     * Diagnostic endpoint to inspect latest webhook processing status and Gemini VLM status.
     */
    @GetMapping("/debug")
    public ResponseEntity<Map<String, Object>> getDebugInfo() {
        Map<String, Object> debug = new HashMap<>(LAST_DEBUG);
        try {
            debug.put("vlmTest", geminiAiService.testVlmConnection());
        } catch (Exception e) {
            debug.put("vlmTestError", e.getMessage());
        }
        return ResponseEntity.ok(debug);
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
            String messageId = (String) message.get("id");
            String fromPhone = (String) message.get("from");
            String messageType = (String) message.get("type");

            // Meta Webhook Deduplication: Drop duplicate webhook deliveries triggered by network retries
            if (messageId != null && !messageId.isBlank()) {
                if (!PROCESSED_MESSAGE_IDS.add(messageId)) {
                    logger.info("Dropping duplicate webhook delivery from Meta for messageId={}", messageId);
                    return ResponseEntity.ok("EVENT_RECEIVED");
                }
            }

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
            case "image", "document" -> java.util.concurrent.CompletableFuture.runAsync(() -> {
                try {
                    handleDocumentMessage(fromPhone, type, message);
                } catch (Exception e) {
                    logger.error("Error in async document/image processing: {}", e.getMessage(), e);
                }
            });
            case "audio" -> java.util.concurrent.CompletableFuture.runAsync(() -> {
                try {
                    handleAudioVoiceNote(fromPhone, (Map<String, Object>) message.get("audio"));
                } catch (Exception e) {
                    logger.error("Error in async audio note processing: {}", e.getMessage(), e);
                }
            });
            default -> whatsAppClient.sendTextMessage(fromPhone, "Hello! Welcome to the Clinical Health Desk. Send your symptoms, share your location to book an OPD token with an available doctor, or upload prescriptions and lab reports.");
        }
    }

    private double[] getOrRestoreUserLocation(String fromPhone) {
        double[] loc = userLocation.get(fromPhone);
        if (loc != null) {
            return loc;
        }
        try {
            var sessionOpt = patientSessionRepository.findByPhoneNumber(fromPhone);
            if (sessionOpt.isPresent()) {
                var s = sessionOpt.get();
                if (s.getLatitude() != null && s.getLongitude() != null) {
                    loc = new double[]{s.getLatitude(), s.getLongitude()};
                    userLocation.put(fromPhone, loc);
                    if (s.getPreferredDepartment() != null && !s.getPreferredDepartment().isBlank()) {
                        userTriageDept.put(fromPhone, s.getPreferredDepartment());
                    }
                    return loc;
                }
            }
        } catch (Exception ex) {
            logger.warn("Could not retrieve patient session: {}", ex.getMessage());
        }
        return null;
    }

    private void saveUserSession(String fromPhone, Double lat, Double lon, String dept) {
        try {
            var opt = patientSessionRepository.findByPhoneNumber(fromPhone);
            PatientSession session = opt.orElseGet(() -> new PatientSession(fromPhone, lat, lon, dept));
            if (lat != null) session.setLatitude(lat);
            if (lon != null) session.setLongitude(lon);
            if (dept != null && !dept.isBlank()) session.setPreferredDepartment(dept);
            session.setUpdatedAt(java.time.LocalDateTime.now());
            patientSessionRepository.save(session);
        } catch (Exception ex) {
            logger.warn("Could not save patient session: {}", ex.getMessage());
        }
    }

    private void handleLocationMessage(String fromPhone, Map<String, Object> location) {
        double lat = ((Number) location.get("latitude")).doubleValue();
        double lon = ((Number) location.get("longitude")).doubleValue();

        userLocation.put(fromPhone, new double[]{lat, lon});

        String preferredDept = userTriageDept.get(fromPhone);
        if (preferredDept == null || preferredDept.isBlank()) {
            preferredDept = "General Medicine";
        }

        saveUserSession(fromPhone, lat, lon, preferredDept);

        String prompt = "🏥 *Hospital Discovery & Care Options*\n\n"
                + "📍 GPS location received near your area.\n"
                + "🎯 *Specialist Needed:* *" + preferredDept + "*\n\n"
                + "Please select how you would like to explore hospitals:\n"
                + "• *⚡ Instant Booking:* Registered partner clinics within 25 km with OPD tokens on WhatsApp\n"
                + "• *🗺️ Nearby Hospitals:* All hospitals near you retrieved live from Google Maps";

        whatsAppClient.sendInteractiveButtons(
                fromPhone,
                prompt,
                List.of(
                        new WhatsAppClientService.ButtonOption("DISCOVER_INSTANT", "⚡ Instant Booking"),
                        new WhatsAppClientService.ButtonOption("DISCOVER_MAPS", "🗺️ View on Maps")
                )
        );
    }

    private void handleTextMessage(String fromPhone, Map<String, Object> textObj) {
        String body = (String) textObj.get("body");
        if (body == null) return;
        String lower = body.toLowerCase().trim();

        // 0. Greeting / First Interaction check (e.g. "hi", "hello", "hey", "start", "namaste", "help", "menu")
        if (isGreeting(lower)) {
            sendCustomWelcomeMessage(fromPhone);
            return;
        }

        // 1. Emergency red-flag check
        if (geminiAiService.isEmergency(body)) {
            whatsAppClient.sendTextMessage(fromPhone, geminiAiService.getEmergencyResponse());
            return;
        }

        // 2. Report Wardrobe retrieval request (e.g. "send me my blood report")
        if (lower.contains("report") || lower.contains("wardrobe") || lower.contains("test")) {
            List<MedicalDocument> found = wardrobeService.searchReports(fromPhone, body.replaceAll("(?i)(send|me|my|report|test|show)", "").trim());
            if (!found.isEmpty()) {
                MedicalDocument doc = found.get(0);
                whatsAppClient.sendTextMessage(fromPhone, "📁 Found your report: *" + doc.getTitle() + "*\n\n" + doc.getAiSummary());
                return;
            }
        }

        // 2a-1. Direct number selection for recently shown partner hospitals (e.g. "1", "2", "3")
        if (lower.matches("^[1-5]$") && userRecentHospitals.containsKey(fromPhone)) {
            int selIdx = Integer.parseInt(lower) - 1;
            List<Long> recentHospIds = userRecentHospitals.get(fromPhone);
            if (recentHospIds != null && selIdx >= 0 && selIdx < recentHospIds.size()) {
                handleHospitalSelection(fromPhone, recentHospIds.get(selIdx));
                return;
            }
        }

        // 2a-2. Check if patient is responding with a preferred date for a pending doctor appointment
        if (userPendingDoctorBooking.containsKey(fromPhone)) {
            LocalDate targetDate = parseUserDateInput(lower);
            if (targetDate != null) {
                Long doctorId = userPendingDoctorBooking.get(fromPhone);
                Doctor doc = doctorRepository.findById(doctorId).orElse(null);
                if (doc != null) {
                    if (!doc.isAvailableOnDay(targetDate.getDayOfWeek())) {
                        String days = (doc.getAvailableDays() != null && !doc.getAvailableDays().isBlank())
                                ? doc.getAvailableDays() : "Mon-Sat";
                        whatsAppClient.sendTextMessage(fromPhone, "ℹ️ *" + doc.getName() + " does not practice on " 
                                + targetDate.getDayOfWeek().name() + "s.*\n\nRegular OPD Working Days: *" + days 
                                + "*\n\nPlease select another date!");
                        return;
                    }
                    int booked = appointmentRepository.countByDoctorIdAndAppointmentDate(doctorId, targetDate);
                    int limit = doc.getDailyTokenLimit();
                    if (booked >= limit) {
                        promptSlotsFullAndShowAlternatives(fromPhone, doc, targetDate);
                    } else {
                        promptShiftOrTimeSelection(fromPhone, doc, targetDate);
                    }
                    return;
                }
            }
        }

        // 2a-3. Check if patient is responding with a preferred time or shift for pending booking
        if (userPendingTimeBooking.containsKey(fromPhone)) {
            PendingTimeBooking pending = userPendingTimeBooking.get(fromPhone);
            bookAppointmentWithPreferredTime(fromPhone, pending.doctorId(), pending.chosenDate(), body.trim());
            return;
        }

        // 2b. Direct Hospital Discovery & Maps requests from text
        if (lower.contains("nearby hospital") || lower.contains("nearby hospitals") || lower.contains("google map") 
                || lower.contains("google maps") || lower.contains("find hospital") || lower.contains("hospitals near")
                || lower.equals("maps") || lower.equals("map") || lower.equals("hospital") || lower.equals("hospitals")) {
            handleDiscoverGoogleMaps(fromPhone);
            return;
        }
        if (lower.contains("instant booking") || lower.contains("partner hospital") || lower.contains("partner clinic") 
                || lower.contains("book token") || lower.contains("book opd")) {
            handleDiscoverInstantBooking(fromPhone);
            return;
        }

        // 2c. Live Queue / Token Tracking Command
        if (lower.contains("queue status") || lower.contains("my token") || lower.contains("token status")
                || lower.contains("my appointment") || lower.contains("live queue") || lower.contains("check queue")
                || lower.equals("token") || lower.equals("queue")) {
            handleQueueStatusCheck(fromPhone);
            return;
        }

        // 3. Medication Reminder Commands
        // First check for cancel / stop / delete / clear alarms commands
        if (lower.contains("cancel all") || lower.contains("cancel alarm") || lower.contains("cancel reminder")
                || lower.contains("stop alarm") || lower.contains("stop reminder") || lower.contains("clear alarm")
                || lower.contains("clear reminder") || lower.contains("delete alarm") || lower.contains("delete reminder")
                || (lower.contains("alarm") && (lower.contains("cancel") || lower.contains("stop") || lower.contains("delete") || lower.contains("clear") || lower.contains("remove")))
                || (lower.contains("reminder") && (lower.contains("cancel") || lower.contains("stop") || lower.contains("delete") || lower.contains("clear") || lower.contains("remove")))) {
            int cancelled = reminderService.cancelAllReminders(fromPhone);
            if (cancelled > 0) {
                whatsAppClient.sendTextMessage(fromPhone, "🛑 *All Medication Alarms Cancelled!*\n\nSuccessfully turned off and cancelled " 
                        + cancelled + " active medication alarm(s).\n\nYou will no longer receive alert notifications for these medicines.\n\nTo schedule a new alarm anytime, message:\n👉 *'Remind me to take Paracetamol at 8:00 PM'*");
            } else {
                whatsAppClient.sendTextMessage(fromPhone, "ℹ️ You don't have any active medication alarms scheduled.");
            }
            return;
        }

        if (lower.contains("reminder") || lower.contains("dawa") || lower.contains("remind me") || lower.contains("alarm")) {
            if (lower.contains("my reminder") || lower.contains("show reminder") || lower.contains("list reminder") 
                    || lower.contains("check reminder") || lower.equals("alarm") || lower.equals("alarms") 
                    || lower.contains("my alarms") || lower.contains("show alarm") || lower.contains("list alarm") || lower.contains("schedule")) {
                showMyAlarms(fromPhone);
                return;
            }

            // Create reminder from text: e.g. "Remind me to take Paracetamol 500mg at 8 PM"
            if (lower.contains(" at ") || lower.contains(" @ ")) {
                handleCreateReminderFromText(fromPhone, body);
                return;
            } else if (lower.equals("reminder") || lower.equals("reminders") || lower.equals("alarm") || lower.equals("alarms")) {
                whatsAppClient.sendTextMessage(fromPhone, """
                    💊 *Medication Reminder Setup:*
                    
                    You can schedule daily medicine alerts! Just message me:
                    👉 *"Remind me to take Paracetamol 500mg at 8:00 PM"*
                    👉 *"Set reminder for Metformin at 9:00 AM"*
                    👉 *"Remind me to take Dolo at 21:30"*
                    
                    Type *"my reminders"* or *"my alarms"* anytime to see your scheduled list!
                    """);
                return;
            }
        }

        // 4. General Medical Triage & Clinical Triage response
        String aiResponse = geminiAiService.askMedicalAi(body, "");
        whatsAppClient.sendTextMessage(fromPhone, aiResponse);

        // Track user's needed medical specialty for hospital & doctor recommendations
        String detectedDept = detectSpecialty(body + " " + aiResponse);
        if (detectedDept != null) {
            userTriageDept.put(fromPhone, detectedDept);
            saveUserSession(fromPhone, null, null, detectedDept);
        }
    }

    private void handleInteractiveMessage(String fromPhone, Map<String, Object> interactive) {
        String type = (String) interactive.get("type");

        if ("list_reply".equals(type)) {
            Map<String, Object> reply = (Map<String, Object>) interactive.get("list_reply");
            String selectedId = (String) reply.get("id");

            if ("DISCOVER_INSTANT".equals(selectedId)) {
                handleDiscoverInstantBooking(fromPhone);
                return;
            } else if ("DISCOVER_MAPS".equals(selectedId)) {
                handleDiscoverGoogleMaps(fromPhone);
                return;
            }

            if (selectedId != null && selectedId.startsWith("HOSP_")) {
                Long hospitalId = Long.parseLong(selectedId.replace("HOSP_", ""));
                handleHospitalSelection(fromPhone, hospitalId);
            } else if (selectedId != null && selectedId.startsWith("DOC_RATE_")) {
                handleDoctorRatingSubmission(fromPhone, selectedId);
            } else if (selectedId != null && selectedId.startsWith("OFFDUTY_")) {
                whatsAppClient.sendTextMessage(fromPhone, "ℹ️ The doctor is off-duty on this day. Please select another date from the list above.");
            } else if (selectedId != null && selectedId.startsWith("SHIFT_")) {
                handleShiftSelection(fromPhone, selectedId);
            } else if (selectedId != null && selectedId.startsWith("APPTDATE_")) {
                handleAppointmentDateSelection(fromPhone, selectedId);
            } else if (selectedId != null && selectedId.startsWith("DOC_")) {
                Long doctorId = Long.parseLong(selectedId.replace("DOC_", ""));
                promptAppointmentDateSelection(fromPhone, doctorId);
            }
        } else if ("button_reply".equals(type)) {
            Map<String, Object> reply = (Map<String, Object>) interactive.get("button_reply");
            String buttonId = (String) reply.get("id");

            if ("DISCOVER_INSTANT".equals(buttonId)) {
                handleDiscoverInstantBooking(fromPhone);
                return;
            } else if ("DISCOVER_MAPS".equals(buttonId)) {
                handleDiscoverGoogleMaps(fromPhone);
                return;
            } else if ("MY_ALARMS".equals(buttonId)) {
                showMyAlarms(fromPhone);
                return;
            } else if ("QUEUE_STATUS".equals(buttonId) || "MY_TOKEN".equals(buttonId)) {
                handleQueueStatusCheck(fromPhone);
                return;
            }

            if (buttonId.startsWith("DOC_RATE_")) {
                handleDoctorRatingSubmission(fromPhone, buttonId);
            } else if (buttonId.startsWith("OFFDUTY_")) {
                whatsAppClient.sendTextMessage(fromPhone, "ℹ️ The doctor is off-duty on this day. Please select another date from the list above.");
            } else if (buttonId.startsWith("SHIFT_")) {
                handleShiftSelection(fromPhone, buttonId);
            } else if (buttonId.startsWith("APPTDATE_")) {
                handleAppointmentDateSelection(fromPhone, buttonId);
            } else if (buttonId.startsWith("DOC_")) {
                Long doctorId = Long.parseLong(buttonId.replace("DOC_", ""));
                promptAppointmentDateSelection(fromPhone, doctorId);
            } else if (buttonId.startsWith("MED_TAKEN_")) {
                String idPayload = buttonId.replace("MED_TAKEN_", "");
                List<Long> ids = Arrays.stream(idPayload.split("_"))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .map(Long::parseLong)
                        .toList();
                reminderService.markBatchAsTaken(ids);
                whatsAppClient.sendTextMessage(fromPhone, "✅ Logged! Great job taking your medication on time. 🌟");
            } else if (buttonId.startsWith("MED_SNOOZE_15_")) {
                String idPayload = buttonId.replace("MED_SNOOZE_15_", "");
                List<Long> ids = Arrays.stream(idPayload.split("_"))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .map(Long::parseLong)
                        .toList();
                reminderService.markBatchAsSnoozed(ids, 15);
                whatsAppClient.sendTextMessage(fromPhone, "⏰ Snoozed for 15 minutes. We will ring you again! 🔔");
            } else if (buttonId.startsWith("MED_SNOOZE_30_")) {
                String idPayload = buttonId.replace("MED_SNOOZE_30_", "");
                List<Long> ids = Arrays.stream(idPayload.split("_"))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .map(Long::parseLong)
                        .toList();
                reminderService.markBatchAsSnoozed(ids, 30);
                whatsAppClient.sendTextMessage(fromPhone, "⏰ Snoozed for 30 minutes. We will ring you again! 🔔");
            } else if (buttonId.startsWith("MED_SNOOZE_")) {
                String idPayload = buttonId.replace("MED_SNOOZE_", "");
                List<Long> ids = Arrays.stream(idPayload.split("_"))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .map(Long::parseLong)
                        .toList();
                reminderService.markBatchAsSnoozed(ids, 15);
                whatsAppClient.sendTextMessage(fromPhone, "⏰ Snoozed for 15 minutes. We will remind you again!");
            }
        }
    }

    private void handleDiscoverInstantBooking(String fromPhone) {
        double[] loc = getOrRestoreUserLocation(fromPhone);
        if (loc == null) {
            whatsAppClient.sendTextMessage(fromPhone, "📍 Please share your current location pin via WhatsApp first so we can find partner hospitals in your area!");
            return;
        }

        String preferredDept = userTriageDept.get(fromPhone);
        if (preferredDept == null || preferredDept.isBlank()) {
            preferredDept = "General Medicine";
        }

        List<LocationService.NearbyHospitalResult> local = locationService.findLocalHospitalsWithSmartFallback(loc[0], loc[1], preferredDept);

        if (local.isEmpty()) {
            String msg = "⚠️ *No Partner Clinics Available Right Now*\n\n"
                    + "Currently, registered partner clinics in your area do not have on-duty doctors.\n\n"
                    + "👉 Tap below to view all hospitals near you on Google Maps:";
            whatsAppClient.sendInteractiveButtons(
                    fromPhone,
                    msg,
                    List.of(new WhatsAppClientService.ButtonOption("DISCOVER_MAPS", "🗺️ View on Maps"))
            );
            return;
        }

        // Cache recent hospitals for direct number selection (1, 2, 3)
        userRecentHospitals.put(fromPhone, local.stream().map(r -> r.hospital().getId()).toList());

        List<Map<String, String>> rows = new ArrayList<>();
        for (LocationService.NearbyHospitalResult res : local) {
            Hospital h = res.hospital();
            String name = h.getName() != null ? h.getName().trim() : "Clinic";
            if (name.length() > 24) name = name.substring(0, 24).trim();
            String desc = res.distanceKm() + " km away • " + res.availableDoctorsCount() + " doc(s) on-duty";
            if (desc.length() > 72) desc = desc.substring(0, 72);
            rows.add(Map.of(
                    "id", "HOSP_" + h.getId(),
                    "title", name,
                    "description", desc
            ));
        }

        String body = "🏥 *Partner Hospitals for Instant Booking*\n"
                + "🎯 *Specialty:* *" + preferredDept + "*\n\n"
                + "Select a hospital below to view available *" + preferredDept + "* doctors and book your OPD queue token:";

        boolean sent = whatsAppClient.sendInteractiveList(
                fromPhone,
                "Partner Hospitals",
                body,
                "Select Hospital",
                rows
        );

        if (!sent) {
            // Text Fallback if interactive list fails or unsupported
            StringBuilder sb = new StringBuilder();
            sb.append("🏥 *Partner Hospitals for Instant Booking*\n");
            sb.append("🎯 *Specialty:* *").append(preferredDept).append("*\n\n");
            sb.append("Please select a hospital by replying with its number:\n\n");
            for (int i = 0; i < local.size(); i++) {
                Hospital h = local.get(i).hospital();
                sb.append((i + 1)).append("️⃣ *").append(h.getName()).append("*\n");
                sb.append("   📍 ").append(local.get(i).distanceKm()).append(" km away • ")
                        .append(local.get(i).availableDoctorsCount()).append(" doctor(s) on-duty\n\n");
            }
            sb.append("👉 Reply with *1*, *2*, or *3* to view available doctors!");
            whatsAppClient.sendTextMessage(fromPhone, sb.toString());
        }
    }

    private void handleHospitalSelection(String fromPhone, Long hospitalId) {
        Hospital hospital = hospitalRepository.findById(hospitalId).orElse(null);
        List<Doctor> allDoctors = doctorRepository.findByHospitalIdAndAvailableTodayTrue(hospitalId);

        if (allDoctors.isEmpty()) {
            whatsAppClient.sendTextMessage(fromPhone, "No doctors are currently available at this hospital today. Please try another hospital or view Google Maps.");
            return;
        }

        String preferredDept = userTriageDept.get(fromPhone);

        // Strict / smart filtering: show doctors related to that specific department
        List<Doctor> deptDocs = allDoctors;
        if (preferredDept != null && !preferredDept.isBlank()) {
            String pDept = preferredDept.toLowerCase().trim();
            List<Doctor> matching = allDoctors.stream()
                    .filter(d -> {
                        if (d.getDepartment() == null) return false;
                        String dDept = d.getDepartment().toLowerCase();
                        if (pDept.contains("medicine") || pDept.equals("general medicine")) {
                            return dDept.contains("medicine") || dDept.contains("general") || dDept.contains("physician");
                        }
                        return dDept.contains(pDept);
                    })
                    .toList();
            if (!matching.isEmpty()) {
                deptDocs = matching;
            }
        }

        // Build doctor schedule message
        StringBuilder docText = new StringBuilder();
        String deptHeader = (preferredDept != null && !preferredDept.isBlank()) ? preferredDept + " " : "";
        docText.append("👨‍⚕️ *").append(deptHeader).append("Specialists Available Today at ")
               .append(hospital != null ? hospital.getName() : "Hospital")
               .append(":*\n\n");

        int idx = 1;
        for (Doctor doc : deptDocs) {
            docText.append(idx++).append(". *").append(doc.getName()).append("*\n")
                   .append("   • Department: *").append(doc.getDepartment()).append("*\n")
                   .append("   • Rating: ⭐ ").append(String.format(java.util.Locale.US, "%.1f", doc.getRating()))
                   .append("/5.0 (").append(doc.getTotalReviews()).append(" patient reviews)\n")
                   .append("   • Room: ").append(doc.getRoomNumber() != null ? doc.getRoomNumber() : "-")
                   .append(" | Fee: ₹").append((int) doc.getConsultationFee()).append("\n");
            if (doc.getAvailableTime() != null && !doc.getAvailableTime().isBlank()) {
                docText.append("   • Hours: ").append(doc.getAvailableTime()).append("\n");
            }
            docText.append("\n");
        }
        docText.append("👉 Tap a doctor below to book your OPD queue token:");

        // Interactive Buttons (up to 3 doctors)
        if (deptDocs.size() <= 3) {
            List<WhatsAppClientService.ButtonOption> buttons = new ArrayList<>();
            for (Doctor doc : deptDocs) {
                buttons.add(new WhatsAppClientService.ButtonOption("DOC_" + doc.getId(), formatDoctorButtonTitle(doc)));
            }
            whatsAppClient.sendInteractiveButtons(
                    fromPhone,
                    docText.toString(),
                    buttons
            );
        } else {
            // Interactive List (if more than 3 doctors)
            List<Map<String, String>> docRows = new ArrayList<>();
            for (Doctor doc : deptDocs.stream().limit(10).toList()) {
                String title = doc.getName();
                if (title.length() > 24) title = title.substring(0, 24);
                String desc = "⭐" + String.format(java.util.Locale.US, "%.1f", doc.getRating()) + " • " + doc.getDepartment() + " • ₹" + (int) doc.getConsultationFee();
                if (desc.length() > 72) desc = desc.substring(0, 72);
                docRows.add(Map.of(
                        "id", "DOC_" + doc.getId(),
                        "title", title,
                        "description", desc
                ));
            }
            whatsAppClient.sendInteractiveList(
                    fromPhone,
                    "Available Doctors",
                    docText.toString(),
                    "Select Doctor",
                    docRows
            );
        }
    }

    private void handleDiscoverGoogleMaps(String fromPhone) {
        double[] loc = getOrRestoreUserLocation(fromPhone);
        if (loc == null) {
            whatsAppClient.sendTextMessage(fromPhone, "📍 Please share your current location pin via WhatsApp first so we can find nearby hospitals on Google Maps!");
            return;
        }

        String preferredDept = userTriageDept.get(fromPhone);
        List<ExternalHospitalService.ExternalHospital> mapsHospitals = externalHospitalService.findNearbyHospitalsFromMaps(loc[0], loc[1], preferredDept);

        StringBuilder sb = new StringBuilder();
        sb.append("🗺️ *Hospitals Near You (Google Maps)*\n");
        sb.append("📍 _Showing medical facilities near your GPS location_\n");
        if (preferredDept != null && !preferredDept.isBlank() && !preferredDept.equalsIgnoreCase("General Medicine")) {
            sb.append("🎯 *Specialty:* *").append(preferredDept).append("*\n");
        }
        sb.append("\n");

        if (mapsHospitals.isEmpty()) {
            sb.append("Medical facilities found in your area. Open the Google Maps link below for live routes and directions:\n\n");
        } else {
            int idx = 1;
            for (ExternalHospitalService.ExternalHospital eh : mapsHospitals) {
                sb.append(idx++).append(". 🏥 *").append(eh.name()).append("*\n");
                sb.append("   • Distance: ~").append(eh.distanceKm()).append(" km\n");
                if (eh.address() != null && !eh.address().isBlank()) {
                    sb.append("   • Area: ").append(eh.address()).append("\n");
                }
                sb.append("   🔗 ").append(eh.mapsUrl()).append("\n\n");
            }
        }

        String masterUrl = externalHospitalService.generateMasterGoogleMapsUrl(loc[0], loc[1], preferredDept);
        sb.append("👉 *Open Complete Map on Google Maps:*\n").append(masterUrl);

        // 1. Send full hospital list via standard text message (Meta limit is 4,096 chars - plenty of room)
        whatsAppClient.sendTextMessage(fromPhone, sb.toString());

        // 2. Send follow-up interactive prompt with button (concise, safely within 1024 char limit)
        whatsAppClient.sendInteractiveButtons(
                fromPhone,
                "💡 Would you like to book an instant OPD queue token with our local partner clinic?",
                List.of(new WhatsAppClientService.ButtonOption("DISCOVER_INSTANT", "⚡ Instant Booking"))
        );
    }

    private void promptAppointmentDateSelection(String fromPhone, Long doctorId) {
        Doctor doc = doctorRepository.findById(doctorId).orElse(null);
        if (doc == null) {
            whatsAppClient.sendTextMessage(fromPhone, "Selected doctor was not found. Please try booking again.");
            return;
        }

        userPendingDoctorBooking.put(fromPhone, doctorId);

        List<Map<String, String>> dateRows = new ArrayList<>();
        LocalDate today = LocalDate.now();

        // Offer next 6 days with live slot availability count
        for (int i = 0; i < 6; i++) {
            LocalDate d = today.plusDays(i);
            boolean isDoctorWorking = doc.isAvailableOnDay(d.getDayOfWeek());
            int booked = appointmentRepository.countByDoctorIdAndAppointmentDate(doctorId, d);
            int limit = doc.getDailyTokenLimit();
            int remaining = Math.max(0, limit - booked);

            String title;
            if (i == 0) {
                title = "Today (" + d.format(DateTimeFormatter.ofPattern("EEE, d MMM")) + ")";
            } else if (i == 1) {
                title = "Tomorrow (" + d.format(DateTimeFormatter.ofPattern("EEE, d MMM")) + ")";
            } else {
                title = d.format(DateTimeFormatter.ofPattern("EEE, d MMM yyyy"));
            }
            if (title.length() > 24) title = title.substring(0, 24);

            String desc;
            String rowId;
            if (!isDoctorWorking) {
                desc = "🔴 Off-Duty (Day Off)";
                rowId = "OFFDUTY_" + doctorId + "_" + d.toString();
            } else if (remaining > 0) {
                desc = "🟢 " + remaining + " tokens left (Next: #" + String.format("%02d", booked + 1) + ")";
                rowId = "APPTDATE_" + doctorId + "_" + d.toString();
            } else {
                desc = "🔴 FULL (0 tokens available)";
                rowId = "APPTDATE_" + doctorId + "_" + d.toString();
            }
            if (desc.length() > 72) desc = desc.substring(0, 72);

            dateRows.add(Map.of(
                    "id", rowId,
                    "title", title,
                    "description", desc
            ));
        }

        String bodyText = String.format(
                "📅 *Select Preferred Consultation Date*\n\n" +
                "👨‍⚕️ *Doctor:* %s (%s)\n" +
                "⭐ *Doctor Rating:* ⭐ %.1f/5.0 (%d reviews)\n" +
                "🚪 *Room:* %s | 💵 *Fee:* ₹%d\n" +
                "⏰ *Hours:* %s\n\n" +
                "Please choose your preferred date below:",
                doc.getName(),
                doc.getQualification() != null ? doc.getQualification() : doc.getDepartment(),
                doc.getRating(),
                doc.getTotalReviews(),
                doc.getRoomNumber() != null ? doc.getRoomNumber() : "-",
                (int) doc.getConsultationFee(),
                doc.getAvailableTime() != null ? doc.getAvailableTime() : "Regular OPD Hours"
        );

        whatsAppClient.sendInteractiveList(
                fromPhone,
                "Available Dates",
                bodyText,
                "Choose Date 📅",
                dateRows
        );
    }

    private void handleAppointmentDateSelection(String fromPhone, String payload) {
        try {
            String data = payload.replace("APPTDATE_", "");
            int splitIdx = data.indexOf("_");
            if (splitIdx < 0) return;

            Long doctorId = Long.parseLong(data.substring(0, splitIdx));
            String dateStr = data.substring(splitIdx + 1);
            LocalDate chosenDate = LocalDate.parse(dateStr);

            Doctor doctor = doctorRepository.findById(doctorId).orElse(null);
            if (doctor == null) {
                whatsAppClient.sendTextMessage(fromPhone, "Doctor record not found. Please try booking again.");
                return;
            }

            if (!doctor.isAvailableOnDay(chosenDate.getDayOfWeek())) {
                String days = (doctor.getAvailableDays() != null && !doctor.getAvailableDays().isBlank())
                        ? doctor.getAvailableDays() : "Mon-Sat";
                whatsAppClient.sendTextMessage(fromPhone, "ℹ️ *" + doctor.getName() + " does not practice on " 
                        + chosenDate.getDayOfWeek().name() + "s.*\n\nRegular OPD Working Days: *" + days 
                        + "*\n\nPlease select another date from the list above!");
                return;
            }

            int booked = appointmentRepository.countByDoctorIdAndAppointmentDate(doctorId, chosenDate);
            int limit = doctor.getDailyTokenLimit();

            if (booked >= limit) {
                promptSlotsFullAndShowAlternatives(fromPhone, doctor, chosenDate);
                return;
            }

            // Prompt for shift / preferred time
            promptShiftOrTimeSelection(fromPhone, doctor, chosenDate);
        } catch (Exception e) {
            logger.error("Error handling appointment date selection: {}", e.getMessage(), e);
            whatsAppClient.sendTextMessage(fromPhone, "Sorry, there was an issue processing your appointment date. Please try again.");
        }
    }

    private void promptShiftOrTimeSelection(String fromPhone, Doctor doc, LocalDate chosenDate) {
        userPendingDoctorBooking.remove(fromPhone);
        userPendingTimeBooking.put(fromPhone, new PendingTimeBooking(doc.getId(), chosenDate));

        String dateStr = chosenDate.format(DateTimeFormatter.ofPattern("EEEE, d MMM yyyy"));
        String shift1 = (doc.getFirstHalfTime() != null && !doc.getFirstHalfTime().isBlank()) ? doc.getFirstHalfTime() : "09:00 AM - 01:00 PM";
        String shift2 = (doc.getSecondHalfTime() != null && !doc.getSecondHalfTime().isBlank()) ? doc.getSecondHalfTime() : "05:00 PM - 09:00 PM";

        String msg = String.format(
                "⏰ *Select Consultation Shift or Preferred Time*\n\n" +
                "👨‍⚕️ *Doctor:* %s (%s)\n" +
                "📅 *Date:* %s\n" +
                "🚪 *Room:* %s | ⏱️ *Slot Duration:* ~%d mins\n\n" +
                "🌅 *1st Half (Morning):* %s\n" +
                "🌆 *2nd Half (Evening):* %s\n\n" +
                "👉 *Tap a shift below, OR reply directly with your preferred time* (e.g. \"10:30 AM\", \"11:00\", \"6:00 PM\").\n" +
                "_We will map you to the exact or nearest best available time slot!_",
                doc.getName(),
                doc.getQualification() != null ? doc.getQualification() : doc.getDepartment(),
                dateStr,
                doc.getRoomNumber() != null ? doc.getRoomNumber() : "101",
                doc.getConsultationDurationMinutes(),
                shift1,
                shift2
        );

        String idPrefixM = "SHIFT_M_" + doc.getId() + "_" + chosenDate;
        String idPrefixE = "SHIFT_E_" + doc.getId() + "_" + chosenDate;

        whatsAppClient.sendInteractiveButtons(
                fromPhone,
                msg,
                List.of(
                        new WhatsAppClientService.ButtonOption(idPrefixM, "🌅 Morning Shift"),
                        new WhatsAppClientService.ButtonOption(idPrefixE, "🌆 Evening Shift")
                )
        );
    }

    private void handleShiftSelection(String fromPhone, String payload) {
        try {
            boolean isMorning = payload.startsWith("SHIFT_M_");
            String data = payload.replace(isMorning ? "SHIFT_M_" : "SHIFT_E_", "");
            int splitIdx = data.indexOf("_");
            if (splitIdx < 0) return;

            Long doctorId = Long.parseLong(data.substring(0, splitIdx));
            String dateStr = data.substring(splitIdx + 1);
            LocalDate chosenDate = LocalDate.parse(dateStr);

            bookAppointmentWithPreferredTime(fromPhone, doctorId, chosenDate, isMorning ? "MORNING" : "EVENING");
        } catch (Exception e) {
            logger.error("Error in handleShiftSelection: {}", e.getMessage(), e);
            whatsAppClient.sendTextMessage(fromPhone, "Sorry, there was an issue selecting your shift. Please try again.");
        }
    }

    private void promptSlotsFullAndShowAlternatives(String fromPhone, Doctor doc, LocalDate fullDate) {
        LocalDate today = LocalDate.now();
        List<Map<String, String>> altRows = new ArrayList<>();

        for (int i = 0; i < 7; i++) {
            LocalDate d = today.plusDays(i);
            if (d.equals(fullDate)) continue;

            if (!doc.isAvailableOnDay(d.getDayOfWeek())) continue;

            int booked = appointmentRepository.countByDoctorIdAndAppointmentDate(doc.getId(), d);
            int remaining = Math.max(0, doc.getDailyTokenLimit() - booked);

            if (remaining > 0) {
                String title;
                if (d.equals(today)) title = "Today (" + d.format(DateTimeFormatter.ofPattern("EEE, d MMM")) + ")";
                else if (d.equals(today.plusDays(1))) title = "Tomorrow (" + d.format(DateTimeFormatter.ofPattern("EEE, d MMM")) + ")";
                else title = d.format(DateTimeFormatter.ofPattern("EEE, d MMM yyyy"));
                if (title.length() > 24) title = title.substring(0, 24);

                String desc = "🟢 " + remaining + " tokens available (Next: #" + String.format("%02d", booked + 1) + ")";
                if (desc.length() > 72) desc = desc.substring(0, 72);

                altRows.add(Map.of(
                        "id", "APPTDATE_" + doc.getId() + "_" + d.toString(),
                        "title", title,
                        "description", desc
                ));
            }
        }

        String fullDateName = fullDate.format(DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy"));

        if (altRows.isEmpty()) {
            whatsAppClient.sendTextMessage(
                    fromPhone,
                    "⚠️ *All consultation slots for " + doc.getName() + " on " + fullDateName + " are fully booked!*\n\n" +
                    "There are no immediate open slots over the next week. Please check back tomorrow or choose another specialist at the hospital."
            );
            return;
        }

        String alertText = String.format(
                "⚠️ *No Slots Available on %s!*\n\n" +
                "All %d daily tokens for *%s* on this date are fully booked.\n\n" +
                "👉 *Please select another date with available slots below:*",
                fullDateName,
                doc.getDailyTokenLimit(),
                doc.getName()
        );

        whatsAppClient.sendInteractiveList(
                fromPhone,
                "Other Open Dates",
                alertText,
                "Select Other Date 📅",
                altRows
        );
    }

    private void bookAppointmentForDoctor(String fromPhone, Long doctorId) {
        bookAppointmentWithPreferredTime(fromPhone, doctorId, LocalDate.now(), "DEFAULT");
    }

    private void bookAppointmentForDoctorAndDate(String fromPhone, Long doctorId, LocalDate appointmentDate) {
        bookAppointmentWithPreferredTime(fromPhone, doctorId, appointmentDate, "DEFAULT");
    }

    private void bookAppointmentWithPreferredTime(String fromPhone, Long doctorId, LocalDate appointmentDate, String userTimeOrShift) {
        Doctor doctor = doctorRepository.findById(doctorId).orElseThrow();
        Hospital hospital = doctor.getHospital();

        List<Appointment> bookedAppts = appointmentRepository.findByDoctorIdAndAppointmentDateOrderBySerialNumberAsc(doctorId, appointmentDate);
        OpdScheduleService.SlotMatchResult match = opdScheduleService.findNearestAvailableSlot(doctor, appointmentDate, bookedAppts, userTimeOrShift);

        if (match == null) {
            promptSlotsFullAndShowAlternatives(fromPhone, doctor, appointmentDate);
            return;
        }

        OpdScheduleService.DoctorSlot slot = match.slot();
        int tokenNumber = slot.slotNumber();
        String timeSlotDescription = slot.timeWindow() + " (" + slot.shiftName() + ")";

        // Generate clean random 6-digit PIN (e.g. 849201)
        String qrToken;
        do {
            qrToken = String.format("%06d", java.util.concurrent.ThreadLocalRandom.current().nextInt(100000, 1000000));
        } while (appointmentRepository.findByQrCodeToken(qrToken).isPresent());

        Appointment appointment = new Appointment(hospital, doctor, fromPhone, "Patient",
                appointmentDate, timeSlotDescription, tokenNumber, qrToken);

        appointment = appointmentRepository.save(appointment);

        // Clear any pending date/time booking states
        userPendingDoctorBooking.remove(fromPhone);
        userPendingTimeBooking.remove(fromPhone);

        // Generate Branded PDF Slip with Dual QR Codes (in-memory)
        try {
            pdfService.generatePdfSlip(appointment);
        } catch (Exception e) {
            logger.error("Error generating PDF slip for appointment: {}", e.getMessage(), e);
        }

        String pdfUrl = "https://mediassist-1hdl.onrender.com/api/v1/appointments/" + appointment.getId() + "/pdf";
        String dateFormatted = appointmentDate.format(DateTimeFormatter.ofPattern("EEEE, MMMM d, yyyy"));

        String noticeBlock = (match.noticeMessage() != null && !match.noticeMessage().isBlank())
                ? "\n" + match.noticeMessage() + "\n" : "";

        String confirmation = String.format("""
            🎉 *OPD Consultation Confirmed!*
            
            🏥 *Hospital:* %s
            👨‍⚕️ *Doctor:* %s (%s)
            🎯 *Department:* %s
            🚪 *OPD Room:* %s
            📅 *Date:* %s
            
            🎫 *Your Token Number:* #%02d
            ⏰ *Tentative Time:* %s
            🚪 *Reporting Time:* %s (Please arrive 15 mins early)
            %s
            🔐 *Check-in PIN:* `%s`
            💵 *Consultation Fee:* ₹%d
            
            📄 *Download A4 OP Case Sheet Slip:*
            %s
            
            _💡 Note: When you arrive, receptionist or doctor will scan the START QR code on your slip to begin consultation._
            """,
                hospital.getName(),
                doctor.getName(),
                doctor.getQualification() != null ? doctor.getQualification() : doctor.getDepartment(),
                doctor.getDepartment(),
                doctor.getRoomNumber() != null ? doctor.getRoomNumber() : "101",
                dateFormatted,
                tokenNumber,
                slot.timeWindow() + " (" + slot.shiftName() + ")",
                slot.reportingTime(),
                noticeBlock,
                qrToken,
                (int) doctor.getConsultationFee(),
                pdfUrl
        );

        whatsAppClient.sendTextMessage(fromPhone, confirmation);

        // Also deliver the PDF document file directly into the WhatsApp conversation
        try {
            whatsAppClient.sendDocumentMessage(
                    fromPhone,
                    pdfUrl,
                    "📄 Official OP Case Sheet (Token #" + String.format("%02d", tokenNumber) + " - " + appointmentDate.format(DateTimeFormatter.ofPattern("d MMM")) + ")",
                    "Appointment_Slip_Token_" + tokenNumber + ".pdf"
            );
        } catch (Exception e) {
            logger.warn("Failed to dispatch PDF document attachment: {}", e.getMessage());
        }
    }

    private LocalDate parseUserDateInput(String lower) {
        LocalDate today = LocalDate.now();
        String trimmed = lower.trim().toLowerCase();
        if (trimmed.equals("today") || trimmed.contains("today") || trimmed.contains("aaj")) return today;
        if (trimmed.equals("tomorrow") || trimmed.contains("tomorrow") || trimmed.contains("kal")) return today.plusDays(1);
        if (trimmed.contains("day after") || trimmed.contains("parson")) return today.plusDays(2);

        try {
            return LocalDate.parse(trimmed);
        } catch (Exception ignored) {}

        try {
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\b(\\d{1,2})(?:st|nd|rd|th)?\\b").matcher(trimmed);
            if (m.find()) {
                int day = Integer.parseInt(m.group(1));
                if (day >= 1 && day <= 31) {
                    LocalDate candidate = today.withDayOfMonth(day);
                    if (candidate.isBefore(today)) candidate = candidate.plusMonths(1);
                    return candidate;
                }
            }
        } catch (Exception ignored) {}
        return null;
    }

    private void handleDoctorRatingSubmission(String fromPhone, String rateId) {
        try {
            // Format: DOC_RATE_{stars}_{apptId}
            String payload = rateId.replace("DOC_RATE_", "");
            String[] parts = payload.split("_");
            int stars = Integer.parseInt(parts[0]);
            Long apptId = Long.parseLong(parts[1]);

            Appointment appt = appointmentRepository.findById(apptId).orElse(null);
            if (appt != null) {
                appt.setRating(stars);
                appointmentRepository.save(appt);
                if (appt.getDoctor() != null) {
                    Doctor doc = appt.getDoctor();
                    doc.addReviewRating(stars);
                    doctorRepository.save(doc);
                }
            }

            String starsVisual = "⭐".repeat(stars);
            String thankYouMsg = String.format(
                    "🙏 *Thank You for Rating!*\n\n" +
                    "You rated your consultation: %s (*%d / 5 Stars*).\n\n" +
                    "Your feedback helps future patients choose the best care and helps our hospital continuously improve. Wishing you good health and a speedy recovery! 🌿\n\n" +
                    "📸 *Need assistance with your prescription?*\n" +
                    "Please take a photo or send a PDF of your doctor's prescription right here in this chat! MediAssist will:\n" +
                    "• Explain your medicines & dosages in simple words\n" +
                    "• Schedule daily dose reminder alarms on WhatsApp\n" +
                    "• Safely archive it in your digital medical wardrobe",
                    starsVisual, stars
            );

            whatsAppClient.sendTextMessage(fromPhone, thankYouMsg);
        } catch (Exception e) {
            logger.error("Error processing doctor rating: {}", e.getMessage(), e);
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
            whatsAppClient.sendTextMessage(fromPhone, "🔍 *Prescription received!* Reviewing your prescribed medications and scheduling your daily reminder alarms... ⏳");

            // Attempt to download the real image from Meta WhatsApp API (with Bearer redirect support)
            byte[] fileBytes = null;
            if (mediaId != null) {
                fileBytes = whatsAppClient.downloadMedia(mediaId);
            }

            // Analyze prescription & extract medication alarms
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
                    ⚠️ *Prescription Review Notice:*
                    We received your document, but were unable to read the medications clearly from the handwriting or image quality.
                    
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
            reply.append("📋 *Prescription Review & Medication Schedule*\n\n");
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
            
            Your audio message has been received and processed.
            • We have noted your symptoms.
            • If you would like to book an appointment with an on-duty doctor, simply share your current location pin via WhatsApp!
            """);
    }

    private String detectSpecialty(String text) {
        if (text == null) return null;
        String lower = text.toLowerCase();
        if (lower.contains("cardio") || lower.contains("heart") || lower.contains("chest pain") || lower.contains("palpitation") || lower.contains("hypertension")) {
            return "Cardiology";
        }
        if (lower.contains("ortho") || lower.contains("bone") || lower.contains("fracture") || lower.contains("joint") || lower.contains("knee") || lower.contains("back pain")) {
            return "Orthopedics";
        }
        if (lower.contains("pedia") || lower.contains("child") || lower.contains("baby") || lower.contains("infant") || lower.contains("kid")) {
            return "Pediatrics";
        }
        if (lower.contains("derma") || lower.contains("skin") || lower.contains("rash") || lower.contains("acne") || lower.contains("eczema") || lower.contains("itching")) {
            return "Dermatology";
        }
        if (lower.contains("ent") || lower.contains("ear") || lower.contains("throat") || lower.contains("sinus") || lower.contains("tonsil")) {
            return "ENT";
        }
        if (lower.contains("gynae") || lower.contains("obstetric") || lower.contains("pregnancy") || lower.contains("period") || lower.contains("menstrual")) {
            return "Gynecology & Obstetrics";
        }
        if (lower.contains("neuro") || lower.contains("brain") || lower.contains("migraine") || lower.contains("headache") || lower.contains("seizure")) {
            return "Neurology";
        }
        if (lower.contains("onco") || lower.contains("cancer") || lower.contains("tumor") || lower.contains("chemotherapy")) {
            return "Oncology";
        }
        if (lower.contains("gastro") || lower.contains("stomach") || lower.contains("digestion") || lower.contains("acidity") || lower.contains("liver")) {
            return "Gastroenterology";
        }
        if (lower.contains("pulmo") || lower.contains("lung") || lower.contains("asthma") || lower.contains("breath") || lower.contains("respiratory")) {
            return "Pulmonology";
        }
        if (lower.contains("nephro") || lower.contains("kidney") || lower.contains("renal") || lower.contains("dialysis") || lower.contains("stone")) {
            return "Nephrology";
        }
        if (lower.contains("eye") || lower.contains("vision") || lower.contains("cataract")) {
            return "Ophthalmology";
        }
        if (lower.contains("medicine") || lower.contains("physician") || lower.contains("internal med")
                || lower.contains("fever") || lower.contains("weakness") || lower.contains("cold") || lower.contains("infection") || lower.contains("fatigue")) {
            return "General Medicine";
        }
        return null;
    }

    private String formatDoctorButtonTitle(Doctor doc) {
        String name = doc.getName();
        String dept = doc.getDepartment() != null ? doc.getDepartment() : "Gen";
        String shortDept = switch (dept.toLowerCase()) {
            case "cardiology" -> "Cardio";
            case "pediatrics" -> "Pedia";
            case "orthopedics" -> "Ortho";
            case "dermatology" -> "Derma";
            case "general medicine", "internal medicine" -> "GenMed";
            case "ent" -> "ENT";
            case "gynecology", "gynecology & obstetrics" -> "Gynae";
            case "neurology" -> "Neuro";
            case "oncology" -> "Onco";
            case "gastroenterology" -> "Gastro";
            case "pulmonology" -> "Pulmo";
            case "nephrology" -> "Nephro";
            case "ophthalmology" -> "Eye";
            case "urology" -> "Uro";
            default -> dept.length() > 6 ? dept.substring(0, 6) : dept;
        };

        String cleanName = name.replaceFirst("(?i)^dr\\.?\\s*", "").trim();
        String candidate = "Dr. " + cleanName.split("\\s+")[0] + " (" + shortDept + ")";
        if (candidate.length() <= 20) {
            return candidate;
        }
        String compact = cleanName.split("\\s+")[0] + " (" + shortDept + ")";
        if (compact.length() <= 20) {
            return compact;
        }
        return candidate.substring(0, Math.min(candidate.length(), 20));
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

    private boolean isGreeting(String text) {
        if (text == null) return false;
        String t = text.trim().toLowerCase().replaceAll("[^a-z\\s]", "");
        return t.matches("^(hi+|hey+|hello+|namaste|namaskar|hola|start|help|menu|good\\s*(morning|afternoon|evening)|restart|info)(\\s+.*)?$");
    }

    private void sendCustomWelcomeMessage(String fromPhone) {
        String welcome = """
            👋 *Welcome to MediAssist!* 🩺
            _Your 24/7 Smart Healthcare & OPD Assistant_

            I am here to assist you with fast healthcare services:
            • 🩺 *Clinical Guidance:* Type your symptoms anytime.
            • 🏥 *Nearby Hospitals:* Tap below or send your location.
            • 🎫 *Instant OPD Token:* Book queue appointments with QR slips.
            • 💊 *Medication Alarms:* Send prescription photos or set reminders.
            • 📁 *Report Wardrobe:* Send lab reports/PDFs for automated summaries.

            👉 *Tap a quick option below, share your location, or type how you are feeling:*
            """;

        List<WhatsAppClientService.ButtonOption> buttons = List.of(
                new WhatsAppClientService.ButtonOption("DISCOVER_MAPS", "🗺️ Nearby Hospitals"),
                new WhatsAppClientService.ButtonOption("DISCOVER_INSTANT", "⚡ Instant OPD Token"),
                new WhatsAppClientService.ButtonOption("MY_ALARMS", "💊 My Alarms")
        );

        whatsAppClient.sendInteractiveButtons(fromPhone, welcome, buttons);
    }

    private void showMyAlarms(String fromPhone) {
        var reminders = reminderService.getActiveReminders(fromPhone);
        if (reminders.isEmpty()) {
            whatsAppClient.sendTextMessage(fromPhone, "📋 You have no active medication alarms.\n\nTo schedule one, message me:\n👉 *'Remind me to take Paracetamol at 8:00 PM'*");
        } else {
            // Group reminders by scheduled time node (HH:mm)
            Map<String, List<com.med.assistant.model.MedicationReminder>> nodes = reminders.stream()
                    .collect(java.util.stream.Collectors.groupingBy(
                            r -> r.getReminderTime() != null ? r.getReminderTime() : "Unscheduled",
                            java.util.TreeMap::new,
                            java.util.stream.Collectors.toList()
                    ));

            StringBuilder sb = new StringBuilder("⏰ *Your Medication Alarm Nodes & Schedule:*\n\n");
            for (Map.Entry<String, List<com.med.assistant.model.MedicationReminder>> entry : nodes.entrySet()) {
                String timeNode = entry.getKey();
                List<com.med.assistant.model.MedicationReminder> meds = entry.getValue();

                sb.append("🕒 *Time Node: ").append(timeNode).append(" IST* (")
                  .append(meds.size()).append(meds.size() == 1 ? " medicine" : " medicines").append(")\n");

                for (com.med.assistant.model.MedicationReminder r : meds) {
                    String dosage = (r.getDosageInstruction() != null && !r.getDosageInstruction().isBlank())
                            ? " — " + r.getDosageInstruction() : "";
                    String statusIcon = r.getStatus() == com.med.assistant.model.MedicationReminder.AdherenceStatus.TAKEN ? "✅ Taken"
                            : (r.getStatus() == com.med.assistant.model.MedicationReminder.AdherenceStatus.SNOOZED ? "⏰ Snoozed" : "⏳ Active");
                    sb.append("  • 💊 *").append(r.getMedicineName()).append("*").append(dosage)
                      .append(" [").append(statusIcon).append("]\n");
                }
                sb.append("\n");
            }
            sb.append("🔔 *How Alarms Work:*\n");
            sb.append("At each time node, you will receive an alert with *[✅ Taken]*, *[⏰ Snooze 15m]*, and *[⏰ Snooze 30m]* buttons!\n\n");
            sb.append("💡 *To cancel all alarms*, simply message: *\"Cancel all alarms\"* or *\"Stop alarms\"*");
            whatsAppClient.sendTextMessage(fromPhone, sb.toString());
        }
    }

    private void handleQueueStatusCheck(String fromPhone) {
        List<Appointment> userAppts = appointmentRepository.findByPatientPhoneOrderByAppointmentDateDesc(fromPhone);
        List<Appointment> todayActive = userAppts.stream()
                .filter(a -> a.getAppointmentDate().equals(LocalDate.now()) && a.getStatus() != Appointment.Status.CANCELLED)
                .toList();

        if (todayActive.isEmpty()) {
            whatsAppClient.sendTextMessage(fromPhone, """
                📋 *No Active OPD Token for Today!*
                
                You don't have any booked OPD queue tokens for today.
                
                To book an instant token with a doctor, tap:
                👉 *[⚡ Instant OPD Token]* or message *"Find hospital"*
                """);
            return;
        }

        Appointment appt = todayActive.get(0);
        Doctor doctor = appt.getDoctor();
        Hospital hospital = appt.getHospital();

        List<Appointment> docAppts = appointmentRepository.findByDoctorIdAndAppointmentDateOrderBySerialNumberAsc(doctor.getId(), LocalDate.now());

        // Calculate currently serving token
        int currentlyServing = 0;
        for (Appointment a : docAppts) {
            if (a.getStatus() == Appointment.Status.CHECKED_IN) {
                currentlyServing = a.getSerialNumber();
                break;
            }
        }
        if (currentlyServing == 0 && !docAppts.isEmpty()) {
            currentlyServing = Math.max(1, appt.getSerialNumber() - 1);
        }

        int ahead = (int) docAppts.stream()
                .filter(a -> a.getSerialNumber() < appt.getSerialNumber() 
                        && a.getStatus() != Appointment.Status.COMPLETED 
                        && a.getStatus() != Appointment.Status.CANCELLED)
                .count();

        int estWaitMinutes = ahead * 15;

        String pdfUrl = "https://mediassist-1hdl.onrender.com/api/v1/appointments/" + appt.getId() + "/pdf";

        String msg = """
            🎫 *LIVE OPD QUEUE STATUS* ⏱️
            
            🏥 *Hospital:* %s
            👨‍⚕️ *Doctor:* %s (%s)
            🚪 *Consultation Room:* %s
            
            👉 *Your Queue Token:* #%02d
            ⏰ *Tentative Time Window:* %s
            🔑 *Check-In Code:* %s
            
            🔔 *Live Waiting Room Update:*
            • Currently in Consultation: *Token #%02d*
            • Patients Ahead of You: *%d %s*
            • Estimated Wait Time: *~%d minutes*
            
            💡 *Zero-Crowd Guideline:*
            Please plan to arrive at Room %s around your assigned window so you never have to wait in physical lines!
            
            📥 *Download Slip:* %s
            """.formatted(
                hospital.getName(),
                doctor.getName(),
                doctor.getDepartment(),
                doctor.getRoomNumber() != null ? doctor.getRoomNumber() : "OPD Desk",
                appt.getSerialNumber(),
                appt.getTimeSlot() != null ? appt.getTimeSlot() : "On Schedule",
                appt.getQrCodeToken(),
                currentlyServing,
                ahead,
                ahead == 1 ? "patient" : "patients",
                estWaitMinutes,
                doctor.getRoomNumber() != null ? doctor.getRoomNumber() : "OPD",
                pdfUrl
        );

        whatsAppClient.sendTextMessage(fromPhone, msg);
    }
}
