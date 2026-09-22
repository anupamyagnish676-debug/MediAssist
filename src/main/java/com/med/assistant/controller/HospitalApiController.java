package com.med.assistant.controller;

import com.med.assistant.model.Doctor;
import com.med.assistant.model.Hospital;
import com.med.assistant.repository.DoctorRepository;
import com.med.assistant.repository.HospitalRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/hospitals")
@CrossOrigin(origins = "*")
public class HospitalApiController {

    private final HospitalRepository hospitalRepository;
    private final DoctorRepository doctorRepository;

    public HospitalApiController(HospitalRepository hospitalRepository, DoctorRepository doctorRepository) {
        this.hospitalRepository = hospitalRepository;
        this.doctorRepository = doctorRepository;
    }

    @GetMapping
    public ResponseEntity<List<Hospital>> getAllHospitals() {
        return ResponseEntity.ok(hospitalRepository.findByActiveTrue());
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

    @PostMapping("/doctors/{doctorId}/limit")
    public ResponseEntity<Map<String, Object>> updateTokenLimit(@PathVariable Long doctorId, @RequestParam int limit) {
        Doctor doctor = doctorRepository.findById(doctorId).orElseThrow();
        doctor.setDailyTokenLimit(limit);
        doctorRepository.save(doctor);

        return ResponseEntity.ok(Map.of(
                "doctorId", doctor.getId(),
                "dailyTokenLimit", doctor.getDailyTokenLimit()
        ));
    }
}
