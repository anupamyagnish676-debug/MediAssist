package com.med.assistant.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.LocalDateTime;

@Entity
@Table(name = "patient_sessions")
public class PatientSession {

    @Id
    @Column(name = "phone_number", nullable = false, unique = true)
    private String phoneNumber;

    private Double latitude;
    private Double longitude;
    private String preferredDepartment;
    private LocalDateTime updatedAt = LocalDateTime.now();

    public PatientSession() {}

    public PatientSession(String phoneNumber, Double latitude, Double longitude, String preferredDepartment) {
        this.phoneNumber = phoneNumber;
        this.latitude = latitude;
        this.longitude = longitude;
        this.preferredDepartment = preferredDepartment;
        this.updatedAt = LocalDateTime.now();
    }

    public String getPhoneNumber() { return phoneNumber; }
    public void setPhoneNumber(String phoneNumber) { this.phoneNumber = phoneNumber; }

    public Double getLatitude() { return latitude; }
    public void setLatitude(Double latitude) { this.latitude = latitude; }

    public Double getLongitude() { return longitude; }
    public void setLongitude(Double longitude) { this.longitude = longitude; }

    public String getPreferredDepartment() { return preferredDepartment; }
    public void setPreferredDepartment(String preferredDepartment) { this.preferredDepartment = preferredDepartment; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
