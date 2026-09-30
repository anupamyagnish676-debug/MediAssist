package com.med.assistant.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.med.assistant.model.Hospital;
import com.med.assistant.repository.HospitalRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.HashSet;

@Service
public class ExternalHospitalService {

    private static final Logger logger = LoggerFactory.getLogger(ExternalHospitalService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(6))
            .build();

    private final HospitalRepository hospitalRepository;

    @Value("${GOOGLE_MAPS_API_KEY:${app.google.maps.api-key:}}")
    private String googleMapsApiKey;

    public ExternalHospitalService(HospitalRepository hospitalRepository) {
        this.hospitalRepository = hospitalRepository;
    }

    public record ExternalHospital(
            String name,
            String address,
            double distanceKm,
            String mapsUrl
    ) {}

    /**
     * Resilient multi-tier hospital & clinic discovery:
     * 1. Google Places Nearby Search (if API key present)
     * 2. Photon Geocoder API by Komoot (Free, fast <400ms, global OSM index, no key needed)
     * 3. Multi-mirror OpenStreetMap Overpass (with automatic mirror fallback)
     * 4. OpenStreetMap Nominatim Fallback
     * 5. Database safety net (closest active hospitals)
     */
    public List<ExternalHospital> findNearbyHospitalsFromMaps(double lat, double lon, String departmentKeyword) {
        List<ExternalHospital> results = new ArrayList<>();

        // 1. Try Google Places Nearby Search API if key is present
        if (googleMapsApiKey != null && !googleMapsApiKey.isBlank() && !googleMapsApiKey.contains("mock")) {
            try {
                results = searchViaGooglePlaces(lat, lon, departmentKeyword);
                if (!results.isEmpty()) {
                    logger.info("Found {} hospitals via Google Places API", results.size());
                    return results;
                }
            } catch (Exception ge) {
                logger.warn("Google Places API search failed, falling back: {}", ge.getMessage());
            }
        }

        // 2. High-speed Photon Geocoder (Komoot - Free, no key required, ultra-fast)
        try {
            results = searchViaPhoton(lat, lon, departmentKeyword);
            if (!results.isEmpty()) {
                logger.info("Found {} hospitals via Photon Geocoder", results.size());
                return results;
            }
        } catch (Exception pe) {
            logger.warn("Photon Geocoder search failed, trying Overpass: {}", pe.getMessage());
        }

        // 3. Multi-mirror OpenStreetMap Overpass geographic API
        try {
            results = searchViaOsmOverpass(lat, lon, departmentKeyword);
            if (!results.isEmpty()) {
                logger.info("Found {} hospitals via OSM Overpass", results.size());
                return results;
            }
        } catch (Exception oe) {
            logger.warn("OSM Overpass search failed, trying Nominatim: {}", oe.getMessage());
        }

        // 4. OpenStreetMap Nominatim Fallback
        try {
            results = searchViaOsm(lat, lon, departmentKeyword);
            if (!results.isEmpty()) {
                logger.info("Found {} hospitals via Nominatim", results.size());
                return results;
            }
        } catch (Exception ne) {
            logger.warn("OSM Nominatim search failed, trying DB fallback: {}", ne.getMessage());
        }

        // 5. Ultimate Fallback: Registered Database Hospitals sorted by proximity
        try {
            results = searchViaDatabaseFallback(lat, lon);
            if (!results.isEmpty()) {
                logger.info("Found {} hospitals via local database fallback", results.size());
                return results;
            }
        } catch (Exception de) {
            logger.warn("Database fallback search failed: {}", de.getMessage());
        }

        return results;
    }

