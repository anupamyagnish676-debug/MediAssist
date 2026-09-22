package com.med.assistant.service;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.med.assistant.model.Appointment;
import com.openhtmltopdf.pdfboxout.PdfRendererBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.UUID;

@Service
public class AppointmentSlipPdfService {

    private final TemplateEngine templateEngine;

    @Value("${app.storage.upload-dir:./uploads}")
    private String uploadDir;

    public AppointmentSlipPdfService(TemplateEngine templateEngine) {
        this.templateEngine = templateEngine;
    }

    public String generatePdfSlip(Appointment appointment) {
        try {
            // 1. Ensure slips directory exists safely
            Path slipsPath;
            try {
                slipsPath = Paths.get(uploadDir, "slips");
                Files.createDirectories(slipsPath);
            } catch (Exception ex) {
                slipsPath = Paths.get(System.getProperty("java.io.tmpdir"), "medbot", "slips");
                Files.createDirectories(slipsPath);
            }

            // 2. Generate QR Code image file
            String token = appointment.getQrCodeToken() != null ? appointment.getQrCodeToken() : UUID.randomUUID().toString().substring(0, 8);
            File qrFile = slipsPath.resolve("qr_" + token + ".png").toFile();
            QRCodeWriter qrCodeWriter = new QRCodeWriter();
            BitMatrix bitMatrix = qrCodeWriter.encode("APPT:" + token, BarcodeFormat.QR_CODE, 200, 200);
            MatrixToImageWriter.writeToPath(bitMatrix, "PNG", qrFile.toPath());

            // 3. Prepare Template Context
            Context context = new Context();
            context.setVariable("hospitalName", appointment.getHospital() != null ? appointment.getHospital().getName() : "MedAssist Partner Hospital");
            context.setVariable("hospitalAddress", appointment.getHospital() != null ? appointment.getHospital().getAddress() : "Central OPD Wing");
            context.setVariable("hospitalPhone", appointment.getHospital() != null ? appointment.getHospital().getPhone() : "+1 800-555-0199");
            context.setVariable("brandColor", appointment.getHospital() != null && appointment.getHospital().getBrandColor() != null ? appointment.getHospital().getBrandColor() : "#0284c7");
            context.setVariable("doctorName", appointment.getDoctor() != null ? appointment.getDoctor().getName() : "Duty Physician");
            context.setVariable("department", appointment.getDoctor() != null ? appointment.getDoctor().getDepartment() : "General OPD");
            context.setVariable("roomNumber", appointment.getDoctor() != null && appointment.getDoctor().getRoomNumber() != null ? appointment.getDoctor().getRoomNumber() : "101");
            context.setVariable("patientName", appointment.getPatientName() != null ? appointment.getPatientName() : "Patient");
            context.setVariable("patientPhone", appointment.getPatientPhone() != null ? appointment.getPatientPhone() : "+91 9876543210");
            context.setVariable("appointmentDate", appointment.getAppointmentDate() != null ? appointment.getAppointmentDate().format(DateTimeFormatter.ofPattern("dd MMMM yyyy")) : "Today");
            context.setVariable("timeSlot", appointment.getTimeSlot() != null ? appointment.getTimeSlot() : "11:00 AM");
            context.setVariable("serialNumber", String.format("%02d", appointment.getSerialNumber()));
            context.setVariable("qrCodeBase64", qrFile.toURI().toString());
            context.setVariable("qrToken", token);
            context.setVariable("fee", appointment.getDoctor() != null ? appointment.getDoctor().getConsultationFee() : 50.0);

            // 4. Render HTML with Thymeleaf
            String renderedHtml = templateEngine.process("appointment-slip", context);

            // 5. Build PDF with OpenHTMLtoPDF
            String fileName = "Appointment_Token_" + appointment.getSerialNumber() + "_" + token + ".pdf";
            File outputFile = slipsPath.resolve(fileName).toFile();

            try (FileOutputStream fos = new FileOutputStream(outputFile)) {
                PdfRendererBuilder builder = new PdfRendererBuilder();
                builder.useFastMode();
                builder.withHtmlContent(renderedHtml, slipsPath.toUri().toString());
                builder.toStream(fos);
                builder.run();
            }

            return outputFile.getAbsolutePath();

        } catch (Exception e) {
            throw new RuntimeException("Error generating appointment PDF slip: " + e.getMessage(), e);
        }
    }
}
