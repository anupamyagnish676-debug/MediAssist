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
