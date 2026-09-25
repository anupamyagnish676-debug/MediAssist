package com.med.assistant.controller;

import com.med.assistant.model.Appointment;
import com.med.assistant.repository.AppointmentRepository;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.*;

@RestController
@RequestMapping("/api/v1/appointments")
@CrossOrigin(origins = "*")
public class AppointmentApiController {

    private final AppointmentRepository appointmentRepository;
    private final com.med.assistant.service.AppointmentSlipPdfService pdfService;
    private final com.med.assistant.service.WhatsAppClientService whatsAppClient;

    public AppointmentApiController(AppointmentRepository appointmentRepository,
                                    com.med.assistant.service.AppointmentSlipPdfService pdfService,
                                    com.med.assistant.service.WhatsAppClientService whatsAppClient) {
        this.appointmentRepository = appointmentRepository;
        this.pdfService = pdfService;
        this.whatsAppClient = whatsAppClient;
    }

    private String cleanToken(String rawToken) {
        if (rawToken == null) return "";
        return rawToken.replace("START:", "")
                       .replace("END:", "")
                       .replace("APPT:", "")
                       .trim();
    }

    /**
     * Receptionist Check-In via QR Code Scanner or Manual PIN.
     */
    @PostMapping("/check-in")
    public ResponseEntity<Map<String, Object>> checkInWithQrCode(@RequestParam String token) {
        String clean = cleanToken(token);

        Optional<Appointment> opt = appointmentRepository.findByQrCodeToken(clean);
        if (opt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "success", false,
                    "message", "Invalid or unrecognized appointment PIN/QR Code: " + clean
            ));
        }

        Appointment appt = opt.get();
        if (appt.getStatus() == Appointment.Status.CONFIRMED) {
            appt.setStatus(Appointment.Status.CHECKED_IN);
            appointmentRepository.save(appt);
        }

        Map<String, Object> resp = new HashMap<>();
        resp.put("success", true);
        resp.put("message", "Check-in successful!");
        resp.put("appointmentId", appt.getId());
        resp.put("qrCodeToken", appt.getQrCodeToken());
        resp.put("serialNumber", appt.getSerialNumber());
        resp.put("patientPhone", appt.getPatientPhone());
        resp.put("patientName", appt.getPatientName() != null ? appt.getPatientName() : "Walk-in Patient");
        resp.put("doctorName", appt.getDoctor() != null ? appt.getDoctor().getName() : "N/A");
        resp.put("department", appt.getDoctor() != null ? appt.getDoctor().getDepartment() : "General");
        resp.put("roomNumber", appt.getDoctor() != null ? appt.getDoctor().getRoomNumber() : "-");
        resp.put("timeSlot", appt.getTimeSlot() != null ? appt.getTimeSlot() : "");
        resp.put("status", appt.getStatus().name());
        return ResponseEntity.ok(resp);
    }

    /**
     * Start Appointment Consultation (Scanned via START QR or entered by doctor/receptionist).
     * Automatically alerts the NEXT waiting patient on WhatsApp that doctor is currently engaged.
     */
    @PostMapping("/start-consultation")
    public ResponseEntity<Map<String, Object>> startConsultation(@RequestParam String token) {
        String clean = cleanToken(token);
        Optional<Appointment> opt = appointmentRepository.findByQrCodeToken(clean);
        if (opt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "success", false,
                    "message", "Invalid appointment token: " + clean
            ));
        }

        Appointment appt = opt.get();
        appt.setStatus(Appointment.Status.IN_CONSULTATION);
        appt.setConsultationStartTime(java.time.LocalDateTime.now());
        appointmentRepository.save(appt);

        // Find next waiting patient for this doctor today
        if (appt.getDoctor() != null) {
            List<Appointment> todayList = appointmentRepository.findByDoctorIdAndAppointmentDateOrderBySerialNumberAsc(
                    appt.getDoctor().getId(), appt.getAppointmentDate());

            Optional<Appointment> nextOpt = todayList.stream()
                    .filter(a -> a.getSerialNumber() > appt.getSerialNumber())
                    .filter(a -> a.getStatus() == Appointment.Status.CHECKED_IN || a.getStatus() == Appointment.Status.CONFIRMED)
                    .findFirst();

            if (nextOpt.isPresent()) {
                Appointment nextPatient = nextOpt.get();
                String docName = appt.getDoctor().getName();
                String room = appt.getDoctor().getRoomNumber() != null ? appt.getDoctor().getRoomNumber() : "OPD Chamber";

                String alertMsg = String.format(
                        "⏳ *Doctor is Currently Engaged*\n\n" +
                        "Hello %s,\n" +
                        "*%s* has started consultation with Token #%02d in Room %s.\n\n" +
                        "👉 *Your Queue Token: #%02d*\n" +
                        "Please relax in the waiting lounge. We will instantly ping you on WhatsApp the moment the doctor is ready for you! 🛋️",
                        nextPatient.getPatientName() != null ? nextPatient.getPatientName() : "Patient",
                        docName,
                        appt.getSerialNumber(),
                        room,
                        nextPatient.getSerialNumber()
                );
                whatsAppClient.sendTextMessage(nextPatient.getPatientPhone(), alertMsg);
            }
        }

        Map<String, Object> resp = new HashMap<>();
        resp.put("success", true);
        resp.put("message", "Consultation started for Token #" + appt.getSerialNumber());
        resp.put("appointmentId", appt.getId());
        resp.put("qrCodeToken", appt.getQrCodeToken());
        resp.put("serialNumber", appt.getSerialNumber());
        resp.put("status", appt.getStatus().name());
        return ResponseEntity.ok(resp);
    }

    /**
     * End Appointment Consultation (Scanned via END QR or entered by doctor/receptionist).
     * 1. Alerts NEXT patient: "🟢 IT'S YOUR TURN! Please step into Room X."
     * 2. Prompts CURRENT patient with WhatsApp 5-Star Rating & Review!
     */
    @PostMapping("/end-consultation")
    public ResponseEntity<Map<String, Object>> endConsultation(@RequestParam String token) {
        String clean = cleanToken(token);
        Optional<Appointment> opt = appointmentRepository.findByQrCodeToken(clean);
        if (opt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "success", false,
                    "message", "Invalid appointment token: " + clean
            ));
        }

        Appointment appt = opt.get();
        appt.setStatus(Appointment.Status.COMPLETED);
        appt.setConsultationEndTime(java.time.LocalDateTime.now());
        appointmentRepository.save(appt);

        // 1. Notify NEXT waiting patient
        if (appt.getDoctor() != null) {
            List<Appointment> todayList = appointmentRepository.findByDoctorIdAndAppointmentDateOrderBySerialNumberAsc(
                    appt.getDoctor().getId(), appt.getAppointmentDate());

            Optional<Appointment> nextOpt = todayList.stream()
                    .filter(a -> a.getSerialNumber() > appt.getSerialNumber())
                    .filter(a -> a.getStatus() == Appointment.Status.CHECKED_IN || a.getStatus() == Appointment.Status.CONFIRMED)
                    .findFirst();

            if (nextOpt.isPresent()) {
                Appointment nextPatient = nextOpt.get();
                String docName = appt.getDoctor().getName();
                String room = appt.getDoctor().getRoomNumber() != null ? appt.getDoctor().getRoomNumber() : "OPD Chamber";

                String turnMsg = String.format(
                        "🟢 *IT'S YOUR TURN!*\n\n" +
                        "Hello %s, *%s* is ready to see you now!\n\n" +
                        "👉 *Your Queue Token: #%02d*\n" +
                        "🚪 Please proceed directly into *Room %s*.",
                        nextPatient.getPatientName() != null ? nextPatient.getPatientName() : "Patient",
                        docName,
                        nextPatient.getSerialNumber(),
                        room
                );
                whatsAppClient.sendTextMessage(nextPatient.getPatientPhone(), turnMsg);
            }
        }

        // 2. Request Rating & Feedback from completed patient via Interactive WhatsApp List
        if (appt.getPatientPhone() != null && !appt.getPatientPhone().isBlank()) {
            String docName = appt.getDoctor() != null ? appt.getDoctor().getName() : "Doctor";
            String dept = appt.getDoctor() != null ? appt.getDoctor().getDepartment() : "General";

            List<Map<String, String>> ratingRows = List.of(
                    Map.of("id", "DOC_RATE_5_" + appt.getId(), "title", "⭐⭐⭐⭐⭐ 5 Stars", "description", "Excellent consultation & care"),
                    Map.of("id", "DOC_RATE_4_" + appt.getId(), "title", "⭐⭐⭐⭐ 4 Stars", "description", "Very good experience"),
                    Map.of("id", "DOC_RATE_3_" + appt.getId(), "title", "⭐⭐⭐ 3 Stars", "description", "Satisfactory / Average"),
                    Map.of("id", "DOC_RATE_2_" + appt.getId(), "title", "⭐⭐ 2 Stars", "description", "Needs improvement"),
                    Map.of("id", "DOC_RATE_1_" + appt.getId(), "title", "⭐ 1 Star", "description", "Unsatisfactory")
            );

            whatsAppClient.sendInteractiveList(
                    appt.getPatientPhone(),
                    "Rate Doctor",
                    "🏥 *Consultation Completed!*\n\nHow was your experience today with *" + docName + "* (" + dept + ")?\n\nPlease select your rating below to help future patients:",
                    "⭐ Rate Consultation",
                    ratingRows
            );
        }

        Map<String, Object> resp = new HashMap<>();
        resp.put("success", true);
        resp.put("message", "Consultation ended for Token #" + appt.getSerialNumber());
        resp.put("appointmentId", appt.getId());
        resp.put("qrCodeToken", appt.getQrCodeToken());
        resp.put("serialNumber", appt.getSerialNumber());
        resp.put("status", appt.getStatus().name());
        return ResponseEntity.ok(resp);
    }

    /**
     * Receptionist View: List of appointments for a hospital today.
     */
    @GetMapping("/today")
    public ResponseEntity<List<Appointment>> getTodayAppointments(@RequestParam(defaultValue = "1") Long hospitalId) {
        return ResponseEntity.ok(appointmentRepository.findByHospitalIdAndAppointmentDate(hospitalId, LocalDate.now()));
    }

    /**
     * Live TV Waiting Room Queue: returns currently serving & upcoming tokens.
     */
    @GetMapping("/live-queue")
    public ResponseEntity<Map<String, Object>> getLiveQueue(@RequestParam(defaultValue = "1") Long hospitalId) {
        List<Appointment> today = appointmentRepository.findByHospitalIdAndAppointmentDate(hospitalId, LocalDate.now());

        List<Appointment> checkedIn = today.stream()
                .filter(a -> a.getStatus() == Appointment.Status.CHECKED_IN)
                .sorted(Comparator.comparingInt(Appointment::getSerialNumber))
                .toList();

        Appointment currentServing = checkedIn.isEmpty() ? null : checkedIn.get(0);
        List<Appointment> waitingList = checkedIn.size() > 1 ? checkedIn.subList(1, checkedIn.size()) : Collections.emptyList();

        Map<String, Object> response = new HashMap<>();
        response.put("hospitalId", hospitalId);
        response.put("currentServingToken", currentServing != null ? currentServing.getSerialNumber() : null);
        response.put("currentServingDoctor", currentServing != null ? (currentServing.getDoctor() != null ? currentServing.getDoctor().getName() : "Doctor") : "Waiting for next patient");
        response.put("currentServingRoom", currentServing != null && currentServing.getDoctor() != null ? currentServing.getDoctor().getRoomNumber() : "-");
        response.put("waitingQueue", waitingList.stream().map(a -> {
            Map<String, Object> item = new HashMap<>();
            item.put("token", a.getSerialNumber());
            item.put("doctor", a.getDoctor() != null ? a.getDoctor().getName() : "Doctor");
            item.put("room", a.getDoctor() != null ? a.getDoctor().getRoomNumber() : "-");
            item.put("timeSlot", a.getTimeSlot() != null ? a.getTimeSlot() : "");
            return item;
        }).toList());

        return ResponseEntity.ok(response);
    }

    @GetMapping("/{id}/pdf")
    public ResponseEntity<byte[]> downloadPdf(@PathVariable Long id) {
        Optional<Appointment> opt = appointmentRepository.findById(id);
        if (opt.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        try {
            byte[] pdfBytes = pdfService.generateAndGetBytes(opt.get());
            if (pdfBytes == null || pdfBytes.length == 0) {
                return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
            }
            return ResponseEntity.ok()
                    .header(org.springframework.http.HttpHeaders.CONTENT_TYPE, "application/pdf")
                    .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"Appointment_Token_" + opt.get().getSerialNumber() + ".pdf\"")
                    .body(pdfBytes);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
}
