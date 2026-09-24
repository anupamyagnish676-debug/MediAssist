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
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.*;

@RestController
@RequestMapping("/api/v1/simulator")
@CrossOrigin(origins = "*")
public class SimulatorApiController {

    private static final Logger logger = LoggerFactory.getLogger(SimulatorApiController.class);

    private final LocationService locationService;
    private final GeminiAiService geminiAiService;
    private final ReportWardrobeService wardrobeService;
    private final MedicationReminderService reminderService;
    private final AppointmentSlipPdfService pdfService;
    private final HospitalRepository hospitalRepository;
    private final DoctorRepository doctorRepository;
    private final AppointmentRepository appointmentRepository;

    public SimulatorApiController(LocationService locationService,
                                  GeminiAiService geminiAiService,
                                  ReportWardrobeService wardrobeService,
                                  MedicationReminderService reminderService,
                                  AppointmentSlipPdfService pdfService,
                                  HospitalRepository hospitalRepository,
                                  DoctorRepository doctorRepository,
                                  AppointmentRepository appointmentRepository) {
        this.locationService = locationService;
        this.geminiAiService = geminiAiService;
        this.wardrobeService = wardrobeService;
        this.reminderService = reminderService;
        this.pdfService = pdfService;
        this.hospitalRepository = hospitalRepository;
        this.doctorRepository = doctorRepository;
        this.appointmentRepository = appointmentRepository;
    }

    public record SimulatorRequest(
            String patientPhone,
            String messageType,
            String text,
            Double latitude,
            Double longitude,
            String actionId
    ) {}

    public record SimulatorResponse(
            String replyText,
            String responseType, // "text", "list", "buttons", "document"
            List<Map<String, String>> interactiveOptions,
            String documentUrl,
            String documentName,
            Integer tokenNumber
    ) {}

