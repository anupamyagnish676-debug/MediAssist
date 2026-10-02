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
    private final com.med.assistant.repository.UserRepository userRepository;
    private final com.med.assistant.service.ReportWardrobeService wardrobeService;
    private final com.med.assistant.service.MedicationReminderService reminderService;
    private final com.med.assistant.repository.DoctorRepository doctorRepository;
    private final com.med.assistant.repository.HospitalRepository hospitalRepository;

    public AppointmentApiController(AppointmentRepository appointmentRepository,
                                    com.med.assistant.service.AppointmentSlipPdfService pdfService,
                                    com.med.assistant.service.WhatsAppClientService whatsAppClient,
                                    com.med.assistant.repository.UserRepository userRepository,
                                    com.med.assistant.service.ReportWardrobeService wardrobeService,
                                    com.med.assistant.service.MedicationReminderService reminderService,
                                    com.med.assistant.repository.DoctorRepository doctorRepository,
                                    com.med.assistant.repository.HospitalRepository hospitalRepository) {
        this.appointmentRepository = appointmentRepository;
        this.pdfService = pdfService;
        this.whatsAppClient = whatsAppClient;
        this.userRepository = userRepository;
        this.wardrobeService = wardrobeService;
        this.reminderService = reminderService;
        this.doctorRepository = doctorRepository;
        this.hospitalRepository = hospitalRepository;
    }

    private String cleanToken(String rawToken) {
        if (rawToken == null) return "";
        return rawToken.replace("START:", "")
                       .replace("END:", "")
                       .replace("APPT:", "")
                       .trim();
    }

    /**
     * Validates that the appointment belongs to the hospital attempting to process it.
     * Prevents cross-hospital scanning, unauthorized check-ins, or consultation manipulations.
     */
    private String validateHospitalAccess(Appointment appt, Long targetHospitalId) {
        if (appt == null || appt.getHospital() == null) return null;
        Long apptHospitalId = appt.getHospital().getId();

        // 1. Explicit hospitalId passed from client (e.g. manager-portal or receptionist)
        if (targetHospitalId != null && !targetHospitalId.equals(apptHospitalId)) {
            return "This appointment slip does not belong to this hospital.";
        }

        // 2. Check authenticated manager if present in Spring Security Context
        try {
            org.springframework.security.core.Authentication auth = org.springframework.security.core.context.SecurityContextHolder.getContext().getAuthentication();
            if (auth != null && auth.isAuthenticated() && !auth.getName().equals("anonymousUser") && userRepository != null) {
                Optional<com.med.assistant.model.User> uOpt = userRepository.findByEmailIgnoreCase(auth.getName());
                if (uOpt.isPresent()) {
                    com.med.assistant.model.User user = uOpt.get();
                    if (user.getRole() == com.med.assistant.model.User.Role.HOSPITAL_MANAGER && user.getHospital() != null) {
                        Long managerHospId = user.getHospital().getId();
                        if (!managerHospId.equals(apptHospitalId)) {
                            return "This appointment slip does not belong to this hospital.";
                        }
                    }
                }
            }
        } catch (Exception ignored) {}

        return null;
    }

    /**
     * Receptionist Check-In via QR Code Scanner or Manual PIN.
     */
    @PostMapping("/check-in")
    public ResponseEntity<Map<String, Object>> checkInWithQrCode(
            @RequestParam String token,
            @RequestParam(required = false) Long hospitalId) {
        String clean = cleanToken(token);

        Optional<Appointment> opt = appointmentRepository.findByQrCodeToken(clean);
        if (opt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "success", false,
                    "message", "Invalid or unrecognized appointment PIN/QR Code: " + clean
            ));
        }

        Appointment appt = opt.get();

        String validationError = validateHospitalAccess(appt, hospitalId);
        if (validationError != null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "success", false,
                    "message", validationError
            ));
        }

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
    public ResponseEntity<Map<String, Object>> startConsultation(
            @RequestParam String token,
            @RequestParam(required = false) Long hospitalId) {
        String clean = cleanToken(token);
        Optional<Appointment> opt = appointmentRepository.findByQrCodeToken(clean);
        if (opt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "success", false,
                    "message", "Invalid appointment token: " + clean
            ));
        }

        Appointment appt = opt.get();

        String validationError = validateHospitalAccess(appt, hospitalId);
        if (validationError != null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "success", false,
                    "message", validationError
            ));
        }

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
    public ResponseEntity<Map<String, Object>> endConsultation(
            @RequestParam String token,
            @RequestParam(required = false) Long hospitalId) {
        String clean = cleanToken(token);
        Optional<Appointment> opt = appointmentRepository.findByQrCodeToken(clean);
        if (opt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "success", false,
                    "message", "Invalid appointment token: " + clean
            ));
        }

        Appointment appt = opt.get();

        String validationError = validateHospitalAccess(appt, hospitalId);
        if (validationError != null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).body(Map.of(
                    "success", false,
                    "message", validationError
            ));
        }

        appt.setStatus(Appointment.Status.COMPLETED);
        appt.setConsultationEndTime(java.time.LocalDateTime.now());
        appointmentRepository.save(appt);

        // 1. Notify NEXT waiting patients with Real-Time Proximity Alerts (Tokens 1, 2, and 3)
        if (appt.getDoctor() != null) {
            List<Appointment> todayList = appointmentRepository.findByDoctorIdAndAppointmentDateOrderBySerialNumberAsc(
                    appt.getDoctor().getId(), appt.getAppointmentDate());

            List<Appointment> waitingList = todayList.stream()
                    .filter(a -> a.getSerialNumber() > appt.getSerialNumber())
                    .filter(a -> a.getStatus() == Appointment.Status.CHECKED_IN || a.getStatus() == Appointment.Status.CONFIRMED)
                    .toList();

            String docName = appt.getDoctor().getName();
            String room = appt.getDoctor().getRoomNumber() != null ? appt.getDoctor().getRoomNumber() : "OPD Chamber";

            // Next patient (1st in line) - Call into chamber
            if (waitingList.size() >= 1) {
                Appointment p1 = waitingList.get(0);
                String turnMsg = String.format(
                        "🟢 *IT'S YOUR TURN NOW!*\n\n" +
                        "Hello %s, *%s* is ready to see you now!\n\n" +
                        "👉 *Your Queue Token: #%02d*\n" +
                        "🚪 Please proceed directly into *Room %s*.",
                        p1.getPatientName() != null ? p1.getPatientName() : "Patient",
                        docName,
                        p1.getSerialNumber(),
                        room
                );
                whatsAppClient.sendTextMessage(p1.getPatientPhone(), turnMsg);
            }

            // 2nd patient in line - Be ready right outside
            if (waitingList.size() >= 2) {
                Appointment p2 = waitingList.get(1);
                String p2Msg = String.format(
                        "⏳ *OPD Queue Update:* Doctor is now seeing Token #%02d.\n\n" +
                        "Hello %s, you are *NEXT IN LINE* (Token #%02d, only 1 patient ahead).\n" +
                        "🚪 Please move near *Room %s* to be ready!",
                        waitingList.get(0).getSerialNumber(),
                        p2.getPatientName() != null ? p2.getPatientName() : "Patient",
                        p2.getSerialNumber(),
                        room
                );
                whatsAppClient.sendTextMessage(p2.getPatientPhone(), p2Msg);
            }

            // 3rd patient in line - Proximity alert (~15 mins)
            if (waitingList.size() >= 3) {
                Appointment p3 = waitingList.get(2);
                String p3Msg = String.format(
                        "🏃 *YOUR TURN IS APPROACHING!* (3rd in queue)\n\n" +
                        "Hello %s,\n" +
                        "*%s* is currently with Token #%02d.\n\n" +
                        "👉 *Your Token: #%02d* (only 2 patients ahead, estimated ~10–15 mins).\n" +
                        "🚪 Please head towards *Room %s* so you don't miss your call!",
                        p3.getPatientName() != null ? p3.getPatientName() : "Patient",
                        docName,
                        waitingList.get(0).getSerialNumber(),
                        p3.getSerialNumber(),
                        room
                );
                whatsAppClient.sendTextMessage(p3.getPatientPhone(), p3Msg);
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

    public record WalkInRequest(
            Long hospitalId,
            Long doctorId,
            String patientName,
            String patientPhone,
            String timeSlot
    ) {}

    /**
     * Issue an instant Walk-in OPD Token at the reception desk.
     */
    @PostMapping("/walk-in")
    public ResponseEntity<?> createWalkInAppointment(@RequestBody WalkInRequest req) {
        if (req.doctorId() == null || req.patientPhone() == null || req.patientPhone().isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "message", "Doctor and Patient Phone are required"));
        }
        Optional<com.med.assistant.model.Doctor> docOpt = doctorRepository.findById(req.doctorId());
        if (docOpt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("success", false, "message", "Doctor not found"));
        }
        com.med.assistant.model.Doctor doctor = docOpt.get();
        com.med.assistant.model.Hospital hospital = doctor.getHospital();

        LocalDate today = LocalDate.now();
        List<Appointment> bookedAppts = appointmentRepository.findByDoctorIdAndAppointmentDateOrderBySerialNumberAsc(doctor.getId(), today);
        int nextSerial = bookedAppts.size() + 1;

        String qrToken;
        do {
            qrToken = String.format("%06d", java.util.concurrent.ThreadLocalRandom.current().nextInt(100000, 1000000));
        } while (appointmentRepository.findByQrCodeToken(qrToken).isPresent());

        String patientName = (req.patientName() != null && !req.patientName().isBlank()) ? req.patientName().trim() : "Walk-in Patient";
        String timeSlot = (req.timeSlot() != null && !req.timeSlot().isBlank()) ? req.timeSlot().trim() : "Walk-in Priority";

        Appointment appt = new Appointment(hospital, doctor, req.patientPhone().trim(), patientName,
                today, timeSlot, nextSerial, qrToken);
        appt.setStatus(Appointment.Status.CHECKED_IN);
        appt = appointmentRepository.save(appt);

        try {
            pdfService.generatePdfSlip(appt);
        } catch (Exception e) {
            // pdf error logged
        }

        // WhatsApp notification
        try {
            String pdfUrl = "https://mediassist-1hdl.onrender.com/api/v1/appointments/" + appt.getId() + "/pdf";
            String msg = String.format("""
                🏥 *Walk-In OPD Token Issued!*
                
                Hello %s,
                Your walk-in token for *%s* (%s) has been generated:
                
                👉 *Token Number: #%02d*
                🚪 *Room:* %s
                📅 *Date:* %s
                
                📄 *Download Appointment Slip:*
                %s
                """,
                patientName,
                doctor.getName(),
                doctor.getDepartment(),
                nextSerial,
                doctor.getRoomNumber() != null ? doctor.getRoomNumber() : "OPD Desk",
                today.toString(),
                pdfUrl
            );
            whatsAppClient.sendTextMessage(req.patientPhone().trim(), msg);
        } catch (Exception we) {
            // non-blocking
        }

        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Walk-in token #" + nextSerial + " issued successfully!",
                "appointmentId", appt.getId(),
                "serialNumber", nextSerial,
                "patientName", patientName,
                "patientPhone", req.patientPhone().trim(),
                "qrCodeToken", qrToken,
                "pdfUrl", "/api/v1/appointments/" + appt.getId() + "/pdf"
        ));
    }

    /**
     * Patient Medical Locker API: returns appointments, prescriptions/lab documents, and active medications.
     */
    @GetMapping("/patient/records")
    public ResponseEntity<?> getPatientMedicalRecords(@RequestParam String phone) {
        if (phone == null || phone.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("error", "Phone number is required"));
        }
        String cleanPhone = phone.trim();
        Set<String> variants = new HashSet<>();
        variants.add(cleanPhone);
        String digits = cleanPhone.replaceAll("[^0-9]", "");
        if (digits.length() == 10) {
            variants.add("+91" + digits);
            variants.add("91" + digits);
            variants.add(digits);
        } else if (digits.length() == 12 && digits.startsWith("91")) {
            variants.add("+" + digits);
            variants.add(digits);
            variants.add(digits.substring(2));
        }

        // Appointments
        List<Appointment> allAppts = new ArrayList<>();
        for (String v : variants) {
            allAppts.addAll(appointmentRepository.findByPatientPhoneOrderByAppointmentDateDesc(v));
        }
        Map<Long, Appointment> apptMap = new LinkedHashMap<>();
        for (Appointment a : allAppts) {
            apptMap.put(a.getId(), a);
        }
        List<Map<String, Object>> apptList = apptMap.values().stream()
                .sorted((a, b) -> b.getAppointmentDate().compareTo(a.getAppointmentDate()))
                .map(a -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", a.getId());
                    m.put("tokenNumber", a.getSerialNumber());
                    m.put("patientName", a.getPatientName() != null ? a.getPatientName() : "Patient");
                    m.put("doctorName", a.getDoctor() != null ? a.getDoctor().getName() : "Doctor");
                    m.put("department", a.getDoctor() != null ? a.getDoctor().getDepartment() : "General");
                    m.put("hospitalName", a.getHospital() != null ? a.getHospital().getName() : "Hospital");
                    m.put("roomNumber", a.getDoctor() != null ? a.getDoctor().getRoomNumber() : "-");
                    m.put("appointmentDate", a.getAppointmentDate().toString());
                    m.put("timeSlot", a.getTimeSlot() != null ? a.getTimeSlot() : "");
                    m.put("status", a.getStatus().name());
                    m.put("qrCodeToken", a.getQrCodeToken());
                    m.put("pdfUrl", "/api/v1/appointments/" + a.getId() + "/pdf");
                    return m;
                }).toList();

        // Documents from wardrobe
        List<com.med.assistant.model.MedicalDocument> docs = new ArrayList<>();
        if (wardrobeService != null) {
            for (String v : variants) {
                docs.addAll(wardrobeService.getRecentReports(v));
            }
        }
        Map<Long, com.med.assistant.model.MedicalDocument> docMap = new LinkedHashMap<>();
        for (com.med.assistant.model.MedicalDocument d : docs) {
            docMap.put(d.getId(), d);
        }
        List<Map<String, Object>> docList = docMap.values().stream()
                .sorted((a, b) -> b.getUploadedAt().compareTo(a.getUploadedAt()))
                .map(d -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", d.getId());
                    m.put("fileName", d.getOriginalFileName());
                    m.put("documentType", d.getDocumentType() != null ? d.getDocumentType().name() : "PRESCRIPTION");
                    m.put("aiSummary", d.getAiSummary());
                    m.put("uploadedAt", d.getUploadedAt() != null ? d.getUploadedAt().toString() : "");
                    return m;
                }).toList();

        // Medications
        List<com.med.assistant.service.MedicationReminderService.AlarmDetail> meds = new ArrayList<>();
        if (reminderService != null) {
            meds = reminderService.getAlarmDetails(cleanPhone);
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("phone", cleanPhone);
        response.put("appointments", apptList);
        response.put("documents", docList);
        response.put("medications", meds);
        return ResponseEntity.ok(response);
    }
}
