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

    public record HospitalDiscoveryResult(
            List<NearbyHospitalResult> localBookingHospitals,
            List<NearbyHospitalResult> regionalReferralHospitals
    ) {}

    /**
     * Discovers hospitals in two categories:
     * 1. Local Booking Hospitals (<= 25 km): Available to book OPD tokens instantly with us.
     * 2. Regional Referral Hospitals (> 25 km): For visiting specialized departments outside the immediate radius.
     */
    public HospitalDiscoveryResult discoverHospitals(double userLat, double userLon) {
        List<Hospital> allHospitals = hospitalRepository.findByActiveTrue();

        List<NearbyHospitalResult> allWithDist = allHospitals.stream()
                .map(h -> {
                    double dist = calculateDistanceKm(userLat, userLon, h.getLatitude(), h.getLongitude());
                    int availableDocs = (int) h.getDoctors().stream()
                            .filter(d -> d.isAvailableToday())
                            .count();
                    return new NearbyHospitalResult(h, dist, availableDocs);
                })
                .sorted(Comparator.comparingDouble(NearbyHospitalResult::distanceKm))
                .collect(Collectors.toList());

        List<NearbyHospitalResult> local = allWithDist.stream()
                .filter(res -> res.distanceKm() <= 25.0)
                .limit(5)
                .collect(Collectors.toList());

        List<NearbyHospitalResult> regional = allWithDist.stream()
                .filter(res -> res.distanceKm() > 25.0)
                .limit(5)
                .collect(Collectors.toList());

        // If no regional hospitals exist > 25km, but more local exist, or vice versa, return what exists
        return new HospitalDiscoveryResult(local, regional);
    }

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