    @PostMapping("/chat")
    public ResponseEntity<SimulatorResponse> handleSimulatedMessage(@RequestBody SimulatorRequest req) {
        String phone = req.patientPhone() != null && !req.patientPhone().isBlank() ? req.patientPhone() : "+919876543210";
        String type = req.messageType() != null ? req.messageType() : "text";

        // 1. Location Simulation
        if ("location".equals(type)) {
            double lat = req.latitude() != null ? req.latitude() : 28.6139;
            double lon = req.longitude() != null ? req.longitude() : 77.2090;

            List<LocationService.NearbyHospitalResult> nearby = locationService.findNearbyHospitals(lat, lon, 25.0);
            if (nearby.isEmpty()) {
                // If no hospital within 25km, expand search so simulator works regardless of GPS location
                nearby = locationService.findNearbyHospitals(lat, lon, 25000.0);
            }
            if (nearby.isEmpty()) {
                return ResponseEntity.ok(new SimulatorResponse(
                        "🏥 No registered hospitals found in the network yet.\n\nTo test doctor bookings and QR token slips, please onboard a hospital or click 'Load Demo Data' in the Admin Portal.",
                        "text", null, null, null, null));
            }

            List<Map<String, String>> options = new ArrayList<>();
            for (LocationService.NearbyHospitalResult res : nearby) {
                options.add(Map.of(
                        "id", "HOSP_" + res.hospital().getId(),
                        "title", res.hospital().getName(),
                        "subtitle", res.distanceKm() + " km away • " + res.availableDoctorsCount() + " doctors available"
                ));
            }

            return ResponseEntity.ok(new SimulatorResponse(
                    "🏥 Found " + nearby.size() + " hospitals near you. Tap a hospital to see available doctors:",
                    "list", options, null, null, null));
        }

        // 2. Action Simulation (Interactive Buttons or List Item Selected)
        if ("action".equals(type) && req.actionId() != null) {
            String act = req.actionId();

            if (act.startsWith("HOSP_")) {
                Long hospId = Long.parseLong(act.replace("HOSP_", ""));
                List<Doctor> doctors = doctorRepository.findByHospitalIdAndAvailableTodayTrue(hospId);

                if (doctors.isEmpty()) {
                    return ResponseEntity.ok(new SimulatorResponse(
                            "No doctors available today at this hospital.", "text", null, null, null, null));
                }

                List<Map<String, String>> docButtons = new ArrayList<>();
                for (Doctor d : doctors) {
                    docButtons.add(Map.of(
                            "id", "DOC_" + d.getId(),
                            "title", "Book " + d.getName() + " (" + d.getDepartment() + ")"
                    ));
                }

                return ResponseEntity.ok(new SimulatorResponse(
                        "👨‍⚕️ Available doctors today. Select a doctor to confirm your booking:",
                        "buttons", docButtons, null, null, null));
            }

            if (act.startsWith("DOC_")) {
                Long doctorId = Long.parseLong(act.replace("DOC_", ""));
                Doctor doc = doctorRepository.findById(doctorId).orElseThrow();
                Hospital hosp = doc.getHospital();

                int todayBookings = appointmentRepository.countByDoctorIdAndAppointmentDate(doctorId, LocalDate.now());
                int nextTokenNumber = todayBookings + 1;
                String qrToken = UUID.randomUUID().toString().substring(0, 8).toUpperCase();

                Appointment appt = new Appointment(hosp, doc, phone, "Patient", LocalDate.now(), "11:00 AM", nextTokenNumber, qrToken);
                appointmentRepository.save(appt);

                String pdfUrl = null;
                try {
                    pdfService.generatePdfSlip(appt);
                    pdfUrl = "/api/v1/appointments/" + appt.getId() + "/pdf";
                } catch (Exception ex) {
                    logger.error("Could not generate PDF slip: {}", ex.getMessage(), ex);
                    // Still provide the URL — the download endpoint will generate on-demand
                    pdfUrl = "/api/v1/appointments/" + appt.getId() + "/pdf";
                }

                String room = doc.getRoomNumber() != null ? doc.getRoomNumber() : "101";
                String text = """
                        🎉 Appointment Confirmed!
                        
                        🏥 Hospital: %s
                        👨‍⚕️ Doctor: %s (%s)
                        🚪 OPD Room: %s
                        📅 Date: Today, %s
                        ⏰ Time Slot: 11:00 AM
                        
                        👉 YOUR QUEUE TOKEN: #%02d
                        
                        📄 Your official branded PDF appointment slip with QR code is ready. Show this at the hospital desk for instant check-in!
                        """.formatted(hosp.getName(), doc.getName(), doc.getDepartment(), room, LocalDate.now(), nextTokenNumber);

                return ResponseEntity.ok(new SimulatorResponse(
                        text, "document", null,
                        pdfUrl,
                        "Appointment_Token_" + nextTokenNumber + ".pdf",
                        nextTokenNumber));
            }

            if (act.startsWith("MED_TAKEN_")) {
                String idPayload = act.replace("MED_TAKEN_", "");
                List<Long> ids = Arrays.stream(idPayload.split("_"))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .map(Long::parseLong)
                        .toList();
                reminderService.markBatchAsTaken(ids);
                return ResponseEntity.ok(new SimulatorResponse(
                        "✅ Marked as Taken (" + ids.size() + " medication" + (ids.size() == 1 ? "" : "s") + ")! Great job adhering to your schedule. 🌟",
                        "text", null, null, null, null));
            }

            if (act.startsWith("MED_SNOOZE_15_") || act.startsWith("MED_SNOOZE_30_") || act.startsWith("MED_SNOOZE_")) {
                int minutes = act.startsWith("MED_SNOOZE_30_") ? 30 : 15;
                String prefix = act.startsWith("MED_SNOOZE_15_") ? "MED_SNOOZE_15_"
                        : (act.startsWith("MED_SNOOZE_30_") ? "MED_SNOOZE_30_" : "MED_SNOOZE_");
                String idPayload = act.replace(prefix, "");
                List<Long> ids = Arrays.stream(idPayload.split("_"))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .map(Long::parseLong)
                        .toList();
                reminderService.markBatchAsSnoozed(ids, minutes);
                return ResponseEntity.ok(new SimulatorResponse(
                        "⏰ Snoozed for " + minutes + " minutes! Your alarm is set and will alert you again when due. 🔔",
                        "text", null, null, null, null));
            }
        }

        // 3. Text Message Simulation
        String query = req.text() != null ? req.text().trim() : "";
        String lower = query.toLowerCase();

        if (geminiAiService.isEmergency(query)) {
            return ResponseEntity.ok(new SimulatorResponse(
                    geminiAiService.getEmergencyResponse(), "text", null, null, null, null));
        }

        if (lower.contains("my reminder") || lower.contains("show reminder") || lower.contains("list reminder")
                || lower.contains("my alarms") || lower.contains("show alarms") || lower.equals("alarms") || lower.equals("reminders")) {
            var reminders = reminderService.getActiveReminders(phone);
            if (reminders.isEmpty()) {
                return ResponseEntity.ok(new SimulatorResponse(
                        "📋 You have no active medication alarms.\n\nTo schedule one, type:\n👉 'Remind me to take Paracetamol at 8:00 PM'",
                        "text", null, null, null, null));
            }

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

                for (var r : meds) {
                    String dosage = (r.getDosageInstruction() != null && !r.getDosageInstruction().isBlank())
                            ? " — " + r.getDosageInstruction() : "";
                    String status = r.getStatus() == com.med.assistant.model.MedicationReminder.AdherenceStatus.TAKEN ? "✅ Taken"
                            : (r.getStatus() == com.med.assistant.model.MedicationReminder.AdherenceStatus.SNOOZED ? "⏰ Snoozed" : "⏳ Active");
                    sb.append("  • 💊 *").append(r.getMedicineName()).append("*").append(dosage)
                      .append(" [").append(status).append("]\n");
                }
                sb.append("\n");
            }
            sb.append("🔔 Interactive alarms trigger automatically with [Taken] and [Snooze] buttons!");
            return ResponseEntity.ok(new SimulatorResponse(sb.toString(), "text", null, null, null, null));
        }

