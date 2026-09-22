package com.med.assistant.service;

import com.med.assistant.model.MedicalDocument;
import com.med.assistant.repository.MedicalDocumentRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

@Service
public class ReportWardrobeService {

    private final MedicalDocumentRepository documentRepository;
    private final GeminiAiService geminiAiService;

    @Value("${app.storage.upload-dir:./uploads}")
    private String uploadDir;

    public ReportWardrobeService(MedicalDocumentRepository documentRepository, GeminiAiService geminiAiService) {
        this.documentRepository = documentRepository;
        this.geminiAiService = geminiAiService;
    }

    /**
     * Stores incoming prescription or lab document into the patient's Report Wardrobe.
     */
    public MedicalDocument storeDocument(String patientPhone, byte[] fileBytes, String originalName, String mimeType) {
        try {
            Path wardrobeDir = Paths.get(uploadDir, "wardrobe", patientPhone);
            Files.createDirectories(wardrobeDir);

            String fileName = System.currentTimeMillis() + "_" + originalName;
            Path filePath = wardrobeDir.resolve(fileName);

            try (FileOutputStream fos = new FileOutputStream(filePath.toFile())) {
                fos.write(fileBytes);
            }

            // Run AI OCR & Summarization
            String aiSummary = geminiAiService.analyzeLabReportOrPrescription(fileBytes, mimeType, originalName);

            MedicalDocument.DocumentType docType = originalName.toLowerCase().contains("prescription")
                    ? MedicalDocument.DocumentType.PRESCRIPTION
                    : MedicalDocument.DocumentType.LAB_REPORT;

            MedicalDocument doc = new MedicalDocument(
                    patientPhone,
                    docType,
                    originalName,
                    originalName,
                    filePath.toAbsolutePath().toString(),
                    aiSummary,
                    "Lab, Medical Report, " + originalName
            );

            return documentRepository.save(doc);

        } catch (Exception e) {
            throw new RuntimeException("Failed to store document in wardrobe: " + e.getMessage(), e);
        }
    }

    /**
     * Natural language retrieval from patient's wardrobe.
     */
    public List<MedicalDocument> searchReports(String patientPhone, String searchQuery) {
        return documentRepository.searchWardrobe(patientPhone, searchQuery);
    }

    public List<MedicalDocument> getRecentReports(String patientPhone) {
        return documentRepository.findByPatientPhoneOrderByUploadedAtDesc(patientPhone);
    }
}
