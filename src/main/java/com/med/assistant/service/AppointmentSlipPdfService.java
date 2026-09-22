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
            // 1. Generate Base64 QR Code
            QRCodeWriter qrCodeWriter = new QRCodeWriter();
            String qrPayload = "APPT:" + appointment.getQrCodeToken();
            BitMatrix bitMatrix = qrCodeWriter.encode(qrPayload, BarcodeFormat.QR_CODE, 200, 200);
            ByteArrayOutputStream qrStream = new ByteArrayOutputStream();
            MatrixToImageWriter.writeToStream(bitMatrix, "PNG", qrStream);
            String qrBase64 = "data:image/png;base64," + Base64.getEncoder().encodeToString(qrStream.toByteArray());

            // 2. Prepare Template Context
            Context context = new Context();
            context.setVariable("hospitalName", appointment.getHospital().getName());
            context.setVariable("hospitalAddress", appointment.getHospital().getAddress());
            context.setVariable("hospitalPhone", appointment.getHospital().getPhone());
            context.setVariable("brandColor", appointment.getHospital().getBrandColor());
            context.setVariable("doctorName", appointment.getDoctor().getName());
            context.setVariable("department", appointment.getDoctor().getDepartment());
            context.setVariable("roomNumber", appointment.getDoctor().getRoomNumber());
            context.setVariable("patientName", appointment.getPatientName());
            context.setVariable("patientPhone", appointment.getPatientPhone());
            context.setVariable("appointmentDate", appointment.getAppointmentDate().format(DateTimeFormatter.ofPattern("dd MMMM yyyy")));
            context.setVariable("timeSlot", appointment.getTimeSlot());
            context.setVariable("serialNumber", String.format("%02d", appointment.getSerialNumber()));
            context.setVariable("qrCodeBase64", qrBase64);
            context.setVariable("qrToken", appointment.getQrCodeToken());
            context.setVariable("fee", appointment.getDoctor().getConsultationFee());

            // 3. Render HTML
            String renderedHtml = templateEngine.process("appointment-slip", context);

            // 4. Ensure directories exist
            Path slipsPath = Paths.get(uploadDir, "slips");
            Files.createDirectories(slipsPath);

            String fileName = "Appointment_Token_" + appointment.getSerialNumber() + "_" + appointment.getQrCodeToken() + ".pdf";
            File outputFile = slipsPath.resolve(fileName).toFile();

            // 5. Build PDF with OpenHTMLtoPDF
            try (FileOutputStream fos = new FileOutputStream(outputFile)) {
                PdfRendererBuilder builder = new PdfRendererBuilder();
                builder.useFastMode();
                builder.withHtmlContent(renderedHtml, "/");
                builder.toStream(fos);
                builder.run();
            }

            return outputFile.getAbsolutePath();

        } catch (Exception e) {
            throw new RuntimeException("Error generating appointment PDF slip: " + e.getMessage(), e);
        }
    }
}
