package com.med.assistant.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

@Service
public class ExternalHospitalService {

    private static final Logger logger = LoggerFactory.getLogger(ExternalHospitalService.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(6))
            .build();

    @Value("${GOOGLE_MAPS_API_KEY:${app.google.maps.api-key:}}")
    private String googleMapsApiKey;

    public record ExternalHospital(
            String name,
            String address,
            double distanceKm,
            String mapsUrl
    ) {}

    /**
     * Finds real nearby hospitals from Google Maps / Places API or live OpenStreetMap Overpass/Nominatim.
     */
    public List<ExternalHospital> findNearbyHospitalsFromMaps(double lat, double lon, String departmentKeyword) {
        List<ExternalHospital> results = new ArrayList<>();

        // 1. Try Google Places Nearby Search API if key is present
        if (googleMapsApiKey != null && !googleMapsApiKey.isBlank() && !googleMapsApiKey.contains("mock")) {
            try {
                results = searchViaGooglePlaces(lat, lon, departmentKeyword);
                if (!results.isEmpty()) {
                    return results;
                }
            } catch (Exception ge) {
                logger.warn("Google Places API search failed, falling back: {}", ge.getMessage());
            }
        }

        // 2. High-precision OpenStreetMap Overpass geographic API (finds KIMS, Apollo, etc. with exact coordinates)
        try {
            results = searchViaOsmOverpass(lat, lon, departmentKeyword);
            if (!results.isEmpty()) {
                return results;
            }
        } catch (Exception oe) {
            logger.warn("OSM Overpass search failed, trying Nominatim: {}", oe.getMessage());
        }

        // 3. OpenStreetMap Nominatim Fallback
        try {
            results = searchViaOsm(lat, lon, departmentKeyword);
            if (!results.isEmpty()) {
                return results;
            }
        } catch (Exception ne) {
            logger.warn("OSM Nominatim search failed: {}", ne.getMessage());
        }

        return results;
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

                String mapsUrl = String.format(java.util.Locale.US, "https://www.google.com/maps/search/?api=1&query=%f,%f", pLat, pLon);
                list.add(new ExternalHospital(name, address, dist, mapsUrl));
            }
        }
        // Strict Ascending Order (lowest distance first)
        list.sort(Comparator.comparingDouble(ExternalHospital::distanceKm));
        return list.stream().limit(5).toList();
    }

    private List<ExternalHospital> searchViaOsmOverpass(double lat, double lon, String departmentKeyword) throws Exception {
        String query = String.format(java.util.Locale.US,
                "[out:json][timeout:8];" +
                "(" +
                "  node[\"amenity\"=\"hospital\"](around:15000, %f, %f);" +
                "  way[\"amenity\"=\"hospital\"](around:15000, %f, %f);" +
                "  relation[\"amenity\"=\"hospital\"](around:15000, %f, %f);" +
                "  node[\"healthcare\"=\"hospital\"](around:15000, %f, %f);" +
                "  way[\"healthcare\"=\"hospital\"](around:15000, %f, %f);" +
                "  node[\"amenity\"=\"clinic\"](around:6000, %f, %f);" +
                "  way[\"amenity\"=\"clinic\"](around:6000, %f, %f);" +
                ");" +
                "out center 35;",
                lat, lon, lat, lon, lat, lon, lat, lon, lat, lon, lat, lon, lat, lon
        );

        String formBody = "data=" + URLEncoder.encode(query, StandardCharsets.UTF_8);

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create("https://overpass-api.de/api/interpreter"))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .header("User-Agent", "MediAssistHealthDesk/1.0 (contact@mediassist.com)")
                .timeout(Duration.ofSeconds(8))
                .POST(HttpRequest.BodyPublishers.ofString(formBody))
                .build();

        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        JsonNode root = objectMapper.readTree(resp.body());

        List<ExternalHospital> list = new ArrayList<>();
        java.util.Set<String> seen = new java.util.HashSet<>();
        JsonNode elements = root.path("elements");
        if (elements.isArray()) {
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
                String addr = tags.path("addr:street").asText("");
                if (addr.isBlank()) addr = tags.path("addr:suburb").asText(tags.path("addr:city").asText(""));

                String mapsUrl = String.format(java.util.Locale.US, "https://www.google.com/maps/search/?api=1&query=%f,%f", pLat, pLon);
                list.add(new ExternalHospital(name, addr, dist, mapsUrl));
            }
        }

        // Strict Ascending Order (lowest distance first)
        list.sort(Comparator.comparingDouble(ExternalHospital::distanceKm));
        return list.stream().limit(5).toList();
    }

    private List<ExternalHospital> searchViaOsm(double lat, double lon, String departmentKeyword) throws Exception {
        double delta = 0.18; // approx 20 km bounding box
        String query = "hospital";
        if (departmentKeyword != null && !departmentKeyword.isBlank() && !departmentKeyword.equalsIgnoreCase("General Medicine")) {
            query = departmentKeyword + " hospital";
        }
        String encodedQ = URLEncoder.encode(query, StandardCharsets.UTF_8);

        String url = String.format(java.util.Locale.US,
                "https://nominatim.openstreetmap.org/search?format=json&q=%s&bounded=1&viewbox=%f,%f,%f,%f&limit=15",
                encodedQ, lon - delta, lat + delta, lon + delta, lat - delta
        );

        HttpRequest req = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("User-Agent", "MediAssistHealthDesk/1.0 (contact@mediassist.com)")
                .timeout(Duration.ofSeconds(6))
                .GET()
                .build();

        HttpResponse<String> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofString());
        JsonNode root = objectMapper.readTree(resp.body());

        List<ExternalHospital> list = new ArrayList<>();
        if (root.isArray()) {
            for (JsonNode place : root) {
                String rawName = place.path("display_name").asText("Hospital");
                String name = cleanHospitalName(rawName);
                String[] parts = rawName.split(",");
                String address = parts.length > 1 ? (parts[1].trim() + (parts.length > 2 ? ", " + parts[2].trim() : "")) : "";

                double pLat = place.path("lat").asDouble(lat);
                double pLon = place.path("lon").asDouble(lon);
                double dist = calculateDistanceKm(lat, lon, pLat, pLon);

                String mapsUrl = String.format(java.util.Locale.US, "https://www.google.com/maps/search/?api=1&query=%f,%f", pLat, pLon);
                list.add(new ExternalHospital(name, address, dist, mapsUrl));
            }
        }

        // If specific keyword query returned nothing, fall back to general hospital search
        if (list.isEmpty() && !"hospital".equals(query)) {
            return searchViaOsm(lat, lon, null);
        }

        // Strict Ascending Order (lowest distance first)
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
            return "Apollo Hospitals";
        }
        if (lower.contains("aiims")) {
            return "AIIMS Hospital";
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
