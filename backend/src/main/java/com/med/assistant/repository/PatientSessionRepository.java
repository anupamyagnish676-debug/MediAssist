package com.med.assistant.repository;

import com.med.assistant.model.PatientSession;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface PatientSessionRepository extends JpaRepository<PatientSession, String> {
    Optional<PatientSession> findByPhoneNumber(String phoneNumber);
}
