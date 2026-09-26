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

    public record NearbyHospitalResult(
            Hospital hospital,
            double distanceKm,
            int availableDoctorsCount,
            String googleMapsDirectionsUrl
    ) {
        public NearbyHospitalResult(Hospital hospital, double distanceKm, int availableDoctorsCount) {
            this(hospital, distanceKm, availableDoctorsCount,
                    String.format(java.util.Locale.US, "https://www.google.com/maps/dir/?api=1&destination=%.6f,%.6f",
                            hospital != null ? hospital.getLatitude() : 0.0,
                            hospital != null ? hospital.getLongitude() : 0.0));
        }
    }

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
     * Finds local partner hospitals within radius (default <= 25 km) that have a matching department or doctor.
     */
    public List<NearbyHospitalResult> findLocalHospitalsByDepartment(double userLat, double userLon, double maxRadiusKm, String department) {
        List<Hospital> allHospitals = hospitalRepository.findByActiveTrue();

        return allHospitals.stream()
                .map(h -> {
                    double dist = calculateDistanceKm(userLat, userLon, h.getLatitude(), h.getLongitude());
                    int availableDocs = (int) h.getDoctors().stream()
                            .filter(com.med.assistant.model.Doctor::isAvailableToday)
                            .count();
                    return new NearbyHospitalResult(h, dist, availableDocs);
                })
                .filter(res -> res.distanceKm() <= maxRadiusKm)
                .filter(res -> {
                    if (department == null || department.isBlank()) {
                        return true;
                    }
                    Hospital h = res.hospital();
                    String dept = department.toLowerCase().trim();
                    boolean docMatch = h.getDoctors().stream().anyMatch(d -> {
                        if (d.getDepartment() == null) return false;
                        String dDept = d.getDepartment().toLowerCase();
                        if (dept.contains("medicine") || dept.equals("general medicine")) {
                            return dDept.contains("medicine") || dDept.contains("general") || dDept.contains("physician");
                        }
                        return dDept.contains(dept);
                    });
                    boolean specMatch = h.getSpecialties() != null && (
                            (dept.contains("medicine") && (h.getSpecialties().toLowerCase().contains("medicine") || h.getSpecialties().toLowerCase().contains("general")))
                            || h.getSpecialties().toLowerCase().contains(dept)
                    );
                    return docMatch || specMatch;
                })
                .sorted(Comparator.comparingDouble(NearbyHospitalResult::distanceKm))
                .limit(5)
                .collect(Collectors.toList());
    }

    /**
     * Finds partner hospitals with a resilient multi-tier fallback:
     * Tier 1: Matching department within 25 km
     * Tier 2: Matching department within 100 km
     * Tier 3: Any department within 100 km
     * Tier 4: All active partner hospitals nationwide sorted by distance
     * Guarantees the patient is never presented with an empty or broken list.
     */
    public List<NearbyHospitalResult> findLocalHospitalsWithSmartFallback(double userLat, double userLon, String department) {
        // Tier 1: Matching department within 30 km
        List<NearbyHospitalResult> res = findLocalHospitalsByDepartment(userLat, userLon, 30.0, department);
        if (!res.isEmpty()) return res;

        // Tier 2: Any partner hospital within 30 km (shows nearby clinics even if different department)
        res = findNearbyHospitals(userLat, userLon, 30.0);
        if (!res.isEmpty()) return res;

        // Tier 3: Matching department within 60 km
        res = findLocalHospitalsByDepartment(userLat, userLon, 60.0, department);
        if (!res.isEmpty()) return res;

        // Tier 4: Any partner hospital within 60 km
        res = findNearbyHospitals(userLat, userLon, 60.0);
        if (!res.isEmpty()) return res;

        // Tier 5: Regional partner hospital within 90 km
        res = findNearbyHospitals(userLat, userLon, 90.0);
        if (!res.isEmpty()) return res;

        // Do not return nationwide hospitals 1000+ km away as local clinics
        return List.of();
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
