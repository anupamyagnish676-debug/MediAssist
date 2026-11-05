package com.med.assistant.repository;

import com.med.assistant.model.PatientProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface PatientProfileRepository extends JpaRepository<PatientProfile, String> {
    Optional<PatientProfile> findByPhoneNumber(String phoneNumber);
}
