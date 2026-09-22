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

    public AppointmentApiController(AppointmentRepository appointmentRepository) {
        this.appointmentRepository = appointmentRepository;
    }

    /**
     * Receptionist Check-In via QR Code Scanner.
     * Token is e.g. "APPT:A1B2C3D4" or "A1B2C3D4".
     */
    @PostMapping("/check-in")
    public ResponseEntity<Map<String, Object>> checkInWithQrCode(@RequestParam String token) {
        String cleanToken = token.replace("APPT:", "").trim();

        Optional<Appointment> opt = appointmentRepository.findByQrCodeToken(cleanToken);
        if (opt.isEmpty()) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of(
                    "success", false,
                    "message", "Invalid or unrecognized appointment QR Code."
            ));
        }

        Appointment appt = opt.get();
        appt.setStatus(Appointment.Status.CHECKED_IN);
        appointmentRepository.save(appt);

        return ResponseEntity.ok(Map.of(
                "success", true,
                "message", "Check-in successful!",
                "serialNumber", appt.getSerialNumber(),
                "patientPhone", appt.getPatientPhone(),
                "patientName", appt.getPatientName(),
                "doctorName", appt.getDoctor().getName(),
                "department", appt.getDoctor().getDepartment(),
                "roomNumber", appt.getDoctor().getRoomNumber()
        ));
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
        response.put("currentServingDoctor", currentServing != null ? currentServing.getDoctor().getName() : "Waiting for next patient");
        response.put("currentServingRoom", currentServing != null ? currentServing.getDoctor().getRoomNumber() : "-");
        response.put("waitingQueue", waitingList.stream().map(a -> Map.of(
                "token", a.getSerialNumber(),
                "doctor", a.getDoctor().getName(),
                "room", a.getDoctor().getRoomNumber()
        )).toList());

        return ResponseEntity.ok(response);
    }

    @GetMapping("/{id}/pdf")
    public ResponseEntity<byte[]> downloadPdf(@PathVariable Long id) {
        Optional<Appointment> opt = appointmentRepository.findById(id);
        if (opt.isEmpty() || opt.get().getPdfFilePath() == null) {
            return ResponseEntity.notFound().build();
        }
        try {
            byte[] fileBytes = java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(opt.get().getPdfFilePath()));
            return ResponseEntity.ok()
                    .header(org.springframework.http.HttpHeaders.CONTENT_TYPE, "application/pdf")
                    .header(org.springframework.http.HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"Appointment_Token_" + opt.get().getSerialNumber() + ".pdf\"")
                    .body(fileBytes);
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
}
