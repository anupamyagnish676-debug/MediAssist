package com.med.assistant.service;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.med.assistant.model.Appointment;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.common.PDRectangle;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.graphics.image.LosslessFactory;
import org.apache.pdfbox.pdmodel.graphics.image.PDImageXObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Generates appointment slip PDFs entirely in-memory using PDFBox.
 * No filesystem, no fonts installation, no HTML rendering needed.
 * Works reliably on Alpine Linux / Render containers.
 */
@Service
public class AppointmentSlipPdfService {

    private static final Logger logger = LoggerFactory.getLogger(AppointmentSlipPdfService.class);

    // In-memory PDF cache: appointmentId -> PDF bytes
    private final Map<Long, byte[]> pdfCache = new ConcurrentHashMap<>();

    /**
     * Generate a PDF slip and store it in-memory.
     */
    public Long generatePdfSlip(Appointment appointment) {
        try {
            byte[] pdfBytes = buildPdf(appointment);
            pdfCache.put(appointment.getId(), pdfBytes);
            logger.info("PDF slip generated in-memory for appointment ID: {}", appointment.getId());
            return appointment.getId();
        } catch (Exception e) {
            logger.error("Failed to generate PDF for appointment {}: {}", appointment.getId(), e.getMessage(), e);
            throw new RuntimeException("Error generating appointment PDF slip: " + e.getMessage(), e);
        }
    }

    /**
     * Retrieve cached PDF bytes by appointment ID.
     */
    public byte[] getPdfBytes(Long appointmentId) {
        return pdfCache.get(appointmentId);
    }

    /**
     * Generate PDF bytes for an appointment (on-demand, for the download endpoint).
     */
    public byte[] generateAndGetBytes(Appointment appointment) {
        byte[] existing = pdfCache.get(appointment.getId());
        if (existing != null) return existing;
        generatePdfSlip(appointment);
        return pdfCache.get(appointment.getId());
    }

    @SuppressWarnings("deprecation")
    private byte[] buildPdf(Appointment appointment) throws Exception {
        // --- Extract data ---
        String hospitalName = appointment.getHospital() != null ? appointment.getHospital().getName() : "MedAssist Partner Hospital";
        String hospitalAddr = appointment.getHospital() != null ? appointment.getHospital().getAddress() : "Central OPD Wing";
        String hospitalPhone = appointment.getHospital() != null ? appointment.getHospital().getPhone() : "+1 800-555-0199";
        String doctorName = appointment.getDoctor() != null ? appointment.getDoctor().getName() : "Duty Physician";
        String department = appointment.getDoctor() != null ? appointment.getDoctor().getDepartment() : "General OPD";
        String room = appointment.getDoctor() != null && appointment.getDoctor().getRoomNumber() != null ? appointment.getDoctor().getRoomNumber() : "101";
        String patientName = appointment.getPatientName() != null ? appointment.getPatientName() : "Walk-in Patient";
        String patientPhone = appointment.getPatientPhone() != null ? appointment.getPatientPhone() : "+91 9876543210";
        String dateStr = appointment.getAppointmentDate() != null ? appointment.getAppointmentDate().format(DateTimeFormatter.ofPattern("dd MMMM yyyy")) : "Today";
        String timeSlot = appointment.getTimeSlot() != null ? appointment.getTimeSlot() : "11:00 AM";
        String token = String.format("#%02d", appointment.getSerialNumber());
        String qrToken = appointment.getQrCodeToken() != null ? appointment.getQrCodeToken() : UUID.randomUUID().toString().substring(0, 8);
        double fee = appointment.getDoctor() != null ? appointment.getDoctor().getConsultationFee() : 50.0;
        String availableTime = appointment.getDoctor() != null ? appointment.getDoctor().getAvailableTime() : null;

        // --- Colors ---
        int[] primaryBlue = {2, 132, 199};
        int[] darkText = {15, 23, 42};
        int[] mutedText = {100, 116, 139};
        int[] white = {255, 255, 255};
        int[] lightBg = {241, 245, 249};
        int[] sectionHeaderBg = {240, 249, 255};
        int[] separatorColor = {226, 232, 240};

        // --- Fonts ---
        PDType1Font fontBold = PDType1Font.HELVETICA_BOLD;
        PDType1Font fontRegular = PDType1Font.HELVETICA;

        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A5);
            doc.addPage(page);

