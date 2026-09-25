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
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public ResponseEntity<?> getAllHospitals() {
        try {
            List<Hospital> hospitals = hospitalRepository.findAll();
            List<Map<String, Object>> response = new java.util.ArrayList<>();
            for (Hospital h : hospitals) {
                if (!h.isActive()) continue;
                Map<String, Object> map = new HashMap<>();
                map.put("id", h.getId());
                map.put("name", h.getName());
                map.put("address", h.getAddress());
                map.put("latitude", h.getLatitude());
                map.put("longitude", h.getLongitude());
                map.put("phone", h.getPhone());
                map.put("brandColor", h.getBrandColor() != null ? h.getBrandColor() : "#0284c7");
                map.put("logoUrl", h.getLogoUrl());
                map.put("active", h.isActive());
                map.put("status", h.getStatus() != null ? h.getStatus().name() : "ACTIVE");

                List<Doctor> docList = doctorRepository.findByHospitalId(h.getId());
                List<Map<String, Object>> docs = new java.util.ArrayList<>();
                for (Doctor d : docList) {
                    Map<String, Object> docMap = new HashMap<>();
                    docMap.put("id", d.getId());
                    docMap.put("name", d.getName());
                    docMap.put("department", d.getDepartment());
                    docMap.put("dailyTokenLimit", d.getDailyTokenLimit());
                    docMap.put("currentTokenCount", d.getCurrentTokenCount());
                    docMap.put("availableToday", d.isAvailableToday());
                    docMap.put("roomNumber", d.getRoomNumber() != null ? d.getRoomNumber() : "101");
                    docMap.put("consultationFee", d.getConsultationFee());
                    docs.add(docMap);
                }
                map.put("doctors", docs);
                response.add(map);
            }
            return ResponseEntity.ok(response);
        } catch (Throwable t) {
            Map<String, Object> err = new HashMap<>();
            err.put("status", "ERROR");
            err.put("message", t.getMessage() != null ? t.getMessage() : "Error loading hospitals");
            return ResponseEntity.status(500).body(err);
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
    public ResponseEntity<?> getHospitalDoctors(@PathVariable Long hospitalId) {
        try {
            List<Doctor> doctors = doctorRepository.findByHospitalId(hospitalId);
            List<Map<String, Object>> response = new java.util.ArrayList<>();
            for (Doctor d : doctors) {
                Map<String, Object> docMap = new HashMap<>();
                docMap.put("id", d.getId());
                docMap.put("name", d.getName());
                docMap.put("department", d.getDepartment());
                docMap.put("dailyTokenLimit", d.getDailyTokenLimit());
                docMap.put("currentTokenCount", d.getCurrentTokenCount());
                docMap.put("availableToday", d.isAvailableToday());
                docMap.put("roomNumber", d.getRoomNumber() != null ? d.getRoomNumber() : "101");
                docMap.put("consultationFee", d.getConsultationFee());
                response.add(docMap);
            }
            return ResponseEntity.ok(response);
        } catch (Throwable t) {
            return ResponseEntity.status(500).body(Map.of("error", t.getMessage() != null ? t.getMessage() : "Error loading doctors"));
        }
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
