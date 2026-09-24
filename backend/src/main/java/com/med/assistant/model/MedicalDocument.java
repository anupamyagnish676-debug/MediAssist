package com.med.assistant.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "medical_documents")
public class MedicalDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String patientPhone;

    @Enumerated(EnumType.STRING)
    private DocumentType documentType = DocumentType.LAB_REPORT;

    private String title;
    private String originalFileName;
    private String storedFilePath;

    @Column(length = 4000)
    private String aiSummary;

    @Column(length = 2000)
    private String extractedTags; // Comma separated: e.g. "HbA1c, Blood Sugar, Cholesterol, Quest Diagnostics"

    private LocalDateTime uploadedAt = LocalDateTime.now();

    public enum DocumentType {
        PRESCRIPTION,
        LAB_REPORT,
        DISCHARGE_SUMMARY,
        SCAN_OR_XRAY,
        OTHER
    }

    public MedicalDocument() {}

    public MedicalDocument(String patientPhone, DocumentType documentType, String title,
                           String originalFileName, String storedFilePath, String aiSummary, String extractedTags) {
        this.patientPhone = patientPhone;
        this.documentType = documentType;
        this.title = title;
        this.originalFileName = originalFileName;
        this.storedFilePath = storedFilePath;
        this.aiSummary = aiSummary;
        this.extractedTags = extractedTags;
        this.uploadedAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getPatientPhone() { return patientPhone; }
    public void setPatientPhone(String patientPhone) { this.patientPhone = patientPhone; }

    public DocumentType getDocumentType() { return documentType; }
    public void setDocumentType(DocumentType documentType) { this.documentType = documentType; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getOriginalFileName() { return originalFileName; }
    public void setOriginalFileName(String originalFileName) { this.originalFileName = originalFileName; }

    public String getStoredFilePath() { return storedFilePath; }
    public void setStoredFilePath(String storedFilePath) { this.storedFilePath = storedFilePath; }

    public String getAiSummary() { return aiSummary; }
    public void setAiSummary(String aiSummary) { this.aiSummary = aiSummary; }

    public String getExtractedTags() { return extractedTags; }
    public void setExtractedTags(String extractedTags) { this.extractedTags = extractedTags; }

    public LocalDateTime getUploadedAt() { return uploadedAt; }
    public void setUploadedAt(LocalDateTime uploadedAt) { this.uploadedAt = uploadedAt; }
}
