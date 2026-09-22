package com.med.assistant.controller;

import com.med.assistant.model.Doctor;
import com.med.assistant.model.Hospital;
import com.med.assistant.repository.DoctorRepository;
import com.med.assistant.repository.HospitalRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/hospitals")
@CrossOrigin(origins = "*")
public class HospitalApiController {

    private final HospitalRepository hospitalRepository;
    private final DoctorRepository doctorRepository;
    private final DataSource dataSource;

    public HospitalApiController(HospitalRepository hospitalRepository,
                                  DoctorRepository doctorRepository,
                                  DataSource dataSource) {
        this.hospitalRepository = hospitalRepository;
        this.doctorRepository = doctorRepository;
        this.dataSource = dataSource;
    }

    @GetMapping
    public ResponseEntity<?> getAllHospitals() {
        try {
            return ResponseEntity.ok(hospitalRepository.findByActiveTrue());
        } catch (Throwable t) {
            return ResponseEntity.status(500).body(Map.of(
                    "status", "ERROR",
                    "message", t.getMessage() != null ? t.getMessage() : "Unknown DB query error",
                    "type", t.getClass().getName()
            ));
        }
    }

    @GetMapping("/diagnostic")
    public ResponseEntity<Map<String, Object>> getDiagnostic() {
        Map<String, Object> map = new HashMap<>();
        try {
            map.put("databaseProduct", dataSource.getConnection().getMetaData().getDatabaseProductName());
            map.put("databaseUrl", dataSource.getConnection().getMetaData().getURL());
            map.put("hospitalCount", hospitalRepository.count());
            map.put("status", "HEALTHY");
        } catch (Throwable t) {
            map.put("status", "ERROR");
            map.put("errorType", t.getClass().getName());
            map.put("errorMessage", t.getMessage());
            if (t.getCause() != null) {
                map.put("causeType", t.getCause().getClass().getName());
                map.put("causeMessage", t.getCause().getMessage());
            }
        }
        return ResponseEntity.ok(map);
    }

    @GetMapping("/{hospitalId}/doctors")
    public ResponseEntity<List<Doctor>> getHospitalDoctors(@PathVariable Long hospitalId) {
        return ResponseEntity.ok(doctorRepository.findByHospitalId(hospitalId));
    }

    @PostMapping("/doctors/{doctorId}/toggle-availability")
    public ResponseEntity<Map<String, Object>> toggleDoctorAvailability(@PathVariable Long doctorId) {
        Doctor doctor = doctorRepository.findById(doctorId).orElseThrow();
        doctor.setAvailableToday(!doctor.isAvailableToday());
        doctorRepository.save(doctor);

        return ResponseEntity.ok(Map.of(
                "doctorId", doctor.getId(),
                "doctorName", doctor.getName(),
                "availableToday", doctor.isAvailableToday()
        ));
    }

    @PostMapping("/register")
    public ResponseEntity<Hospital> registerHospital(@RequestBody HospitalRegistrationRequest req) {
        Hospital hospital = new Hospital(
                req.name(),
                req.address(),
                req.latitude(),
                req.longitude(),
                req.phone(),
                req.brandColor() != null && !req.brandColor().isBlank() ? req.brandColor() : "#0284c7"
        );
        Hospital saved = hospitalRepository.save(hospital);

        if (req.doctors() != null) {
            for (DoctorRegistrationRequest dReq : req.doctors()) {
                Doctor doc = new Doctor(dReq.name(), dReq.department(), saved,
                        dReq.dailyTokenLimit() > 0 ? dReq.dailyTokenLimit() : 25, dReq.consultationFee());
                doc.setRoomNumber(dReq.roomNumber() != null && !dReq.roomNumber().isBlank() ? dReq.roomNumber() : "101");
                doctorRepository.save(doc);
            }
        }

        return ResponseEntity.ok(saved);
    }

    public record HospitalRegistrationRequest(
            String name,
            String address,
            double latitude,
            double longitude,
            String phone,
            String brandColor,
            List<DoctorRegistrationRequest> doctors
    ) {}

    public record DoctorRegistrationRequest(
            String name,
            String department,
            String roomNumber,
            double consultationFee,
            int dailyTokenLimit
    ) {}
}
