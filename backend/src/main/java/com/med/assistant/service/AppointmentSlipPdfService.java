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

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Generates authentic Hospital OP Case Sheet PDFs on A4 paper modeled on the
 * official KIMS (Kalinga Institute of Medical Sciences) outpatient clinical format.
 * Features dual logos: MediAssist on the left and Hospital/Medical Logo on the right.
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
            logger.info("A4 OP Case Sheet PDF generated in-memory for appointment ID: {}", appointment.getId());
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
     * Generate PDF bytes for an appointment (on-demand, for download endpoint).
     */
    public byte[] generateAndGetBytes(Appointment appointment) {
        generatePdfSlip(appointment);
        return pdfCache.get(appointment.getId());
    }

    @SuppressWarnings("deprecation")
    private byte[] buildPdf(Appointment appointment) throws Exception {
        // --- Extract Hospital Details ---
        String hospitalName = "Kalinga Institute of Medical Sciences (KIMS)";
        String hospitalAddr = "Kushabhadra Campus (KIIT Campus-5), Patia, Bhubaneswar - 751024, Odisha, India";
        String hospitalPhone = "0674 2304400 / 7111000";
        String hospitalEmail = "info@kims.ac.in";
        String hospitalWeb = "https://kims.kiit.ac.in";
        String logoUrl = null;

        if (appointment.getHospital() != null) {
            if (appointment.getHospital().getName() != null && !appointment.getHospital().getName().isBlank()) {
                hospitalName = appointment.getHospital().getName();
            }
            if (appointment.getHospital().getAddress() != null && !appointment.getHospital().getAddress().isBlank()) {
                hospitalAddr = appointment.getHospital().getAddress();
            }
            if (appointment.getHospital().getPhone() != null && !appointment.getHospital().getPhone().isBlank()) {
                hospitalPhone = appointment.getHospital().getPhone();
            }
            logoUrl = appointment.getHospital().getLogoUrl();
        }

        // --- Extract Doctor Details ---
        String doctorName = appointment.getDoctor() != null ? appointment.getDoctor().getName() : "Dr. Soumya Behera";
        String department = appointment.getDoctor() != null ? appointment.getDoctor().getDepartment() : "General Medicine";
        String room = appointment.getDoctor() != null && appointment.getDoctor().getRoomNumber() != null ? appointment.getDoctor().getRoomNumber() : "22";

        // --- Extract Patient Details ---
        String patientName = appointment.getPatientName() != null ? appointment.getPatientName() : "Mr YAGNISH ANUPAM";
        String patientPhone = appointment.getPatientPhone() != null ? appointment.getPatientPhone() : "7978015617";
        String dateStr = appointment.getAppointmentDate() != null
                ? appointment.getAppointmentDate().format(DateTimeFormatter.ofPattern("MMMM d, yyyy"))
                : "September 25, 2026";
        String timeSlot = appointment.getTimeSlot() != null ? appointment.getTimeSlot() : "10:30 AM - 10:45 AM";
        int serialNo = appointment.getSerialNumber() > 0 ? appointment.getSerialNumber() : 1;
        String tokenStr = String.format("%02d", serialNo);
        String qrToken = appointment.getQrCodeToken() != null ? appointment.getQrCodeToken() : "849201";
        String mrn = "KIMS" + String.format("%08d", (appointment.getId() != null ? appointment.getId() * 10140L : 102310010140L) % 100000000L);

        // --- Colors ---
        int[] forestGreen = {20, 83, 45};        // #14532d (KIMS Banner Green)
        int[] darkGreen = {22, 101, 52};          // #166534
        int[] darkText = {15, 23, 42};            // #0f172a
        int[] mutedText = {71, 85, 105};          // #475569
        int[] lightGray = {241, 245, 249};        // #f1f5f9
        int[] borderGray = {203, 213, 225};       // #cbd5e1
        int[] white = {255, 255, 255};
        int[] lightGreenBg = {240, 253, 244};     // #f0fdf4

        // --- Fonts ---
        PDType1Font fontBold = PDType1Font.HELVETICA_BOLD;
        PDType1Font fontRegular = PDType1Font.HELVETICA;
        PDType1Font fontOblique = PDType1Font.HELVETICA_OBLIQUE;

        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage(PDRectangle.A4);
            doc.addPage(page);

            float pageWidth = page.getMediaBox().getWidth();   // 595.28 pt
            float pageHeight = page.getMediaBox().getHeight(); // 841.89 pt
            float margin = 32;
            float contentWidth = pageWidth - 2 * margin;       // 531.28 pt

            try (PDPageContentStream cs = new PDPageContentStream(doc, page)) {
                // ============================================================
                // 1. DUAL LOGOS & HEADER SECTION
                // ============================================================
                float headerTop = pageHeight - 25;
                float logoSize = 62;

                // 1a. Left Logo: MediAssist Brand Logo
                BufferedImage mediAssistImg = loadMediAssistLogo();
                if (mediAssistImg != null) {
                    PDImageXObject pdLogo = LosslessFactory.createFromImage(doc, mediAssistImg);
                    cs.drawImage(pdLogo, margin, headerTop - logoSize, logoSize, logoSize);
                }

                // 1b. Right Logo: Hospital Medical Logo (Uploaded in Settings or default crest)
                BufferedImage hospitalLogoImg = loadHospitalLogo(logoUrl);
                if (hospitalLogoImg != null) {
                    PDImageXObject pdHospLogo = LosslessFactory.createFromImage(doc, hospitalLogoImg);
                    cs.drawImage(pdHospLogo, pageWidth - margin - logoSize, headerTop - logoSize, logoSize, logoSize);
                }

                // 1c. Center Hospital Header Text
                float centerLeft = margin + logoSize + 10;
                float centerRight = pageWidth - margin - logoSize - 10;
                float centerWidth = centerRight - centerLeft;

                String mainHospTitle = hospitalName;
                String subHospTitle = "Pradyumna Bal Memorial Hospital";
                if (hospitalName.contains("(")) {
                    int pIdx = hospitalName.indexOf("(");
                    mainHospTitle = hospitalName.substring(0, pIdx).trim();
                    subHospTitle = hospitalName.substring(pIdx).trim();
                }

                cs.setNonStrokingColor(darkGreen[0], darkGreen[1], darkGreen[2]);
                cs.beginText();
                cs.setFont(fontBold, 15);
                float titleW = fontBold.getStringWidth(truncate(mainHospTitle, 40)) / 1000 * 15;
                cs.newLineAtOffset(centerLeft + (centerWidth - titleW) / 2, headerTop - 18);
                cs.showText(truncate(mainHospTitle, 40));
                cs.endText();

                cs.beginText();
                cs.setFont(fontBold, 12);
                float subW = fontBold.getStringWidth(truncate(subHospTitle, 45)) / 1000 * 12;
                cs.newLineAtOffset(centerLeft + (centerWidth - subW) / 2, headerTop - 34);
                cs.showText(truncate(subHospTitle, 45));
                cs.endText();

                cs.setNonStrokingColor(mutedText[0], mutedText[1], mutedText[2]);
                cs.beginText();
                cs.setFont(fontRegular, 7.5f);
                String badgeText = "NABH & NABL ACCREDITED SUPER SPECIALITY HOSPITAL & MEDICAL COLLEGE";
                float badgeW = fontRegular.getStringWidth(badgeText) / 1000 * 7.5f;
                cs.newLineAtOffset(centerLeft + (centerWidth - badgeW) / 2, headerTop - 48);
                cs.showText(badgeText);
                cs.endText();

                float y = headerTop - logoSize - 8;

                // ============================================================
                // 2. "OP CASE SHEET" BANNER BAR
                // ============================================================
                float bannerHeight = 20;
                cs.setNonStrokingColor(forestGreen[0], forestGreen[1], forestGreen[2]);
                cs.addRect(margin, y - bannerHeight, contentWidth, bannerHeight);
                cs.fill();

                cs.setNonStrokingColor(white[0], white[1], white[2]);
                cs.beginText();
                cs.setFont(fontBold, 11);
                String bannerTitle = "OP CASE SHEET";
                float bTitleW = fontBold.getStringWidth(bannerTitle) / 1000 * 11;
                cs.newLineAtOffset(margin + (contentWidth - bTitleW) / 2, y - 14);
                cs.showText(bannerTitle);
                cs.endText();

                y -= bannerHeight + 10;

                // ============================================================
                // 3. TWO-COLUMN PATIENT & APPOINTMENT DETAILS
                // ============================================================
                float col1X = margin;
                float col1ValX = margin + 68;
                float col2X = margin + 190;
                float col2ValX = margin + 268;

                float infoStartY = y;
                float lineH = 15;

                // Left Column:
                drawGridRow(cs, fontBold, fontRegular, col1X, col1ValX, y, "MRN :", mrn, darkText, mutedText, 8);
                y -= lineH;
                drawGridRow(cs, fontBold, fontRegular, col1X, col1ValX, y, "Patient Name :", truncate(patientName, 22), darkText, darkText, 8.5f);
                y -= lineH;
                drawGridRow(cs, fontBold, fontRegular, col1X, col1ValX, y, "Sex / Age :", "MALE / 21 Years", darkText, mutedText, 8);
                y -= lineH;
                drawGridRow(cs, fontBold, fontRegular, col1X, col1ValX, y, "Address :", truncate(hospitalAddr.contains(",") ? hospitalAddr.split(",")[1].trim() + ", ODISHA" : "BHUBANESWAR, ODISHA", 24), darkText, mutedText, 8);
                y -= lineH;
                drawGridRow(cs, fontBold, fontRegular, col1X, col1ValX, y, "Visit No :", "OP-00" + serialNo + " (First Visit)", darkText, mutedText, 8);
                y -= lineH;

                // Queue No in large prominent style
                cs.setNonStrokingColor(mutedText[0], mutedText[1], mutedText[2]);
                cs.beginText();
                cs.setFont(fontBold, 8.5f);
                cs.newLineAtOffset(col1X, y);
                cs.showText("Queue / Token :");
                cs.endText();

                cs.setNonStrokingColor(forestGreen[0], forestGreen[1], forestGreen[2]);
                cs.beginText();
                cs.setFont(fontBold, 15);
                cs.newLineAtOffset(col1ValX, y - 2);
                cs.showText("#" + tokenStr);
                cs.endText();

                // Right Column (align with infoStartY):
                float rightY = infoStartY;
                drawGridRow(cs, fontBold, fontRegular, col2X, col2ValX, rightY, "Date & Time :", dateStr, darkText, mutedText, 8);
                rightY -= lineH;
                drawGridRow(cs, fontBold, fontRegular, col2X, col2ValX, rightY, "Department :", department.toUpperCase(), darkText, darkText, 8.5f);
                rightY -= lineH;
                drawGridRow(cs, fontBold, fontRegular, col2X, col2ValX, rightY, "Visiting Doctor :", truncate(doctorName, 20), darkText, darkText, 8.5f);
                rightY -= lineH;
                drawGridRow(cs, fontBold, fontRegular, col2X, col2ValX, rightY, "Consultation :", "FIRST VISIT (OPD)", darkText, mutedText, 8);
                rightY -= lineH;
                drawGridRow(cs, fontBold, fontRegular, col2X, col2ValX, rightY, "Mobile No :", patientPhone, darkText, mutedText, 8);
                rightY -= lineH;
                drawGridRow(cs, fontBold, fontRegular, col2X, col2ValX, rightY, "Tentative Window :", truncate(timeSlot, 20), darkText, forestGreen, 8);

                // --- DUAL QR CODES (START CONSULTATION & END CONSULTATION) ---
                float qrBoxW = 66;
                float qrBoxH = 88;
                float qrY = infoStartY - qrBoxH + 12;
                float qrSize = 48;

                // 1. START QR Box (Green)
                float startQrX = pageWidth - margin - (2 * qrBoxW + 8);
                cs.setNonStrokingColor(240, 253, 244); // light green bg
                cs.addRect(startQrX, qrY, qrBoxW, qrBoxH);
                cs.fill();
                cs.setStrokingColor(22, 101, 52); // dark green border
                cs.setLineWidth(0.8f);
                cs.addRect(startQrX, qrY, qrBoxW, qrBoxH);
                cs.stroke();

                // Header badge
                cs.setNonStrokingColor(22, 101, 52);
                cs.addRect(startQrX, qrY + qrBoxH - 14, qrBoxW, 14);
                cs.fill();
                cs.setNonStrokingColor(255, 255, 255);
                cs.beginText();
                cs.setFont(fontBold, 6.5f);
                String sTitle = "START (ENTER)";
                float sTW = fontBold.getStringWidth(sTitle) / 1000 * 6.5f;
                cs.newLineAtOffset(startQrX + (qrBoxW - sTW) / 2, qrY + qrBoxH - 10);
                cs.showText(sTitle);
                cs.endText();

                // Start QR Code
                BufferedImage startQrImg = generateQrImage("START:" + qrToken, 100, 100);
                PDImageXObject pdStartQr = LosslessFactory.createFromImage(doc, startQrImg);
                cs.drawImage(pdStartQr, startQrX + (qrBoxW - qrSize) / 2, qrY + 18, qrSize, qrSize);

                // Start PIN text
                cs.setNonStrokingColor(22, 101, 52);
                cs.beginText();
                cs.setFont(fontBold, 7f);
                String sPin = "PIN: " + (qrToken.length() > 8 ? qrToken.substring(0, 8) : qrToken);
                float sPinW = fontBold.getStringWidth(sPin) / 1000 * 7f;
                cs.newLineAtOffset(startQrX + (qrBoxW - sPinW) / 2, qrY + 5);
                cs.showText(sPin);
                cs.endText();

                // 2. END QR Box (Red)
                float endQrX = startQrX + qrBoxW + 8;
                cs.setNonStrokingColor(254, 242, 242); // light red bg
                cs.addRect(endQrX, qrY, qrBoxW, qrBoxH);
                cs.fill();
                cs.setStrokingColor(220, 38, 38); // red border
                cs.setLineWidth(0.8f);
                cs.addRect(endQrX, qrY, qrBoxW, qrBoxH);
                cs.stroke();

                // Header badge
                cs.setNonStrokingColor(220, 38, 38);
                cs.addRect(endQrX, qrY + qrBoxH - 14, qrBoxW, 14);
                cs.fill();
                cs.setNonStrokingColor(255, 255, 255);
                cs.beginText();
                cs.setFont(fontBold, 6.5f);
                String eTitle = "END (EXIT)";
                float eTW = fontBold.getStringWidth(eTitle) / 1000 * 6.5f;
                cs.newLineAtOffset(endQrX + (qrBoxW - eTW) / 2, qrY + qrBoxH - 10);
                cs.showText(eTitle);
                cs.endText();

                // End QR Code
                BufferedImage endQrImg = generateQrImage("END:" + qrToken, 100, 100);
                PDImageXObject pdEndQr = LosslessFactory.createFromImage(doc, endQrImg);
                cs.drawImage(pdEndQr, endQrX + (qrBoxW - qrSize) / 2, qrY + 18, qrSize, qrSize);

                // End PIN text
                cs.setNonStrokingColor(185, 28, 28);
                cs.beginText();
                cs.setFont(fontBold, 7f);
                String ePin = "PIN: " + (qrToken.length() > 8 ? qrToken.substring(0, 8) : qrToken);
                float ePinW = fontBold.getStringWidth(ePin) / 1000 * 7f;
                cs.newLineAtOffset(endQrX + (qrBoxW - ePinW) / 2, qrY + 5);
                cs.showText(ePin);
                cs.endText();

                y -= 20;

                // Separator rule
                cs.setStrokingColor(borderGray[0], borderGray[1], borderGray[2]);
                cs.setLineWidth(0.8f);
                cs.moveTo(margin, y);
                cs.lineTo(pageWidth - margin, y);
                cs.stroke();

                y -= 8;

                // ============================================================
                // 4. INITIAL ASSESSMENT AT OPD (VITALS ROW)
                // ============================================================
                float assessmentH = 16;
                cs.setNonStrokingColor(lightGreenBg[0], lightGreenBg[1], lightGreenBg[2]);
                cs.addRect(margin, y - assessmentH, contentWidth, assessmentH);
                cs.fill();

                cs.setStrokingColor(borderGray[0], borderGray[1], borderGray[2]);
                cs.setLineWidth(0.5f);
                cs.addRect(margin, y - assessmentH, contentWidth, assessmentH);
                cs.stroke();

                cs.setNonStrokingColor(forestGreen[0], forestGreen[1], forestGreen[2]);
                cs.beginText();
                cs.setFont(fontBold, 8);
                String assessTitle = "INITIAL ASSESSMENT AT OPD";
                float aTitleW = fontBold.getStringWidth(assessTitle) / 1000 * 8;
                cs.newLineAtOffset(margin + (contentWidth - aTitleW) / 2, y - 11);
                cs.showText(assessTitle);
                cs.endText();

                y -= assessmentH + 16;

                // Vitals blanks line
                cs.setNonStrokingColor(darkText[0], darkText[1], darkText[2]);
                cs.beginText();
                cs.setFont(fontBold, 7.5f);
                cs.newLineAtOffset(margin + 5, y);
                cs.showText("HEIGHT : __________    WEIGHT : __________    BP : _________ / _________    PULSE : _________    TEMP : _________    SPO2 : _________");
                cs.endText();

                // Room stamp indicator on the right
                float roomBoxW = 75;
                float roomBoxH = 22;
                cs.setStrokingColor(forestGreen[0], forestGreen[1], forestGreen[2]);
                cs.setLineWidth(1.2f);
                cs.addRect(pageWidth - margin - roomBoxW, y - 6, roomBoxW, roomBoxH);
                cs.stroke();

                cs.setNonStrokingColor(forestGreen[0], forestGreen[1], forestGreen[2]);
                cs.beginText();
                cs.setFont(fontBold, 9);
                String rTxt = "ROOM " + room;
                float rW = fontBold.getStringWidth(rTxt) / 1000 * 9;
                cs.newLineAtOffset(pageWidth - margin - roomBoxW + (roomBoxW - rW) / 2, y + 2);
                cs.showText(rTxt);
                cs.endText();

                y -= 22;

                // Separator rule
                cs.setStrokingColor(borderGray[0], borderGray[1], borderGray[2]);
                cs.setLineWidth(0.8f);
                cs.moveTo(margin, y);
                cs.lineTo(pageWidth - margin, y);
                cs.stroke();

                // ============================================================
                // 5. CLINICAL EXAMINATION & NOTES (WATERMARKED SECTION)
                // ============================================================
                y -= 22;
                drawClinicalSection(cs, fontBold, margin, y, "HISTORY & CHIEF COMPLAINTS :");
                drawRuledLines(cs, margin, y - 18, contentWidth, 3, 22, borderGray);

                y -= 85;
                drawClinicalSection(cs, fontBold, margin, y, "CLINICAL EXAMINATION & OBSERVATIONS :");
                drawRuledLines(cs, margin, y - 18, contentWidth, 3, 22, borderGray);

                y -= 85;
                drawClinicalSection(cs, fontBold, margin, y, "DIAGNOSIS :");
                drawRuledLines(cs, margin, y - 18, contentWidth, 2, 22, borderGray);

                y -= 65;
                drawClinicalSection(cs, fontBold, margin, y, "INVESTIGATION / LAB ORDERS :");
                drawRuledLines(cs, margin, y - 18, contentWidth, 2, 22, borderGray);

                y -= 65;
                drawClinicalSection(cs, fontBold, margin, y, "Rx / TREATMENT PLAN & MEDICATIONS :");
                drawRuledLines(cs, margin, y - 18, contentWidth, 3, 22, borderGray);

                // ============================================================
                // 6. DOCTOR'S STAMP & SIGNATURE BOX (BOTTOM RIGHT)
                // ============================================================
                float stampBoxW = 210;
                float stampBoxH = 68;
                float stampX = pageWidth - margin - stampBoxW;
                float stampY = 82;

                cs.setStrokingColor(forestGreen[0], forestGreen[1], forestGreen[2]);
                cs.setLineWidth(0.8f);
                cs.addRect(stampX, stampY, stampBoxW, stampBoxH);
                cs.stroke();

                cs.setNonStrokingColor(forestGreen[0], forestGreen[1], forestGreen[2]);
                cs.beginText();
                cs.setFont(fontBold, 8);
                cs.newLineAtOffset(stampX + 10, stampY + stampBoxH - 14);
                cs.showText("CONSULTANT SIGNATURE & STAMP");
                cs.endText();

                cs.setNonStrokingColor(darkText[0], darkText[1], darkText[2]);
                cs.beginText();
                cs.setFont(fontBold, 8.5f);
                cs.newLineAtOffset(stampX + 10, stampY + stampBoxH - 28);
                cs.showText(cleanText(doctorName));
                cs.endText();

                cs.setNonStrokingColor(mutedText[0], mutedText[1], mutedText[2]);
                cs.beginText();
                cs.setFont(fontRegular, 7);
                cs.newLineAtOffset(stampX + 10, stampY + stampBoxH - 40);
                cs.showText("Department of " + cleanText(department));
                cs.endText();

                cs.beginText();
                cs.setFont(fontRegular, 7);
                cs.newLineAtOffset(stampX + 10, stampY + stampBoxH - 52);
                cs.showText("Hospital OPD Clinical Wing - Room " + room);
                cs.endText();

                cs.beginText();
                cs.setFont(fontOblique, 7);
                cs.newLineAtOffset(stampX + 10, stampY + 5);
                cs.showText("Sign: ____________________________");
                cs.endText();

                // Instructions block on the bottom left
                cs.setNonStrokingColor(mutedText[0], mutedText[1], mutedText[2]);
                cs.beginText();
                cs.setFont(fontBold, 7);
                cs.newLineAtOffset(margin + 5, stampY + stampBoxH - 14);
                cs.showText("IMPORTANT INSTRUCTIONS FOR PATIENTS:");
                cs.endText();

                String[] patientInstructions = {
                    "- Please report 15 mins before your tentative window for QR check-in",
                    "- This slip is required during consultation and diagnostic pharmacy bills",
                    "- Valid only for the date and consulting department mentioned above",
                    "- Track live queue anytime by messaging 'queue status' on WhatsApp"
                };

                float instY = stampY + stampBoxH - 26;
                cs.setFont(fontRegular, 6.5f);
                for (String inst : patientInstructions) {
                    cs.beginText();
                    cs.newLineAtOffset(margin + 5, instY);
                    cs.showText(inst);
                    cs.endText();
                    instY -= 11;
                }

                // ============================================================
                // 7. FOOTER CONTACT BAND
                // ============================================================
                float footerH = 18;
                float footerY = 46;

                cs.setNonStrokingColor(forestGreen[0], forestGreen[1], forestGreen[2]);
                cs.addRect(margin, footerY, contentWidth, footerH);
                cs.fill();

                cs.setNonStrokingColor(white[0], white[1], white[2]);
                cs.beginText();
                cs.setFont(fontBold, 7);
                String contactLine = "Hello KIMS: " + hospitalPhone + "   |   " + hospitalEmail + "   |   " + hospitalWeb;
                float cLineW = fontBold.getStringWidth(contactLine) / 1000 * 7;
                cs.newLineAtOffset(margin + (contentWidth - cLineW) / 2, footerY + 5);
                cs.showText(contactLine);
                cs.endText();

                // Campus address subline below band
                cs.setNonStrokingColor(darkText[0], darkText[1], darkText[2]);
                cs.beginText();
                cs.setFont(fontRegular, 6.5f);
                String addrLine = truncate(hospitalAddr, 95);
                float aLineW = fontRegular.getStringWidth(addrLine) / 1000 * 6.5f;
                cs.newLineAtOffset(margin + (contentWidth - aLineW) / 2, footerY - 10);
                cs.showText(addrLine);
                cs.endText();

                // MediAssist Attribution
                cs.setNonStrokingColor(mutedText[0], mutedText[1], mutedText[2]);
                cs.beginText();
                cs.setFont(fontBold, 6);
                String poweredBy = "Powered by MediAssist Digital Healthcare   |   Official Electronic OPD Outpatient Record";
                float pW = fontBold.getStringWidth(poweredBy) / 1000 * 6;
                cs.newLineAtOffset(margin + (contentWidth - pW) / 2, footerY - 19);
                cs.showText(poweredBy);
                cs.endText();
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            doc.save(baos);
            return baos.toByteArray();
        }
    }

    private void drawGridRow(PDPageContentStream cs, PDType1Font fontBold, PDType1Font fontRegular,
                             float labelX, float valX, float y,
                             String label, String value,
                             int[] labelColor, int[] valColor, float fontSize) throws java.io.IOException {
        cs.setNonStrokingColor(labelColor[0], labelColor[1], labelColor[2]);
        cs.beginText();
        cs.setFont(fontBold, fontSize);
        cs.newLineAtOffset(labelX, y);
        cs.showText(cleanText(label));
        cs.endText();

        cs.setNonStrokingColor(valColor[0], valColor[1], valColor[2]);
        cs.beginText();
        cs.setFont(fontRegular, fontSize);
        cs.newLineAtOffset(valX, y);
        cs.showText(cleanText(value));
        cs.endText();
    }

    private void drawClinicalSection(PDPageContentStream cs, PDType1Font fontBold, float x, float y, String title) throws java.io.IOException {
        cs.setNonStrokingColor(15, 23, 42);
        cs.beginText();
        cs.setFont(fontBold, 8);
        cs.newLineAtOffset(x, y);
        cs.showText(title);
        cs.endText();
    }

    private void drawRuledLines(PDPageContentStream cs, float x, float startY, float width, int count, float spacing, int[] color) throws java.io.IOException {
        cs.setStrokingColor(color[0], color[1], color[2]);
        cs.setLineWidth(0.4f);
        float curY = startY;
        for (int i = 0; i < count; i++) {
            cs.moveTo(x, curY);
            cs.lineTo(x + width, curY);
            cs.stroke();
            curY -= spacing;
        }
    }

    /**
     * Load MediAssist official brand logo.
     */
    private BufferedImage loadMediAssistLogo() {
        try {
            InputStream is = getClass().getResourceAsStream("/static/images/mediassist_logo.png");
            if (is != null) {
                BufferedImage img = ImageIO.read(is);
                if (img != null) return toStandardImage(img);
            }
            File f = new File("backend/src/main/resources/static/images/mediassist_logo.png");
            if (f.exists()) {
                BufferedImage img = ImageIO.read(f);
                if (img != null) return toStandardImage(img);
            }
            File f2 = new File("frontend/images/mediassist_logo.png");
            if (f2.exists()) {
                BufferedImage img = ImageIO.read(f2);
                if (img != null) return toStandardImage(img);
            }
        } catch (Exception e) {
            logger.warn("Could not load MediAssist logo file: {}", e.getMessage());
        }
        return generateFallbackMediAssistLogo();
    }

    /**
     * Load Hospital / Medical Logo (uploaded in Settings or default crest).
     */
    private BufferedImage loadHospitalLogo(String logoUrl) {
        if (logoUrl != null && !logoUrl.isBlank()) {
            try {
                if (logoUrl.startsWith("data:image")) {
                    int commaIdx = logoUrl.indexOf(",");
                    if (commaIdx >= 0) {
                        String b64 = logoUrl.substring(commaIdx + 1);
                        byte[] bytes = Base64.getDecoder().decode(b64);
                        BufferedImage img = ImageIO.read(new ByteArrayInputStream(bytes));
                        if (img != null) return toStandardImage(img);
                    }
                } else if (logoUrl.startsWith("http://") || logoUrl.startsWith("https://")) {
                    URL url = URI.create(logoUrl).toURL();
                    BufferedImage img = ImageIO.read(url);
                    if (img != null) return toStandardImage(img);
                }
            } catch (Exception e) {
                logger.warn("Could not decode custom hospital logo: {}", e.getMessage());
            }
        }

        // Fallback: Default medical crest
        try {
            InputStream is = getClass().getResourceAsStream("/static/images/default_hospital_crest.png");
            if (is != null) {
                BufferedImage img = ImageIO.read(is);
                if (img != null) return toStandardImage(img);
            }
            File f = new File("backend/src/main/resources/static/images/default_hospital_crest.png");
            if (f.exists()) {
                BufferedImage img = ImageIO.read(f);
                if (img != null) return toStandardImage(img);
            }
            File f2 = new File("frontend/images/default_hospital_crest.png");
            if (f2.exists()) {
                BufferedImage img = ImageIO.read(f2);
                if (img != null) return toStandardImage(img);
            }
        } catch (Exception e) {
            logger.warn("Could not load default hospital crest: {}", e.getMessage());
        }

        return generateFallbackHospitalCrest();
    }

    private BufferedImage toStandardImage(BufferedImage src) {
        if (src == null) return null;
        BufferedImage copy = new BufferedImage(src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = copy.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g2.drawImage(src, 0, 0, null);
        g2.dispose();
        return copy;
    }

    private BufferedImage generateFallbackMediAssistLogo() {
        int s = 150;
        BufferedImage img = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = img.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setColor(new Color(2, 132, 199));
        g2.fillOval(5, 5, s - 10, s - 10);
        g2.setColor(Color.WHITE);
        g2.fillRect(s / 2 - 12, 28, 24, s - 56);
        g2.fillRect(28, s / 2 - 12, s - 56, 24);
        g2.dispose();
        return img;
    }

    private BufferedImage generateFallbackHospitalCrest() {
        int s = 150;
        BufferedImage img = new BufferedImage(s, s, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g2 = img.createGraphics();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2.setColor(new Color(22, 101, 52));
        g2.drawOval(8, 8, s - 16, s - 16);
        g2.drawOval(14, 14, s - 28, s - 28);
        g2.fillRect(s / 2 - 4, 30, 8, 90);
        g2.fillOval(s / 2 - 8, 24, 16, 16);
        g2.dispose();
        return img;
    }

    private BufferedImage generateQrImage(String content, int width, int height) throws Exception {
        QRCodeWriter qrCodeWriter = new QRCodeWriter();
        BitMatrix bitMatrix = qrCodeWriter.encode(content, BarcodeFormat.QR_CODE, width, height);
        return MatrixToImageWriter.toBufferedImage(bitMatrix);
    }

    private String cleanText(String s) {
        if (s == null) return "";
        return s.replace("\u20B9", "Rs. ")
                .replace("\u2022", "-")
                .replaceAll("[^\\x20-\\x7E]", "")
                .trim();
    }

    private String truncate(String s, int maxLen) {
        if (s == null) return "";
        String clean = cleanText(s);
        return clean.length() > maxLen ? clean.substring(0, maxLen - 3) + "..." : clean;
    }
}
