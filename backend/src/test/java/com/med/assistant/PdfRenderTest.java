package com.med.assistant;

import com.med.assistant.model.Appointment;
import com.med.assistant.model.Doctor;
import com.med.assistant.model.Hospital;
import com.med.assistant.service.AppointmentSlipPdfService;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.time.LocalDate;

public class PdfRenderTest {

    @Test
    public void testGenerateAndRenderCaseSheet() throws Exception {
        Hospital hospital = new Hospital();
        hospital.setId(1L);
        hospital.setName("Kalinga Institute of Medical Sciences (KIMS)");
        hospital.setAddress("Kushabhadra Campus (KIIT Campus-5), Patia, Bhubaneswar - 751024, Odisha, India");
        hospital.setPhone("0674 2304400 / 7111000");

        Doctor doctor = new Doctor();
        doctor.setId(10L);
        doctor.setName("Dr. Soumya Behera");
        doctor.setDepartment("Ophthalmology");
        doctor.setRoomNumber("22");
        doctor.setConsultationFee(500.0);

        Appointment appt = new Appointment();
        appt.setId(3L);
        appt.setSerialNumber(25);
        appt.setHospital(hospital);
        appt.setDoctor(doctor);
        appt.setPatientName("Mr YAGNISH ANUPAM");
        appt.setPatientPhone("7978015617");
        appt.setAppointmentDate(LocalDate.of(2026, 9, 2));
        appt.setTimeSlot("04:15 PM - 04:30 PM");
        appt.setQrCodeToken("KIMS-OPD-7721");

        AppointmentSlipPdfService service = new AppointmentSlipPdfService();
        byte[] pdfBytes = service.generateAndGetBytes(appt);

        // Save PDF
        File pdfFile = new File("test_op_case_sheet.pdf");
        java.nio.file.Files.write(pdfFile.toPath(), pdfBytes);
        System.out.println("Saved PDF: " + pdfFile.getAbsolutePath() + " (" + pdfBytes.length + " bytes)");

        // Render to PNG
        try (PDDocument doc = PDDocument.load(pdfBytes)) {
            PDFRenderer renderer = new PDFRenderer(doc);
            BufferedImage image = renderer.renderImageWithDPI(0, 150);
            File pngFile = new File("test_op_case_sheet.png");
            ImageIO.write(image, "PNG", pngFile);
            System.out.println("Rendered PNG: " + pngFile.getAbsolutePath() + " (" + image.getWidth() + "x" + image.getHeight() + ")");
        }
    }
}