    private List<ExternalHospital> searchViaPhoton(double lat, double lon, String departmentKeyword) {
        List<ExternalHospital> list = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        String[] tags = {"amenity:hospital", "amenity:clinic"};
        for (String osmTag : tags) {
            try {
                String term = "hospital";
                if ("amenity:clinic".equals(osmTag)) term = "clinic";
                if (departmentKeyword != null && !departmentKeyword.isBlank() && !departmentKeyword.equalsIgnoreCase("General Medicine")) {
                    term = departmentKeyword;
                }
                String encodedTerm = URLEncoder.encode(term, StandardCharsets.UTF_8);
                String url = String.format(java.util.Locale.US,
                        "https://photon.komoot.io/api/?q=%s&lat=%f&lon=%f&osm_tag=%s&limit=8",
                        encodedTerm, lat, lon, osmTag
                );

                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("User-Agent", "MediAssistHealthDesk/2.0 (contact@mediassist.com)")
                        .timeout(Duration.ofSeconds(5))
                        .GET()
                        .build();

                HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() == 200) {
                    JsonNode root = objectMapper.readTree(resp.body());
                    JsonNode features = root.path("features");
                    if (features.isArray()) {
                        for (JsonNode f : features) {
                            JsonNode props = f.path("properties");
                            String rawName = props.path("name").asText("");
                            if (rawName.isBlank()) continue;

                            String lower = rawName.toLowerCase();
                            // Filter non-medical noise
                            if (lower.contains("bus stop") || lower.contains("playground") || lower.contains("ground")
                                    || lower.contains("pharmacy") || lower.contains("chemist") || lower.contains("shop")) {
                                continue;
                            }

                            JsonNode coords = f.path("geometry").path("coordinates");
                            if (!coords.isArray() || coords.size() < 2) continue;
                            double pLon = coords.get(0).asDouble();
                            double pLat = coords.get(1).asDouble();

                            String name = cleanHospitalName(rawName);
                            String norm = name.toLowerCase();
                            if (seen.contains(norm)) continue;
                            seen.add(norm);

                            double dist = calculateDistanceKm(lat, lon, pLat, pLon);
                            if (dist > 35.0) continue;

                            String street = props.path("street").asText("");
                            String city = props.path("city").asText(props.path("district").asText(props.path("state").asText("")));
                            String address = (!street.isBlank() && !city.isBlank()) ? (street + ", " + city) : (!city.isBlank() ? city : street);

                            String mapsUrl = String.format(java.util.Locale.US, "https://www.google.com/maps/dir/?api=1&destination=%.6f,%.6f", pLat, pLon);
                            list.add(new ExternalHospital(name, address, dist, mapsUrl));
                        }
                    }
                }
            } catch (Exception pe) {
                logger.warn("Photon search for '{}' failed: {}", osmTag, pe.getMessage());
            }
        }
        list.sort(Comparator.comparingDouble(ExternalHospital::distanceKm));
        return list.stream().limit(6).toList();
    }

    private List<ExternalHospital> searchViaGooglePlaces(double lat, double lon, String departmentKeyword) throws Exception {
        String keyword = (departmentKeyword != null && !departmentKeyword.isBlank() && !departmentKeyword.equalsIgnoreCase("General Medicine"))
                ? departmentKeyword + " hospital" : "hospital";
        String encodedKeyword = URLEncoder.encode(keyword, StandardCharsets.UTF_8);

        String url = String.format(java.util.Locale.US,
                "https://maps.googleapis.com/maps/api/place/nearbysearch/json?location=%f,%f&radius=15000&type=hospital&keyword=%s&key=%s",
                lat, lon, encodedKeyword, googleMapsApiKey.trim()
        );

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(6))
                .GET()
                .build();

        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        JsonNode root = objectMapper.readTree(resp.body());

        List<ExternalHospital> list = new ArrayList<>();
        JsonNode resultsNode = root.path("results");
        if (resultsNode.isArray()) {
            for (JsonNode place : resultsNode) {
                String rawName = place.path("name").asText("Hospital");
                String name = cleanHospitalName(rawName);
                String address = place.path("vicinity").asText(place.path("formatted_address").asText(""));
                double pLat = place.path("geometry").path("location").path("lat").asDouble(lat);
                double pLon = place.path("geometry").path("location").path("lng").asDouble(lon);
                double dist = calculateDistanceKm(lat, lon, pLat, pLon);

                String mapsUrl = String.format(java.util.Locale.US, "https://www.google.com/maps/dir/?api=1&destination=%.6f,%.6f", pLat, pLon);
                list.add(new ExternalHospital(name, address, dist, mapsUrl));
            }
        }
        // Strict Ascending Order (lowest distance first)
        list.sort(Comparator.comparingDouble(ExternalHospital::distanceKm));
        return list.stream().limit(5).toList();
    }

    private List<ExternalHospital> searchViaOsmOverpass(double lat, double lon, String departmentKeyword) throws Exception {
        String[] mirrors = {
                "https://overpass-api.de/api/interpreter",
                "https://overpass.kumi.systems/api/interpreter"
        };

        String query = String.format(java.util.Locale.US,
                "[out:json][timeout:6];" +
                "(" +
                "  nwr[\"amenity\"~\"hospital|clinic\"](around:15000, %f, %f);" +
                "  nwr[\"healthcare\"~\"hospital|clinic\"](around:15000, %f, %f);" +
                ");" +
                "out center 30;",
                lat, lon, lat, lon
        );

        String formBody = "data=" + URLEncoder.encode(query, StandardCharsets.UTF_8);

        for (String mirror : mirrors) {
            try {
                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(mirror))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .header("User-Agent", "MediAssistHealthDesk/2.0 (contact@mediassist.com)")
                        .timeout(Duration.ofSeconds(6))
                        .POST(HttpRequest.BodyPublishers.ofString(formBody))
                        .build();

                HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() != 200) continue;

                JsonNode root = objectMapper.readTree(resp.body());
                List<ExternalHospital> list = new ArrayList<>();
                Set<String> seen = new HashSet<>();
                JsonNode elements = root.path("elements");
                if (elements.isArray() && elements.size() > 0) {
                    for (JsonNode el : elements) {
                        JsonNode tags = el.path("tags");
                        String rawName = tags.has("name") ? tags.path("name").asText() : tags.path("name:en").asText("");
                        if (rawName == null || rawName.isBlank()) continue;

                        double pLat = el.has("lat") ? el.path("lat").asDouble() : el.path("center").path("lat").asDouble(lat);
                        double pLon = el.has("lon") ? el.path("lon").asDouble() : el.path("center").path("lon").asDouble(lon);

                        String name = cleanHospitalName(rawName);
                        String norm = name.toLowerCase();
                        if (seen.contains(norm)) continue;
                        seen.add(norm);

                        double dist = calculateDistanceKm(lat, lon, pLat, pLon);
                        if (dist > 35.0) continue;

                        String addr = tags.path("addr:street").asText("");
                        if (addr.isBlank()) addr = tags.path("addr:suburb").asText(tags.path("addr:city").asText(""));

                        String mapsUrl = String.format(java.util.Locale.US, "https://www.google.com/maps/dir/?api=1&destination=%.6f,%.6f", pLat, pLon);
                        list.add(new ExternalHospital(name, addr, dist, mapsUrl));
                    }
                    if (!list.isEmpty()) {
                        list.sort(Comparator.comparingDouble(ExternalHospital::distanceKm));
                        return list.stream().limit(6).toList();
                    }
                }
            } catch (Exception me) {
                logger.warn("Overpass mirror '{}' failed: {}", mirror, me.getMessage());
            }
        }
        return List.of();
    }

    private List<ExternalHospital> searchViaOsm(double lat, double lon, String departmentKeyword) throws Exception {
        double delta = 0.16; // approx 16-18 km bounding box around user
        List<ExternalHospital> list = new ArrayList<>();
        Set<String> seen = new HashSet<>();

        String[] terms = {"clinic", "hospital"};
        for (String term : terms) {
            try {
                String encodedQ = URLEncoder.encode(term, StandardCharsets.UTF_8);
                String url = String.format(java.util.Locale.US,
                        "https://nominatim.openstreetmap.org/search?format=json&q=%s&bounded=1&viewbox=%f,%f,%f,%f&limit=15",
                        encodedQ, lon - delta, lat + delta, lon + delta, lat - delta
                );

                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("User-Agent", "MediAssistHealthDesk/2.0 (contact@mediassist.com)")
                        .timeout(Duration.ofSeconds(4))
                        .GET()
                        .build();

                HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
                JsonNode root = objectMapper.readTree(resp.body());

                if (root.isArray()) {
                    for (JsonNode place : root) {
                        String rawName = place.path("display_name").asText("");
                        if (rawName.isBlank()) continue;
                        String lower = rawName.toLowerCase();

                        if (lower.contains("ground") || lower.contains("playground") || lower.contains("bus stop")
                                || lower.contains("pharmacy") || lower.contains("chemist") || lower.contains("shop")) {
                            continue;
                        }

                        String name = cleanHospitalName(rawName);
                        String norm = name.toLowerCase();
                        if (seen.contains(norm)) continue;
                        seen.add(norm);

                        double pLat = place.path("lat").asDouble(lat);
                        double pLon = place.path("lon").asDouble(lon);
                        double dist = calculateDistanceKm(lat, lon, pLat, pLon);
                        if (dist > 35.0) continue;

                        String[] parts = rawName.split(",");
                        String address = parts.length > 1 ? (parts[1].trim() + (parts.length > 2 ? ", " + parts[2].trim() : "")) : "";

                        String mapsUrl = String.format(java.util.Locale.US, "https://www.google.com/maps/dir/?api=1&destination=%.6f,%.6f", pLat, pLon);
                        list.add(new ExternalHospital(name, address, dist, mapsUrl));
                    }
                }
            } catch (Exception e) {
                logger.warn("OSM Nominatim query for '{}' failed: {}", term, e.getMessage());
            }
        }

        list.sort(Comparator.comparingDouble(ExternalHospital::distanceKm));
        return list.stream().limit(6).toList();
    }

    private List<ExternalHospital> searchViaDatabaseFallback(double lat, double lon) {
        List<Hospital> all = hospitalRepository.findByActiveTrue();
        List<ExternalHospital> list = new ArrayList<>();
        for (Hospital h : all) {
            if (h.getLatitude() == 0.0 && h.getLongitude() == 0.0) continue;
            double dist = calculateDistanceKm(lat, lon, h.getLatitude(), h.getLongitude());
            String mapsUrl = String.format(java.util.Locale.US, "https://www.google.com/maps/dir/?api=1&destination=%.6f,%.6f",
                    h.getLatitude(), h.getLongitude());
            list.add(new ExternalHospital(h.getName(), h.getAddress(), dist, mapsUrl));
        }
        list.sort(Comparator.comparingDouble(ExternalHospital::distanceKm));
        return list.stream().limit(5).toList();
    }

    private String cleanHospitalName(String rawName) {
        if (rawName == null || rawName.isBlank()) return "Hospital";
        String n = rawName.trim();
        String lower = n.toLowerCase();
        if (lower.contains("kalinga inst") || lower.contains("pradyumna bal") || lower.contains("kims")) {
            return "KIMS Hospital (Kalinga Institute of Medical Sciences)";
        }
        if (lower.contains("lv prasad") || lower.contains("prasad eye")) {
            return "LV Prasad Eye Institute";
        }
        if (lower.contains("apollo")) {
            if (lower.contains("bhubaneswar")) return "Apollo Hospitals Bhubaneswar";
            return "Apollo Hospitals";
        }
        if (lower.contains("aiims")) {
            if (lower.contains("bhubaneswar")) return "AIIMS Bhubaneswar";
            return "AIIMS Hospital";
        }
        if (lower.contains("manipal")) {
            return "Manipal Hospital";
        }
        if (lower.contains("fortis")) {
            return "Fortis Hospital";
        }
        if (lower.contains("max super") || lower.contains("max healthcare")) {
            return "Max Super Speciality Hospital";
        }
        if (lower.contains("lilavati")) {
            return "Lilavati Hospital & Research Centre";
        }
        if (lower.contains("yashoda")) {
            return "Yashoda Hospitals";
        }
        String[] parts = n.split(",");
        if (parts.length > 0 && parts[0].trim().length() > 3) {
            return parts[0].trim();
        }
        return n;
    }

    public String generateMasterGoogleMapsUrl(double lat, double lon, String departmentKeyword) {
        String query = (departmentKeyword != null && !departmentKeyword.isBlank() && !departmentKeyword.equalsIgnoreCase("General Medicine"))
                ? departmentKeyword + "+hospitals" : "hospitals";
        return String.format(java.util.Locale.US, "https://www.google.com/maps/search/%s/@%f,%f,14z", query, lat, lon);
    }

    public String generateMasterGoogleMapsClinicsUrl(double lat, double lon) {
        return String.format(java.util.Locale.US, "https://www.google.com/maps/search/clinics/@%f,%f,14z", lat, lon);
    }

    private double calculateDistanceKm(double lat1, double lon1, double lat2, double lon2) {
        final int R = 6371; // Earth radius in km
        double latDistance = Math.toRadians(lat2 - lat1);
        double lonDistance = Math.toRadians(lon2 - lon1);
        double a = Math.sin(latDistance / 2) * Math.sin(latDistance / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(lonDistance / 2) * Math.sin(lonDistance / 2);
        double c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
        return Math.round(R * c * 10.0) / 10.0;
    }
}