            float pageWidth = page.getMediaBox().getWidth();
            float pageHeight = page.getMediaBox().getHeight();
            float margin = 32;
            float contentWidth = pageWidth - 2 * margin;

            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                float y = pageHeight;

                // ============================================================
                // 1. BLUE HEADER BAR
                // ============================================================
                float headerHeight = 60;
                cs.setNonStrokingColor(primaryBlue[0], primaryBlue[1], primaryBlue[2]);
                cs.addRect(0, y - headerHeight, pageWidth, headerHeight);
                cs.fill();

                // Hospital name (white, bold)
                cs.setNonStrokingColor(white[0], white[1], white[2]);
                cs.beginText();
                cs.setFont(fontBold, 15);
                float hospNameWidth = fontBold.getStringWidth(truncate(hospitalName, 40)) / 1000 * 15;
                cs.newLineAtOffset((pageWidth - hospNameWidth) / 2, y - 24);
                cs.showText(truncate(hospitalName, 40));
                cs.endText();

                // Address & phone (lighter white)
                cs.setNonStrokingColor(200, 225, 245);
                cs.beginText();
                cs.setFont(fontRegular, 8);
                String headerSubline = truncate(hospitalAddr, 50) + "  |  " + truncate(hospitalPhone, 25);
                float sublineWidth = fontRegular.getStringWidth(headerSubline) / 1000 * 8;
                cs.newLineAtOffset((pageWidth - sublineWidth) / 2, y - 40);
                cs.showText(headerSubline);
                cs.endText();

                y -= headerHeight;

                // ============================================================
                // 2. "OPD APPOINTMENT SLIP" TITLE
                // ============================================================
                y -= 22;
                cs.setNonStrokingColor(darkText[0], darkText[1], darkText[2]);
                cs.beginText();
                cs.setFont(fontBold, 13);
                String title = "OPD APPOINTMENT SLIP";
                float titleWidth = fontBold.getStringWidth(title) / 1000 * 13;
                cs.newLineAtOffset((pageWidth - titleWidth) / 2, y);
                cs.showText(title);
                cs.endText();

                y -= 12;

                // Separator
                cs.setStrokingColor(separatorColor[0], separatorColor[1], separatorColor[2]);
                cs.setLineWidth(1f);
                cs.moveTo(margin, y);
                cs.lineTo(pageWidth - margin, y);
                cs.stroke();

                y -= 10;

                // ============================================================
                // 3. PATIENT INFORMATION SECTION
                // ============================================================
                // Section header background
                float sectionHeaderHeight = 18;
                cs.setNonStrokingColor(sectionHeaderBg[0], sectionHeaderBg[1], sectionHeaderBg[2]);
                cs.addRect(margin, y - sectionHeaderHeight, contentWidth, sectionHeaderHeight);
                cs.fill();

                cs.setNonStrokingColor(primaryBlue[0], primaryBlue[1], primaryBlue[2]);
                cs.beginText();
                cs.setFont(fontBold, 9);
                cs.newLineAtOffset(margin + 8, y - 13);
                cs.showText("PATIENT INFORMATION");
                cs.endText();

                y -= sectionHeaderHeight + 8;

                // Patient Name
                drawLabelValue(cs, fontBold, fontRegular, margin, y, "Patient Name:", truncate(patientName, 35), darkText, mutedText);
                y -= 18;

                // Patient Mobile
                drawLabelValue(cs, fontBold, fontRegular, margin, y, "Patient Mobile:", truncate(patientPhone, 25), darkText, mutedText);
                y -= 14;

                // Separator
                cs.setStrokingColor(separatorColor[0], separatorColor[1], separatorColor[2]);
                cs.setLineWidth(0.5f);
                cs.moveTo(margin, y);
                cs.lineTo(pageWidth - margin, y);
                cs.stroke();

                y -= 10;

                // ============================================================
                // 4. APPOINTMENT DETAILS SECTION
                // ============================================================
                cs.setNonStrokingColor(sectionHeaderBg[0], sectionHeaderBg[1], sectionHeaderBg[2]);
                cs.addRect(margin, y - sectionHeaderHeight, contentWidth, sectionHeaderHeight);
                cs.fill();

