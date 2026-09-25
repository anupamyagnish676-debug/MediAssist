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

    @Test
    public void testOpdScheduleServiceSlotMapping() {
        com.med.assistant.service.OpdScheduleService scheduleService = new com.med.assistant.service.OpdScheduleService();

        Doctor doc = new Doctor();
        doc.setId(1L);
        doc.setName("Dr. Sarah Jenkins");
        doc.setDepartment("Cardiology");
        doc.setAvailableDays("MON,TUE,WED,THU,FRI,SAT");
        doc.setFirstHalfTime("09:00 AM - 01:00 PM");
        doc.setSecondHalfTime("05:00 PM - 09:00 PM");
        doc.setConsultationDurationMinutes(15);
        doc.setFirstHalfLimit(16);
        doc.setSecondHalfLimit(16);

        // 1. Verify working days
        org.junit.jupiter.api.Assertions.assertTrue(doc.isAvailableOnDay(java.time.DayOfWeek.MONDAY));
        org.junit.jupiter.api.Assertions.assertTrue(doc.isAvailableOnDay(java.time.DayOfWeek.SATURDAY));
        org.junit.jupiter.api.Assertions.assertFalse(doc.isAvailableOnDay(java.time.DayOfWeek.SUNDAY));

        // 2. Generate slots for tomorrow
        LocalDate tomorrow = LocalDate.now().plusDays(1);
        java.util.List<com.med.assistant.service.OpdScheduleService.DoctorSlot> slots =
                scheduleService.generateDoctorSlots(doc, tomorrow, java.util.Collections.emptyList());

        org.junit.jupiter.api.Assertions.assertEquals(32, slots.size()); // 16 morning + 16 evening
        org.junit.jupiter.api.Assertions.assertEquals("09:00 AM - 09:15 AM", slots.get(0).timeWindow());
        org.junit.jupiter.api.Assertions.assertEquals("05:00 PM - 05:15 PM", slots.get(16).timeWindow());

        // 3. Test exact match preferred time "10:30 AM"
        com.med.assistant.service.OpdScheduleService.SlotMatchResult match1 =
                scheduleService.findNearestAvailableSlot(doc, tomorrow, java.util.Collections.emptyList(), "10:30 AM");

        org.junit.jupiter.api.Assertions.assertNotNull(match1);
        org.junit.jupiter.api.Assertions.assertTrue(match1.isExactMatch());
        org.junit.jupiter.api.Assertions.assertEquals("10:30 AM - 10:45 AM", match1.slot().timeWindow());

        // 4. Test when 10:30 AM is already booked - should map to nearest available (10:45 AM)
        Appointment existing = new Appointment();
        existing.setSerialNumber(match1.slot().slotNumber());
        existing.setTimeSlot(match1.slot().timeWindow());
        existing.setStatus(Appointment.Status.CONFIRMED);

        com.med.assistant.service.OpdScheduleService.SlotMatchResult match2 =
                scheduleService.findNearestAvailableSlot(doc, tomorrow, java.util.List.of(existing), "10:30 AM");

        org.junit.jupiter.api.Assertions.assertNotNull(match2);
        org.junit.jupiter.api.Assertions.assertFalse(match2.isExactMatch());
        org.junit.jupiter.api.Assertions.assertEquals("10:45 AM - 11:00 AM", match2.slot().timeWindow());
        org.junit.jupiter.api.Assertions.assertTrue(match2.noticeMessage().contains("Nearest available"));

        // 5. Test Evening Shift selection
        com.med.assistant.service.OpdScheduleService.SlotMatchResult eveningMatch =
                scheduleService.findNearestAvailableSlot(doc, tomorrow, java.util.Collections.emptyList(), "EVENING");

        org.junit.jupiter.api.Assertions.assertNotNull(eveningMatch);
        org.junit.jupiter.api.Assertions.assertEquals("05:00 PM - 05:15 PM", eveningMatch.slot().timeWindow());
        org.junit.jupiter.api.Assertions.assertEquals("Evening (2nd Half)", eveningMatch.slot().shiftName());

        System.out.println("OpdScheduleService tests all passed successfully!");
    }
}
