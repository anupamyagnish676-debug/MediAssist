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

    private static final org.slf4j.Logger logger = org.slf4j.LoggerFactory.getLogger(ReportWardrobeService.class);

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
            String safeOriginalName = originalName != null ? originalName.replaceAll("[^a-zA-Z0-9._-]", "_") : "document.jpg";
            String fileName = System.currentTimeMillis() + "_" + safeOriginalName;
            String savedPath = "./uploads/wardrobe/" + fileName;

            try {
                Path wardrobeDir = Paths.get(uploadDir, "wardrobe", patientPhone.replaceAll("[^a-zA-Z0-9+]", ""));
                Files.createDirectories(wardrobeDir);
                Path filePath = wardrobeDir.resolve(fileName);
                try (FileOutputStream fos = new FileOutputStream(filePath.toFile())) {
                    fos.write(fileBytes);
                }
                savedPath = filePath.toAbsolutePath().toString();
            } catch (Exception ioEx) {
                logger.warn("Could not save document file to disk: {}", ioEx.getMessage());
            }

            // Run AI OCR & Summarization
            String aiSummary = "Medical document processed and safely filed in your Report Wardrobe.";
            try {
                aiSummary = geminiAiService.analyzeLabReportOrPrescription(fileBytes, mimeType, safeOriginalName);
                if (aiSummary != null && aiSummary.length() > 3800) {
                    aiSummary = aiSummary.substring(0, 3800);
                }
            } catch (Exception aiEx) {
                logger.warn("AI analysis during document storage failed: {}", aiEx.getMessage());
            }

            MedicalDocument.DocumentType docType = safeOriginalName.toLowerCase().contains("prescription")
                    ? MedicalDocument.DocumentType.PRESCRIPTION
                    : MedicalDocument.DocumentType.LAB_REPORT;

            MedicalDocument doc = new MedicalDocument(
                    patientPhone,
                    docType,
                    safeOriginalName,
                    safeOriginalName,
                    savedPath,
                    aiSummary,
                    "Medical Document, " + safeOriginalName
            );

            return documentRepository.save(doc);

        } catch (Exception e) {
            logger.error("Failed to store document in wardrobe: {}", e.getMessage(), e);
            return null;
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
