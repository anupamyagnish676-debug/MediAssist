package com.med.assistant.repository;

import com.med.assistant.model.MedicalDocument;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MedicalDocumentRepository extends JpaRepository<MedicalDocument, Long> {
    List<MedicalDocument> findByPatientPhoneOrderByUploadedAtDesc(String patientPhone);

    @Query("SELECT m FROM MedicalDocument m WHERE m.patientPhone = :phone AND " +
           "(LOWER(m.title) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
           "LOWER(m.extractedTags) LIKE LOWER(CONCAT('%', :query, '%')) OR " +
           "LOWER(m.aiSummary) LIKE LOWER(CONCAT('%', :query, '%')))")
    List<MedicalDocument> searchWardrobe(@Param("phone") String phone, @Param("query") String query);
}
