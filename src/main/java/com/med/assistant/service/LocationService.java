package com.med.assistant.service;

import com.med.assistant.model.Hospital;
import com.med.assistant.repository.HospitalRepository;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class LocationService {

    private final HospitalRepository hospitalRepository;

    public LocationService(HospitalRepository hospitalRepository) {
        this.hospitalRepository = hospitalRepository;
    }

    public record NearbyHospitalResult(Hospital hospital, double distanceKm, int availableDoctorsCount) {}

    /**
     * Calculates nearby hospitals within a radius using Haversine formula.
     * Works seamlessly across both H2 (local testing) and PostgreSQL.
     */
    public List<NearbyHospitalResult> findNearbyHospitals(double userLat, double userLon, double maxRadiusKm) {
        List<Hospital> allHospitals = hospitalRepository.findByActiveTrue();

        return allHospitals.stream()
                .map(h -> {
                    double dist = calculateDistanceKm(userLat, userLon, h.getLatitude(), h.getLongitude());
                    int availableDocs = (int) h.getDoctors().stream()
                            .filter(d -> d.isAvailableToday())
                            .count();
                    return new NearbyHospitalResult(h, dist, availableDocs);
                })
                .filter(res -> res.distanceKm() <= maxRadiusKm)
                .sorted(Comparator.comparingDouble(NearbyHospitalResult::distanceKm))
                .limit(5)
                .collect(Collectors.toList());
    }

    private double calculateDistanceKm(double lat1, double lon1, double lat2, double lon2) {
        final int R = 6371; // Earth radius in km
        double latDistance = Math.toRadians(lat2 - lat1);
        double lonDistance = Math.toRadians(lon2 - lon1);
        double a = Math.sin(latDistance / 2) * Math.sin(latDistance / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(lonDistance / 2) * Math.sin(lonDistance / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return Math.round(R * c * 10.0) / 10.0; // Round to 1 decimal place
    }
}
