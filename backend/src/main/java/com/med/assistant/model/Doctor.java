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
    private String availableTime; // e.g. "10:00 AM - 2:00 PM"
    private double rating = 4.9;
    private int totalReviews = 42;

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

    public String getAvailableTime() { return availableTime; }
    public void setAvailableTime(String availableTime) { this.availableTime = availableTime; }

    public Hospital getHospital() { return hospital; }
    public void setHospital(Hospital hospital) { this.hospital = hospital; }

    public double getRating() { return rating; }
    public void setRating(double rating) { this.rating = rating; }

    public int getTotalReviews() { return totalReviews; }
    public void setTotalReviews(int totalReviews) { this.totalReviews = totalReviews; }

    public void addReviewRating(int stars) {
        if (stars < 1 || stars > 5) return;
        double currentTotal = this.rating * this.totalReviews;
        this.totalReviews++;
        this.rating = Math.round(((currentTotal + stars) / this.totalReviews) * 10.0) / 10.0;
    }
}
