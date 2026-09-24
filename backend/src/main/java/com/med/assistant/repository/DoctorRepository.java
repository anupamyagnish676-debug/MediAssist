package com.med.assistant.repository;

import com.med.assistant.model.Doctor;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface DoctorRepository extends JpaRepository<Doctor, Long> {
    List<Doctor> findByHospitalIdAndAvailableTodayTrue(Long hospitalId);
    List<Doctor> findByHospitalId(Long hospitalId);
    List<Doctor> findByDepartmentIgnoreCaseAndAvailableTodayTrue(String department);
}
