package com.med.assistant.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.persistence.*;

@Entity
@Table(name = "doctors")
public class Doctor {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String department; // e.g. Cardiology, Pediatrics, General Medicine

    private int dailyTokenLimit = 25;
    private int currentTokenCount = 0;
    private boolean availableToday = true;
    private String roomNumber = "101";
    private double consultationFee = 500.0;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "hospital_id")
    @JsonIgnore
    private Hospital hospital;

    public Doctor() {}

    public Doctor(String name, String department, Hospital hospital, int dailyTokenLimit, double consultationFee) {
        this.name = name;
        this.department = department;
        this.hospital = hospital;
        this.dailyTokenLimit = dailyTokenLimit;
        this.consultationFee = consultationFee;
        this.availableToday = true;
        this.currentTokenCount = 0;
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDepartment() { return department; }
    public void setDepartment(String department) { this.department = department; }

    public int getDailyTokenLimit() { return dailyTokenLimit; }
    public void setDailyTokenLimit(int dailyTokenLimit) { this.dailyTokenLimit = dailyTokenLimit; }

    public int getCurrentTokenCount() { return currentTokenCount; }
    public void setCurrentTokenCount(int currentTokenCount) { this.currentTokenCount = currentTokenCount; }

    public boolean isAvailableToday() { return availableToday; }
    public void setAvailableToday(boolean availableToday) { this.availableToday = availableToday; }

    public boolean isAvailable() { return availableToday; }
    public void setAvailable(boolean available) { this.availableToday = available; }

    public String getRoomNumber() { return roomNumber; }
    public void setRoomNumber(String roomNumber) { this.roomNumber = roomNumber; }

    public double getConsultationFee() { return consultationFee; }
    public void setConsultationFee(double consultationFee) { this.consultationFee = consultationFee; }

    public Hospital getHospital() { return hospital; }
    public void setHospital(Hospital hospital) { this.hospital = hospital; }
}
