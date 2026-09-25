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
    private String qualification = "MBBS, MD";
    private String availableDays = "MON,TUE,WED,THU,FRI,SAT";
    private String firstHalfTime = "09:00 AM - 01:00 PM";
    private String secondHalfTime = "05:00 PM - 09:00 PM";
    private int consultationDurationMinutes = 15;
    private int firstHalfLimit = 15;
    private int secondHalfLimit = 15;
    private String availableTime; // Combined summary, e.g. "09:00 AM - 01:00 PM & 05:00 PM - 09:00 PM"
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

    public String getQualification() { return qualification; }
    public void setQualification(String qualification) { this.qualification = qualification; }

    public String getAvailableDays() { return availableDays; }
    public void setAvailableDays(String availableDays) { this.availableDays = availableDays; }

    public String getFirstHalfTime() { return firstHalfTime; }
    public void setFirstHalfTime(String firstHalfTime) {
        this.firstHalfTime = firstHalfTime;
        refreshCombinedAvailableTime();
    }

    public String getSecondHalfTime() { return secondHalfTime; }
    public void setSecondHalfTime(String secondHalfTime) {
        this.secondHalfTime = secondHalfTime;
        refreshCombinedAvailableTime();
    }

    public int getConsultationDurationMinutes() { return consultationDurationMinutes > 0 ? consultationDurationMinutes : 15; }
    public void setConsultationDurationMinutes(int consultationDurationMinutes) { this.consultationDurationMinutes = consultationDurationMinutes; }

    public int getFirstHalfLimit() { return firstHalfLimit > 0 ? firstHalfLimit : 15; }
    public void setFirstHalfLimit(int firstHalfLimit) { this.firstHalfLimit = firstHalfLimit; }

    public int getSecondHalfLimit() { return secondHalfLimit > 0 ? secondHalfLimit : 15; }
    public void setSecondHalfLimit(int secondHalfLimit) { this.secondHalfLimit = secondHalfLimit; }

    public String getAvailableTime() {
        if (availableTime != null && !availableTime.isBlank()) return availableTime;
        return getFormattedShiftSummary();
    }
    public void setAvailableTime(String availableTime) { this.availableTime = availableTime; }

    public String getFormattedShiftSummary() {
        boolean hasFirst = firstHalfTime != null && !firstHalfTime.isBlank();
        boolean hasSecond = secondHalfTime != null && !secondHalfTime.isBlank();
        if (hasFirst && hasSecond) {
            return firstHalfTime + " & " + secondHalfTime;
        } else if (hasFirst) {
            return firstHalfTime;
        } else if (hasSecond) {
            return secondHalfTime;
        }
        return "09:00 AM - 01:00 PM & 05:00 PM - 09:00 PM";
    }

    public void refreshCombinedAvailableTime() {
        this.availableTime = getFormattedShiftSummary();
    }

    public boolean isAvailableOnDay(java.time.DayOfWeek day) {
        if (availableDays == null || availableDays.isBlank() || availableDays.equalsIgnoreCase("ALL")) return true;
        String dayName = day.name(); // e.g. MONDAY
        String shortName = dayName.substring(0, 3); // MON
        String upper = availableDays.toUpperCase();
        return upper.contains(dayName) || upper.contains(shortName);
    }

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
