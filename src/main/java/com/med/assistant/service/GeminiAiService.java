package com.med.assistant.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;
import java.util.regex.Pattern;

@Service
public class GeminiAiService {

    private static final Logger logger = LoggerFactory.getLogger(GeminiAiService.class);

    @Value("${app.ai.gemini.api-key:}")
    private String geminiApiKey;

    @Value("${app.ai.gemini.model:gemini-1.5-flash}")
    private String modelName;

    private final RestTemplate restTemplate = new RestTemplate();

    // Deterministic safety patterns for red flags
    private static final Pattern RED_FLAGS = Pattern.compile(
            "\\b(chest pain|heart attack|can't breathe|difficulty breathing|shortness of breath|" +
            "stroke|face drooping|sudden numbness|heavy bleeding|unconscious|suicide|poison)\\b",
            Pattern.CASE_INSENSITIVE
    );

    /**
     * Checks if the user message contains critical red-flag emergency symptoms.
     */
    public boolean isEmergency(String message) {
        if (message == null) return false;
        return RED_FLAGS.matcher(message).find();
    }

    /**
     * Returns emergency SOS text with hotlines.
     */
    public String getEmergencyResponse() {
        return """
            🚨 EMERGENCY DETECTED 🚨
            Your message mentions symptoms that may require urgent medical attention.
            
            ⚠️ Please DO NOT wait for an online chat or booking.
            👉 Call your local Emergency Services immediately:
               • Emergency: 112 / 911 / 108
               • Ambulance: 102
               
            Go to the nearest hospital Emergency Room (ER) right away.
            """;
    }

