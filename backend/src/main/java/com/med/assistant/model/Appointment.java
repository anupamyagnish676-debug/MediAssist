package com.med.assistant.model;

import jakarta.persistence.*;
import java.time.LocalDate;
import java.time.LocalDateTime;

@Entity
@Table(name = "appointments")
public class Appointment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "hospital_id", nullable = false)
    private Hospital hospital;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "doctor_id", nullable = false)
    private Doctor doctor;

    @Column(nullable = false)
    private String patientPhone;

    private String patientName = "Patient";
    private LocalDate appointmentDate = LocalDate.now();
    private String timeSlot = "10:00 AM";

    @Column(nullable = false)
    private int serialNumber; // Daily Token #

    @Column(unique = true, nullable = false)
    private String qrCodeToken; // Unique hash string for QR/barcode scan check-in

    @Enumerated(EnumType.STRING)
    private Status status = Status.CONFIRMED;

    private String pdfFilePath;
    private LocalDateTime consultationStartTime;
    private LocalDateTime consultationEndTime;
    private Integer rating; // 1 to 5 stars
    private String feedbackText;
    private LocalDateTime createdAt = LocalDateTime.now();

    public enum Status {
        CONFIRMED,
        CHECKED_IN,
        IN_CONSULTATION,
        COMPLETED,
        CANCELLED
    }

    public Appointment() {}

    public Appointment(Hospital hospital, Doctor doctor, String patientPhone, String patientName,
                       LocalDate appointmentDate, String timeSlot, int serialNumber, String qrCodeToken) {
        this.hospital = hospital;
        this.doctor = doctor;
        this.patientPhone = patientPhone;
        this.patientName = patientName;
        this.appointmentDate = appointmentDate;
        this.timeSlot = timeSlot;
        this.serialNumber = serialNumber;
        this.qrCodeToken = qrCodeToken;
        this.status = Status.CONFIRMED;
        this.createdAt = LocalDateTime.now();
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Hospital getHospital() { return hospital; }
    public void setHospital(Hospital hospital) { this.hospital = hospital; }

    public Doctor getDoctor() { return doctor; }
    public void setDoctor(Doctor doctor) { this.doctor = doctor; }

    public String getPatientPhone() { return patientPhone; }
    public void setPatientPhone(String patientPhone) { this.patientPhone = patientPhone; }

    public String getPatientName() { return patientName; }
    public void setPatientName(String patientName) { this.patientName = patientName; }

    public LocalDate getAppointmentDate() { return appointmentDate; }
    public void setAppointmentDate(LocalDate appointmentDate) { this.appointmentDate = appointmentDate; }

    public String getTimeSlot() { return timeSlot; }
    public void setTimeSlot(String timeSlot) { this.timeSlot = timeSlot; }

    public int getSerialNumber() { return serialNumber; }
    public void setSerialNumber(int serialNumber) { this.serialNumber = serialNumber; }

    public String getQrCodeToken() { return qrCodeToken; }
    public void setQrCodeToken(String qrCodeToken) { this.qrCodeToken = qrCodeToken; }

    public Status getStatus() { return status; }
    public void setStatus(Status status) { this.status = status; }

    public String getPdfFilePath() { return pdfFilePath; }
    public void setPdfFilePath(String pdfFilePath) { this.pdfFilePath = pdfFilePath; }

    public LocalDateTime getConsultationStartTime() { return consultationStartTime; }
    public void setConsultationStartTime(LocalDateTime consultationStartTime) { this.consultationStartTime = consultationStartTime; }

    public LocalDateTime getConsultationEndTime() { return consultationEndTime; }
    public void setConsultationEndTime(LocalDateTime consultationEndTime) { this.consultationEndTime = consultationEndTime; }

    public Integer getRating() { return rating; }
    public void setRating(Integer rating) { this.rating = rating; }

    public String getFeedbackText() { return feedbackText; }
    public void setFeedbackText(String feedbackText) { this.feedbackText = feedbackText; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
