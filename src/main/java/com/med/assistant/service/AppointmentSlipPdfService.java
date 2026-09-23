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
        String hospitalName = appointment.getHospital() != null ? appointment.getHospital().getName() : "MedAssist Partner Hospital";
        String hospitalAddr = appointment.getHospital() != null ? appointment.getHospital().getAddress() : "Central OPD Wing";
        String hospitalPhone = appointment.getHospital() != null ? appointment.getHospital().getPhone() : "+1 800-555-0199";
        String doctorName = appointment.getDoctor() != null ? appointment.getDoctor().getName() : "Duty Physician";
        String department = appointment.getDoctor() != null ? appointment.getDoctor().getDepartment() : "General OPD";
        String room = appointment.getDoctor() != null && appointment.getDoctor().getRoomNumber() != null ? appointment.getDoctor().getRoomNumber() : "101";
        String patientPhone = appointment.getPatientPhone() != null ? appointment.getPatientPhone() : "+91 9876543210";
        String dateStr = appointment.getAppointmentDate() != null ? appointment.getAppointmentDate().format(DateTimeFormatter.ofPattern("dd MMMM yyyy")) : "Today";
        String timeSlot = appointment.getTimeSlot() != null ? appointment.getTimeSlot() : "11:00 AM";
        String token = String.format("#%02d", appointment.getSerialNumber());
        String qrToken = appointment.getQrCodeToken() != null ? appointment.getQrCodeToken() : UUID.randomUUID().toString().substring(0, 8);
        double fee = appointment.getDoctor() != null ? appointment.getDoctor().getConsultationFee() : 50.0;

        // PDFBox 2.x uses static fields for standard fonts
        PDType1Font fontBold = PDType1Font.HELVETICA_BOLD;
        PDType1Font fontRegular = PDType1Font.HELVETICA;
        PDType1Font fontItalic = PDType1Font.HELVETICA_OBLIQUE;

        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A5);
            doc.addPage(page);

            float pageWidth = page.getMediaBox().getWidth();
            float pageHeight = page.getMediaBox().getHeight();
            float margin = 40;
            float contentWidth = pageWidth - 2 * margin;

            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                float y = pageHeight - margin;

                // === HEADER: Hospital Name ===
                cs.setNonStrokingColor(2, 132, 199);
                cs.beginText();
                cs.setFont(fontBold, 14);
                cs.newLineAtOffset(margin, y);
                cs.showText(truncate(hospitalName, 40));
                cs.endText();
                y -= 16;

                // Hospital Address
                cs.setNonStrokingColor(100, 116, 139);
                cs.beginText();
                cs.setFont(fontRegular, 8);
                cs.newLineAtOffset(margin, y);
                cs.showText(truncate(hospitalAddr, 60));
                cs.endText();
                y -= 12;

                // Hospital Phone
                cs.beginText();
                cs.setFont(fontRegular, 8);
                cs.newLineAtOffset(margin, y);
                cs.showText("Emergency Helpline: " + truncate(hospitalPhone, 30));
                cs.endText();
                y -= 8;

                // "OFFICIAL SLIP" badge (right-aligned)
                cs.setNonStrokingColor(224, 242, 254);
                cs.addRect(pageWidth - margin - 80, y + 4, 80, 16);
                cs.fill();
                cs.setNonStrokingColor(3, 105, 161);
                cs.beginText();
                cs.setFont(fontBold, 7);
                cs.newLineAtOffset(pageWidth - margin - 72, y + 9);
                cs.showText("OFFICIAL SLIP");
                cs.endText();

                y -= 6;

                // === Divider line ===
                cs.setStrokingColor(2, 132, 199);
                cs.setLineWidth(2);
                cs.moveTo(margin, y);
                cs.lineTo(pageWidth - margin, y);
                cs.stroke();
                y -= 25;

                // === TOKEN BOX ===
                float boxHeight = 70;
                cs.setNonStrokingColor(240, 249, 255);
                cs.addRect(margin, y - boxHeight, contentWidth, boxHeight);
                cs.fill();

                cs.setStrokingColor(2, 132, 199);
                cs.setLineWidth(1.5f);
                cs.setLineDashPattern(new float[]{4, 3}, 0);
                cs.addRect(margin, y - boxHeight, contentWidth, boxHeight);
                cs.stroke();
                cs.setLineDashPattern(new float[]{}, 0);

                // "YOUR APPOINTMENT TOKEN" label
                cs.setNonStrokingColor(3, 105, 161);
                cs.beginText();
                cs.setFont(fontBold, 8);
                float labelWidth = fontBold.getStringWidth("YOUR APPOINTMENT TOKEN") / 1000 * 8;
                cs.newLineAtOffset(margin + (contentWidth - labelWidth) / 2, y - 18);
                cs.showText("YOUR APPOINTMENT TOKEN");
                cs.endText();

                // Token number (large)
                cs.setNonStrokingColor(2, 132, 199);
                cs.beginText();
                cs.setFont(fontBold, 32);
                float tokenWidth = fontBold.getStringWidth(token) / 1000 * 32;
                cs.newLineAtOffset(margin + (contentWidth - tokenWidth) / 2, y - 50);
                cs.showText(token);
                cs.endText();

                // "Please arrive 15 minutes..." note
                cs.setNonStrokingColor(100, 116, 139);
                cs.beginText();
                cs.setFont(fontRegular, 7);
                String arriveNote = "Please arrive 15 minutes before your scheduled slot";
                float arriveWidth = fontRegular.getStringWidth(arriveNote) / 1000 * 7;
                cs.newLineAtOffset(margin + (contentWidth - arriveWidth) / 2, y - boxHeight + 8);
                cs.showText(arriveNote);
                cs.endText();

                y -= boxHeight + 20;

                // === DETAILS TABLE ===
                String[][] rows = {
                    {"Consulting Doctor:", doctorName + " (" + department + ")"},
                    {"OPD Room:", "Room " + room},
                    {"Date & Time:", dateStr + " at " + timeSlot},
                    {"Patient Mobile:", patientPhone},
                    {"Consultation Fee:", "$" + String.format("%.2f", fee) + " (Pay at front desk)"}
                };

                for (String[] row : rows) {
                    cs.setStrokingColor(226, 232, 240);
                    cs.setLineWidth(0.5f);
                    cs.moveTo(margin, y);
                    cs.lineTo(pageWidth - margin, y);
                    cs.stroke();
                    y -= 5;

                    cs.setNonStrokingColor(71, 85, 105);
                    cs.beginText();
                    cs.setFont(fontBold, 9);
                    cs.newLineAtOffset(margin + 5, y - 10);
                    cs.showText(row[0]);
                    cs.endText();

                    cs.setNonStrokingColor(15, 23, 42);
                    cs.beginText();
                    cs.setFont(fontRegular, 9);
                    cs.newLineAtOffset(margin + 125, y - 10);
                    cs.showText(truncate(row[1], 40));
                    cs.endText();

                    y -= 22;
                }

                // Final separator
                cs.setStrokingColor(226, 232, 240);
                cs.setLineWidth(0.5f);
                cs.moveTo(margin, y);
                cs.lineTo(pageWidth - margin, y);
                cs.stroke();
                y -= 20;

                // === QR CODE SECTION ===
                cs.setNonStrokingColor(248, 250, 252);
                cs.addRect(margin, y - 130, contentWidth, 130);
                cs.fill();

                // Generate QR code in-memory
                BufferedImage qrImage = generateQrImage("APPT:" + qrToken, 200, 200);
                PDImageXObject pdImage = LosslessFactory.createFromImage(doc, qrImage);

                float qrSize = 90;
                float qrX = margin + (contentWidth - qrSize) / 2;
                cs.drawImage(pdImage, qrX, y - 105, qrSize, qrSize);

                // "Scan at the Reception Desk..." text
                cs.setNonStrokingColor(100, 116, 139);
                cs.beginText();
                cs.setFont(fontRegular, 7);
                String scanText = "Scan at the Reception Desk for Instant Check-In";
                float scanWidth = fontRegular.getStringWidth(scanText) / 1000 * 7;
                cs.newLineAtOffset(margin + (contentWidth - scanWidth) / 2, y - 118);
                cs.showText(scanText);
                cs.endText();

                // QR token code
                cs.setNonStrokingColor(148, 163, 184);
                cs.beginText();
                cs.setFont(fontRegular, 7);
                float qrTokenWidth = fontRegular.getStringWidth(qrToken) / 1000 * 7;
                cs.newLineAtOffset(margin + (contentWidth - qrTokenWidth) / 2, y - 128);
                cs.showText(qrToken);
                cs.endText();

                y -= 145;

                // === FOOTER ===
                cs.setStrokingColor(241, 245, 249);
                cs.setLineWidth(0.5f);
                cs.moveTo(margin, y);
                cs.lineTo(pageWidth - margin, y);
                cs.stroke();
                y -= 12;

                cs.setNonStrokingColor(148, 163, 184);
                cs.beginText();
                cs.setFont(fontItalic, 6);
                String footer = "Generated via WhatsApp Medical Assistant - Valid only for the date mentioned above.";
                float footerWidth = fontItalic.getStringWidth(footer) / 1000 * 6;
                cs.newLineAtOffset(margin + (contentWidth - footerWidth) / 2, y);
                cs.showText(footer);
                cs.endText();
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            return baos.toByteArray();
        }
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
