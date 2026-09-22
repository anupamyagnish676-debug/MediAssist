package com.med.assistant.controller;

import com.med.assistant.model.Appointment;
import com.med.assistant.model.Doctor;
import com.med.assistant.model.Hospital;
import com.med.assistant.model.MedicalDocument;
import com.med.assistant.repository.AppointmentRepository;
import com.med.assistant.repository.DoctorRepository;
import com.med.assistant.repository.HospitalRepository;
import com.med.assistant.service.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.*;

@RestController
@RequestMapping("/api/v1/simulator")
@CrossOrigin(origins = "*")
public class SimulatorApiController {

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
                return ResponseEntity.ok(new SimulatorResponse(
                        "No registered hospitals found within 25 km of your location.",
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

                String pdfPath = pdfService.generatePdfSlip(appt);
                appt.setPdfFilePath(pdfPath);
                appointmentRepository.save(appt);

                String text = """
                        🎉 Appointment Confirmed!
                        
                        🏥 Hospital: %s
                        👨‍⚕️ Doctor: %s (%s)
                        🚪 OPD Room: %s
                        📅 Date: Today, %s
                        ⏰ Time Slot: 11:00 AM
                        
                        👉 YOUR QUEUE TOKEN: #%02d
                        
                        📄 Your official branded PDF appointment slip with QR code is attached below. Show this at the hospital desk for instant check-in!
                        """.formatted(hosp.getName(), doc.getName(), doc.getDepartment(), doc.getRoomNumber(), LocalDate.now(), nextTokenNumber);

                return ResponseEntity.ok(new SimulatorResponse(
                        text, "document", null,
                        "/api/v1/appointments/" + appt.getId() + "/pdf",
                        "Appointment_Token_" + nextTokenNumber + ".pdf",
                        nextTokenNumber));
            }

            if (act.startsWith("MED_TAKEN_")) {
                return ResponseEntity.ok(new SimulatorResponse(
                        "✅ Marked as Taken! Great job maintaining your medication schedule. 🌟",
                        "text", null, null, null, null));
            }

            if (act.startsWith("MED_SNOOZE_")) {
                return ResponseEntity.ok(new SimulatorResponse(
                        "⏰ Snoozed for 15 minutes. We will remind you again shortly!",
                        "text", null, null, null, null));
            }
        }

        // 3. Text Message Simulation
        String query = req.text() != null ? req.text().trim() : "";

        if (geminiAiService.isEmergency(query)) {
            return ResponseEntity.ok(new SimulatorResponse(
                    geminiAiService.getEmergencyResponse(), "text", null, null, null, null));
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