                cs.setNonStrokingColor(primaryBlue[0], primaryBlue[1], primaryBlue[2]);
                cs.beginText();
                cs.setFont(fontBold, 9);
                cs.newLineAtOffset(margin + 8, y - 13);
                cs.showText("APPOINTMENT DETAILS");
                cs.endText();

                y -= sectionHeaderHeight + 10;

                // --- Token Number (prominent, in colored box) ---
                float tokenBoxWidth = 140;
                float tokenBoxHeight = 44;
                float tokenBoxX = margin + (contentWidth - tokenBoxWidth) / 2;

                // Token box background
                cs.setNonStrokingColor(sectionHeaderBg[0], sectionHeaderBg[1], sectionHeaderBg[2]);
                cs.addRect(tokenBoxX, y - tokenBoxHeight, tokenBoxWidth, tokenBoxHeight);
                cs.fill();

                // Token box border
                cs.setStrokingColor(primaryBlue[0], primaryBlue[1], primaryBlue[2]);
                cs.setLineWidth(1.5f);
                cs.addRect(tokenBoxX, y - tokenBoxHeight, tokenBoxWidth, tokenBoxHeight);
                cs.stroke();

                // "Token Number" label
                cs.setNonStrokingColor(mutedText[0], mutedText[1], mutedText[2]);
                cs.beginText();
                cs.setFont(fontRegular, 7);
                String tokenLabel = "Token Number";
                float tokenLabelW = fontRegular.getStringWidth(tokenLabel) / 1000 * 7;
                cs.newLineAtOffset(tokenBoxX + (tokenBoxWidth - tokenLabelW) / 2, y - 12);
                cs.showText(tokenLabel);
                cs.endText();

                // Token value (large)
                cs.setNonStrokingColor(primaryBlue[0], primaryBlue[1], primaryBlue[2]);
                cs.beginText();
                cs.setFont(fontBold, 24);
                float tokenValW = fontBold.getStringWidth(token) / 1000 * 24;
                cs.newLineAtOffset(tokenBoxX + (tokenBoxWidth - tokenValW) / 2, y - 36);
                cs.showText(token);
                cs.endText();

                y -= tokenBoxHeight + 12;

                // Date & Time
                drawLabelValue(cs, fontBold, fontRegular, margin, y, "Date & Time:", dateStr + " at " + timeSlot, darkText, mutedText);
                y -= 18;

                // Consulting Doctor & Department
                drawLabelValue(cs, fontBold, fontRegular, margin, y, "Consulting Doctor:", truncate(doctorName + " (" + department + ")", 40), darkText, mutedText);
                y -= 18;

                // OPD Room
                drawLabelValue(cs, fontBold, fontRegular, margin, y, "OPD Room:", "Room " + room, darkText, mutedText);
                y -= 18;

                // Consultation Fee
                drawLabelValue(cs, fontBold, fontRegular, margin, y, "Consultation Fee:", "\u20B9 " + String.format("%.2f", fee), darkText, mutedText);
                y -= 18;

                // Doctor Available Time (only if not null)
                if (availableTime != null) {
                    drawLabelValue(cs, fontBold, fontRegular, margin, y, "Doctor Available:", truncate(availableTime, 35), darkText, mutedText);
                    y -= 18;
                }

                y -= 4;

                // Separator
                cs.setStrokingColor(separatorColor[0], separatorColor[1], separatorColor[2]);
                cs.setLineWidth(0.5f);
                cs.moveTo(margin, y);
                cs.lineTo(pageWidth - margin, y);
                cs.stroke();

                y -= 10;

                // ============================================================
                // 5. QR CODE SECTION
                // ============================================================
                BufferedImage qrImage = generateQrImage("APPT:" + qrToken, 200, 200);
                PDImageXObject pdImage = LosslessFactory.createFromImage(doc, qrImage);

                float qrSize = 80;
                float qrX = margin + (contentWidth - qrSize) / 2;
                cs.drawImage(pdImage, qrX, y - qrSize, qrSize, qrSize);

                y -= qrSize + 6;