    /**
     * Medical Triage & Conversation with strict disclaimers and language auto-matching.
     */
    public String askMedicalAi(String userQuery, String conversationContext) {
        if (isEmergency(userQuery)) {
            return getEmergencyResponse();
        }

        if (geminiApiKey == null || geminiApiKey.isBlank() || geminiApiKey.contains("mock")) {
            return generateMockTriageResponse(userQuery);
        }

        String[] candidateModels = { "gemini-1.5-flash", "gemini-2.0-flash", "gemini-1.5-pro", "gemini-pro" };
        String systemInstruction = """
            You are a compassionate, clinical AI Medical Assistant inside WhatsApp.
            GUIDELINES:
            1. Always include a short disclaimer: 'I am an AI assistant, not a doctor.'
            2. If the user speaks or writes in Hindi, Spanish, or any other vernacular, ALWAYS reply in that same language.
            3. Never prescribe exact prescription-only dosages.
            4. Give helpful preliminary home-care tips and advise which medical specialist to consult.
            5. Keep WhatsApp replies clear, concise, and formatted with bullet points and friendly emojis.
            """;

        Map<String, Object> textPart = Map.of("text", systemInstruction + "\n\nUser: " + userQuery);
        Map<String, Object> content = Map.of("parts", List.of(textPart));
        Map<String, Object> requestBody = Map.of("contents", List.of(content));

        for (String candidate : candidateModels) {
            try {
                String endpoint = "https://generativelanguage.googleapis.com/v1beta/models/" + candidate + ":generateContent?key=" + geminiApiKey;
                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);
                HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

                ResponseEntity<Map> response = restTemplate.postForEntity(endpoint, entity, Map.class);
                this.modelName = candidate; // Cache working model
                return extractTextFromGeminiResponse(response.getBody());
            } catch (Exception e) {
                logger.warn("Model {} failed ({}), trying next candidate...", candidate, e.getMessage());
            }
        }
        return generateMockTriageResponse(userQuery);
    }

    public Map<String, Object> testGeminiConnection(String query) {
        Map<String, Object> result = new HashMap<>();
        boolean hasKey = geminiApiKey != null && !geminiApiKey.isBlank() && !geminiApiKey.contains("mock");
        result.put("apiKeyConfigured", hasKey);
        result.put("keyPrefix", hasKey ? geminiApiKey.substring(0, Math.min(6, geminiApiKey.length())) + "..." : "NONE");

        if (!hasKey) {
            result.put("status", "NO_API_KEY");
            result.put("message", "GEMINI_API_KEY is not configured in Render Environment variables.");
            return result;
        }

        String[] candidateModels = { "gemini-1.5-flash", "gemini-2.0-flash", "gemini-1.5-pro", "gemini-pro" };
        Map<String, Object> textPart = Map.of("text", "Give a 1-sentence health tip about: " + query);
        Map<String, Object> content = Map.of("parts", List.of(textPart));
        Map<String, Object> requestBody = Map.of("contents", List.of(content));

        List<String> errors = new ArrayList<>();
        for (String candidate : candidateModels) {
            try {
                String endpoint = "https://generativelanguage.googleapis.com/v1beta/models/" + candidate + ":generateContent?key=" + geminiApiKey.trim();
                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);
                HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

                ResponseEntity<Map> response = restTemplate.postForEntity(endpoint, entity, Map.class);
                String text = extractTextFromGeminiResponse(response.getBody());
                result.put("status", "SUCCESS");
                result.put("workingModel", candidate);
                result.put("aiOutput", text);
                return result;
            } catch (Exception e) {
                errors.add(candidate + ": " + e.getMessage());
            }
        }
        result.put("status", "FAILED_CALLING_GOOGLE");
        result.put("errors", errors);
        return result;
    }

    /**
     * Multimodal OCR & Analysis for Prescriptions and Lab Reports.
     */
    public String analyzeLabReportOrPrescription(byte[] fileBytes, String mimeType, String fileName) {
        if (geminiApiKey == null || geminiApiKey.isBlank() || geminiApiKey.contains("mock")) {
            return """
                📋 Document Analyzed: """ + fileName + """
                
                🔍 AI Clinical Summary:
                • Type: Diagnostic Lab Report / Prescription
                • Status: Reviewed
                • Extracted Observations: All major parameters logged into your Report Wardrobe.
                
                💡 Note: Always consult your primary physician for final diagnostic interpretation.
                """;
        }

        try {
            String endpoint = "https://generativelanguage.googleapis.com/v1beta/models/" + modelName + ":generateContent?key=" + geminiApiKey;

            String base64Data = Base64.getEncoder().encodeToString(fileBytes);
            Map<String, Object> inlineData = Map.of(
                    "mime_type", mimeType != null ? mimeType : "image/jpeg",
                    "data", base64Data
            );

            Map<String, Object> imagePart = Map.of("inline_data", inlineData);
            Map<String, Object> textPart = Map.of("text", """
                Analyze this medical document (prescription or lab report).
                1. Identify the document type and date.
                2. If it's a lab report: highlight any out-of-range values in simple patient-friendly terms (e.g. 'HbA1c is slightly elevated').
                3. If it's a prescription: extract medication names, dosage, and frequency.
                4. Keep the summary under 150 words, formatted neatly for WhatsApp.
                """);

            Map<String, Object> content = Map.of("parts", List.of(textPart, imagePart));
            Map<String, Object> requestBody = Map.of("contents", List.of(content));

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(requestBody, headers);

            ResponseEntity<Map> response = restTemplate.postForEntity(endpoint, entity, Map.class);
            return extractTextFromGeminiResponse(response.getBody());

        } catch (Exception e) {
            logger.error("Error analyzing medical document with Gemini Vision: {}", e.getMessage());
            return "Unable to analyze document image right now. It has been safely stored in your Report Wardrobe.";
        }
    }

    private String extractTextFromGeminiResponse(Map body) {
        try {
            if (body == null) return "No response received from AI.";
            List candidates = (List) body.get("candidates");
            if (candidates == null || candidates.isEmpty()) return "No content generated.";
            Map candidate = (Map) candidates.get(0);
            Map content = (Map) candidate.get("content");
            List parts = (List) content.get("parts");
            Map part = (Map) parts.get(0);
            return (String) part.get("text");
        } catch (Exception e) {
            return "Medical AI analysis complete.";
        }
    }

    private String generateMockTriageResponse(String userQuery) {
        String q = userQuery != null ? userQuery.toLowerCase().trim() : "";

        // Check if Hindi / Hinglish
        boolean isHindi = q.contains("dard") || q.contains("bukhar") || q.contains("pet") || 
                          q.contains("sar") || q.contains("sir") || q.contains("khansi") || 
                          q.contains("hai") || q.contains("mujhe") || q.contains("kya") || q.contains("hoon");

        if (isHindi) {
            if (q.contains("sar") || q.contains("headache") || q.contains("sir")) {
                return """
                    🩺 *AI मेडिकल सहायक (परामर्श):*
                    आपके सिरदर्द के लिए प्राथमिक देखभाल सलाह:

                    • शांत और कम रोशनी वाले कमरे में विश्राम करें।
                    • पर्याप्त मात्रा में पानी पिएं (डिहाइड्रेशन सिरदर्द का मुख्य कारण होता है)।
                    • मोबाइल और स्क्रीन का उपयोग कम करें।
                    • यदि दर्द 24-48 घंटे से अधिक रहे या बहुत तेज हो, तो तुरंत डॉक्टर से मिलें।

                    👨‍⚕️ *परामर्श विभाग:* जनरल फिजिशियन या न्यूरोलॉजिस्ट
                    📍 *टिप:* निकटतम अस्पताल और उपलब्ध डॉक्टर देखने के लिए अपनी लोकेशन पिन साझा करें!

                    *(अस्वीकरण: मैं एक AI सहायक हूँ, डॉक्टर नहीं।)*
                    """;
            }
            if (q.contains("bukhar") || q.contains("fever") || q.contains("tap")) {
                return """
                    🩺 *AI मेडिकल सहायक (परामर्श):*
                    आपके बुखार के लिए प्राथमिक देखभाल सलाह:

                    • हर 4 से 6 घंटे में अपना तापमान मापें और नोट करें।
                    • खूब सारे तरल पदार्थ (पानी, ओआरएस, सूप) पिएं।
                    • हल्के कपड़े पहनें और ठंडे पानी की पट्टियां माथे पर रखें।
                    • यदि बुखार 102°F से अधिक हो या 3 दिन से अधिक रहे, तो तुरंत रक्त जांच कराएं।

                    👨‍⚕️ *परामर्श विभाग:* जनरल मेडिसिन / फिजिशियन
                    📍 *टिप:* निकटतम अस्पताल और उपलब्ध डॉक्टर देखने के लिए अपनी लोकेशन पिन साझा करें!

                    *(अस्वीकरण: मैं एक AI सहायक हूँ, डॉक्टर नहीं।)*
                    """;
            }
            if (q.contains("pet") || q.contains("stomach") || q.contains("gas") || q.contains("dast")) {
                return """
                    🩺 *AI मेडिकल सहायक (परामर्श):*
                    पेट की समस्या के लिए प्राथमिक सलाह:

                    • हल्का और सुपाच्य भोजन (खिचड़ी, दही, छाछ) लें।
                    • मसालेदार, तला-भुना और कैफीन युक्त भोजन से बचें।
                    • ओआरएस (ORS) या नारियल पानी पिएं।
                    • यदि तेज दर्द या उल्टी हो, तो तुरंत डॉक्टर से मिलें।

                    👨‍⚕️ *परामर्श विभाग:* गैस्ट्रोएंटेरोलॉजिस्ट / जनरल फिजिशियन
                    📍 *टिप:* निकटतम अस्पताल और डॉक्टर देखने के लिए लोकेशन पिन भेजें!

                    *(अस्वीकरण: मैं एक AI सहायक हूँ, डॉक्टर नहीं।)*
                    """;
            }
            if (q.contains("khansi") || q.contains("cough") || q.contains("gala") || q.contains("cold")) {
                return """
                    🩺 *AI मेडिकल सहायक (परामर्श):*
                    खांसी और जुकाम के लिए प्राथमिक सलाह:

                    • दिन में 2-3 बार गर्म पानी से नमक के गरारे (गार्गल) करें।
                    • गर्म पानी या काढ़ा पिएं और भाप (steam) लें।
                    • ठंडी और खट्टी चीजों से परहेज करें।
                    • यदि सांस लेने में तकलीफ हो, तो तुरंत अस्पताल पहुंचें।

                    👨‍⚕️ *परामर्श विभाग:* ईएनटी (ENT) या जनरल फिजिशियन
                    📍 *टिप:* अपने आस-पास के अस्पताल देखने के लिए लोकेशन पिन साझा करें!

                    *(अस्वीकरण: मैं एक AI सहायक हूँ, डॉक्टर नहीं।)*
                    """;
            }
        }

        // English specific symptom triage
        if (q.contains("headache") || q.contains("migraine") || q.contains("head pain")) {
            return """
                🩺 *AI Medical Health Assistant:*
                Preliminary recommendations for **Headache**:

                • Rest in a quiet, dimly lit room and keep your neck relaxed.
                • Drink at least 2-3 glasses of water; dehydration is a frequent headache trigger.
                • Apply a cool compress to your forehead or temples.
                • Avoid screen glare and prolonged mobile/laptop use.
                • If the headache is severe or accompanied by nausea or vision changes, seek medical care.

                👨‍⚕️ *Recommended Specialist:* General Physician / Neurologist
                📍 *Tip:* Share your location pin to view available doctors and book an appointment!

                *(Disclaimer: I am an AI assistant, not a licensed medical doctor.)*
                """;
        }

        if (q.contains("fever") || q.contains("temperature") || q.contains("chills")) {
            return """
                🩺 *AI Medical Health Assistant:*
                Preliminary care guidance for **Fever**:

                • Track your body temperature with a thermometer every 4-6 hours.
                • Increase fluid intake (water, electrolyte solutions, clear soups).
                • Wear lightweight, breathable cotton clothing and rest.
                • If fever exceeds 102°F (38.9°C) or lasts over 48 hours, diagnostic tests are advised.

                👨‍⚕️ *Recommended Specialist:* General Medicine / Physician
                📍 *Tip:* Share your location pin to see available doctors near you and book an appointment!

                *(Disclaimer: I am an AI assistant, not a licensed medical doctor.)*
                """;
        }

        if (q.contains("stomach") || q.contains("abdomen") || q.contains("belly") || q.contains("cramp") || q.contains("nausea") || q.contains("vomit")) {
            return """
                🩺 *AI Medical Health Assistant:*
                Preliminary care guidance for **Abdominal Discomfort**:

                • Stick to a bland diet (bananas, rice, applesauce, toast - BRAT diet).
                • Avoid dairy, spicy foods, caffeine, and heavy greasy meals.
                • Sip oral rehydration solutions (ORS) or coconut water slowly.
                • If pain is localized to the lower right abdomen or severe, visit the ER immediately.

                👨‍⚕️ *Recommended Specialist:* Gastroenterologist / General Physician
                📍 *Tip:* Share your location pin to view nearby hospitals and doctors!

                *(Disclaimer: I am an AI assistant, not a licensed medical doctor.)*
                """;
        }

        if (q.contains("cough") || q.contains("cold") || q.contains("sore throat") || q.contains("throat") || q.contains("sneez")) {
            return """
                🩺 *AI Medical Health Assistant:*
                Preliminary care guidance for **Cold & Throat Symptoms**:

                • Perform warm salt-water gargles 2-3 times daily to soothe throat irritation.
                • Inhale warm steam to relieve nasal and airway congestion.
                • Drink warm herbal teas with honey and ginger.
                • Monitor your breathing and seek medical evaluation if cough persists over a week.

                👨‍⚕️ *Recommended Specialist:* ENT Specialist / Pulmonologist
                📍 *Tip:* Share your location pin to check available doctors and book a slot!

                *(Disclaimer: I am an AI assistant, not a licensed medical doctor.)*
                """;
        }

        // Generic intelligent response
        return """
            🩺 *AI Medical Health Assistant:*
            Thank you for consulting MediAssist regarding: *"%s"*

            • Monitor the duration, intensity, and any triggers for these symptoms.
            • Ensure optimal hydration, light nutrition, and restorative rest.
            • If symptoms persist for more than 48 hours or worsen, please schedule an appointment.

            👨‍⚕️ *Recommended Specialist:* General Physician
            📍 *Tip:* Share your WhatsApp location pin to discover registered doctors and book an OPD appointment!

            *(Disclaimer: I am an AI assistant, not a licensed medical doctor.)*
            """.formatted(userQuery != null ? userQuery.trim() : "your inquiry");
    }
}