        if (lower.contains("remind") && (lower.contains(" at ") || lower.contains(" @ "))) {
            String[] parts = query.split("(?i)\\s+(at|@)\\s+");
            if (parts.length >= 2) {
                String med = parts[0].replaceAll("(?i)(remind|me|to|take|set|reminder|for|please|a)", "").trim();
                if (med.isBlank()) med = "Medication";
                String timeStr = parts[1].trim();
                com.med.assistant.model.MedicationReminder created = reminderService.createReminder(phone, med, "Daily Dose", timeStr);
                return ResponseEntity.ok(new SimulatorResponse(
                        "✅ *Medication Alarm Node Created!*\n\n💊 Medicine: " + med + "\n⏰ Time: " + timeStr + " (IST)\n\nYou will receive a notification alert with [Taken] and [Snooze 15m/30m] buttons at this time!",
                        "text", null, null, null, null));
            }
        }

        if (query.toLowerCase().contains("report") || query.toLowerCase().contains("wardrobe") || query.toLowerCase().contains("test")) {
            List<MedicalDocument> found = wardrobeService.searchReports(phone, query.replaceAll("(?i)(send|me|my|report|test|show)", "").trim());
            if (!found.isEmpty()) {
                MedicalDocument d = found.get(0);
                return ResponseEntity.ok(new SimulatorResponse(
                        "📁 Found your report in Report Wardrobe:\n*" + d.getTitle() + "*\n\n" + d.getAiSummary(),
                        "text", null, null, null, null));
            }
        }

        // Triage
        String aiReply = geminiAiService.askMedicalAi(query, "");
        return ResponseEntity.ok(new SimulatorResponse(aiReply, "text", null, null, null, null));
    }
}