                // "Scan for Quick Check-In" text
                cs.setNonStrokingColor(mutedText[0], mutedText[1], mutedText[2]);
                cs.beginText();
                cs.setFont(fontRegular, 7);
                String scanText = "Scan for Quick Check-In";
                float scanWidth = fontRegular.getStringWidth(scanText) / 1000 * 7;
                cs.newLineAtOffset((pageWidth - scanWidth) / 2, y);
                cs.showText(scanText);
                cs.endText();

                y -= 14;

                // Separator
                cs.setStrokingColor(separatorColor[0], separatorColor[1], separatorColor[2]);
                cs.setLineWidth(0.5f);
                cs.moveTo(margin, y);
                cs.lineTo(pageWidth - margin, y);
                cs.stroke();

                y -= 10;

                // ============================================================
                // 6. IMPORTANT INSTRUCTIONS SECTION
                // ============================================================
                cs.setNonStrokingColor(sectionHeaderBg[0], sectionHeaderBg[1], sectionHeaderBg[2]);
                cs.addRect(margin, y - sectionHeaderHeight, contentWidth, sectionHeaderHeight);
                cs.fill();

                cs.setNonStrokingColor(primaryBlue[0], primaryBlue[1], primaryBlue[2]);
                cs.beginText();
                cs.setFont(fontBold, 9);
                cs.newLineAtOffset(margin + 8, y - 13);
                cs.showText("IMPORTANT INSTRUCTIONS");
                cs.endText();

                y -= sectionHeaderHeight + 8;

                String[] instructions = {
                    "Please arrive 15 minutes before your appointment",
                    "Carry this slip and a valid ID proof",
                    "This slip is valid only for the date mentioned",
                    "For cancellation, contact the hospital helpline"
                };

                cs.setNonStrokingColor(darkText[0], darkText[1], darkText[2]);
                for (String instruction : instructions) {
                    cs.beginText();
                    cs.setFont(fontRegular, 7);
                    cs.newLineAtOffset(margin + 8, y);
                    cs.showText("\u2022  " + instruction);
                    cs.endText();
                    y -= 13;
                }

                y -= 6;

                // Separator
                cs.setStrokingColor(separatorColor[0], separatorColor[1], separatorColor[2]);
                cs.setLineWidth(0.5f);
                cs.moveTo(margin, y);
                cs.lineTo(pageWidth - margin, y);
                cs.stroke();

                y -= 12;

                // ============================================================
                // 7. FOOTER
                // ============================================================
                cs.setNonStrokingColor(mutedText[0], mutedText[1], mutedText[2]);
                cs.beginText();
                cs.setFont(fontRegular, 7);
                String footer = "Powered by MediAssist | www.mediassist.com";
                float footerWidth = fontRegular.getStringWidth(footer) / 1000 * 7;
                cs.newLineAtOffset((pageWidth - footerWidth) / 2, y);
                cs.showText(footer);
                cs.endText();
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            return baos.toByteArray();
        }
    }

    /**
     * Helper to draw a label-value pair on a single line.
     */
    @SuppressWarnings("deprecation")
    private void drawLabelValue(PDPageContentStream cs, PDType1Font fontBold, PDType1Font fontRegular,
                                float margin, float y, String label, String value,
                                int[] darkText, int[] mutedText) throws java.io.IOException {
        cs.setNonStrokingColor(mutedText[0], mutedText[1], mutedText[2]);
        cs.beginText();
        cs.setFont(fontBold, 8);
        cs.newLineAtOffset(margin + 8, y);
        cs.showText(label);
        cs.endText();

        cs.setNonStrokingColor(darkText[0], darkText[1], darkText[2]);
        cs.beginText();
        cs.setFont(fontRegular, 9);
        cs.newLineAtOffset(margin + 120, y);
        cs.showText(value);
        cs.endText();
    }

    private BufferedImage generateQrImage(String content, int width, int height) throws Exception {
        QRCodeWriter qrCodeWriter = new QRCodeWriter();
        BitMatrix bitMatrix = qrCodeWriter.encode(content, BarcodeFormat.QR_CODE, width, height);
        return MatrixToImageWriter.toBufferedImage(bitMatrix);
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        return s.length() > maxLen ? s.substring(0, maxLen - 3) + "..." : s;
    }
}
