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
    private final RateLimitService rateLimitService;

    @Value("${app.storage.upload-dir:./uploads}")
    private String uploadDir;

    private static final int MAX_FILE_SIZE_BYTES = 10 * 1024 * 1024; // 10MB quota

    public ReportWardrobeService(MedicalDocumentRepository documentRepository,
                                 GeminiAiService geminiAiService,
                                 RateLimitService rateLimitService) {
        this.documentRepository = documentRepository;
        this.geminiAiService = geminiAiService;
        this.rateLimitService = rateLimitService;
    }

    /**
     * Stores incoming prescription or lab document into the patient's Report Wardrobe.
     * Hardened with Magic Byte sanitation, 10MB quota, path traversal checks, and rate limiting.
     */
    public MedicalDocument storeDocument(String patientPhone, byte[] fileBytes, String originalName, String mimeType) {
        try {
            if (fileBytes == null || fileBytes.length == 0) {
                logger.warn("Rejected empty document upload from {}", patientPhone);
                return null;
            }

            if (fileBytes.length > MAX_FILE_SIZE_BYTES) {
                logger.warn("Rejected document upload exceeding 10MB ({} bytes) from {}", fileBytes.length, patientPhone);
                return null;
            }

            // Magic byte validation to prevent malicious executables and script uploads
            if (!isValidMedicalFile(fileBytes, originalName)) {
                logger.warn("Security rejection: file content does not match allowed magic bytes (PDF/JPEG/PNG) from {}", patientPhone);
                return null;
            }

            // Rate limit AI vision processing to prevent Denial-of-Wallet attacks
            if (!rateLimitService.tryAcquirePrescriptionScan(patientPhone)) {
                logger.warn("Rate limit exceeded for prescription/lab report scanning: {}", patientPhone);
                return null;
            }

            String safeOriginalName = originalName != null ? originalName.replaceAll("[^a-zA-Z0-9._-]", "_") : "document.jpg";
            String fileName = System.currentTimeMillis() + "_" + safeOriginalName;
            String savedPath = "./uploads/wardrobe/" + fileName;

            try {
                Path baseDir = Paths.get(uploadDir, "wardrobe").toAbsolutePath().normalize();
                String safePhoneDir = patientPhone.replaceAll("[^a-zA-Z0-9+]", "");
                Path wardrobeDir = baseDir.resolve(safePhoneDir).normalize();

                // Path traversal defense
                if (!wardrobeDir.startsWith(baseDir)) {
                    throw new SecurityException("Potential path traversal attempt detected in phone directory path");
                }

                Files.createDirectories(wardrobeDir);
                Path filePath = wardrobeDir.resolve(fileName).normalize();

                if (!filePath.startsWith(wardrobeDir)) {
                    throw new SecurityException("Potential path traversal attempt detected in file name");
                }

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

    /**
     * Inspects magic bytes (file signature) to ensure uploaded content is genuinely a PDF or Image
     * and not an executable or malicious script masquerading as an image.
     */
    private boolean isValidMedicalFile(byte[] bytes, String fileName) {
        if (bytes == null || bytes.length < 4) return false;

        // Disallow dangerous extensions explicitly
        if (fileName != null) {
            String lower = fileName.toLowerCase();
            if (lower.endsWith(".exe") || lower.endsWith(".bat") || lower.endsWith(".cmd") ||
                lower.endsWith(".sh") || lower.endsWith(".php") || lower.endsWith(".jsp") ||
                lower.endsWith(".jar") || lower.endsWith(".js") || lower.endsWith(".html") ||
                lower.endsWith(".svg")) {
                return false;
            }
        }

        // PDF: %PDF (0x25 0x50 0x44 0x46)
        if (bytes[0] == 0x25 && bytes[1] == 0x50 && bytes[2] == 0x44 && bytes[3] == 0x46) {
            return true;
        }

        // JPEG: FF D8 FF
        if ((bytes[0] & 0xFF) == 0xFF && (bytes[1] & 0xFF) == 0xD8 && (bytes[2] & 0xFF) == 0xFF) {
            return true;
        }

        // PNG: 89 50 4E 47 (0x89 'P' 'N' 'G')
        if ((bytes[0] & 0xFF) == 0x89 && bytes[1] == 0x50 && bytes[2] == 0x4E && bytes[3] == 0x47) {
            return true;
        }

        // WebP: starts with "RIFF" and has "WEBP" at offset 8
        if (bytes.length >= 12 &&
            bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F' &&
            bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') {
            return true;
        }

        return false;
    }
}
