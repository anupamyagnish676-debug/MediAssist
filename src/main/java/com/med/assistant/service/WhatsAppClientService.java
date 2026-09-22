package com.med.assistant.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;

@Service
public class WhatsAppClientService {

    private static final Logger logger = LoggerFactory.getLogger(WhatsAppClientService.class);

    @Value("${app.whatsapp.access-token:}")
    private String accessToken;

    @Value("${app.whatsapp.phone-number-id:}")
    private String phoneNumberId;

    @Value("${app.whatsapp.api-url:https://graph.facebook.com/v20.0}")
    private String apiUrl;

    private final RestTemplate restTemplate = new RestTemplate();

    public record ButtonOption(String id, String title) {}

    /**
     * Send standard plain text WhatsApp message.
     */
    public void sendTextMessage(String toPhone, String text) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("messaging_product", "whatsapp");
        payload.put("to", toPhone);
        payload.put("type", "text");
        payload.put("text", Map.of("body", text));

        postToWhatsApp(payload);
    }

    /**
     * Send Quick-Reply Interactive Buttons (e.g. [Taken] [Snooze], or Doctor Selection).
     */
    public void sendInteractiveButtons(String toPhone, String bodyText, List<ButtonOption> buttons) {
        List<Map<String, Object>> buttonList = new ArrayList<>();
        for (ButtonOption btn : buttons) {
            buttonList.add(Map.of(
                    "type", "reply",
                    "reply", Map.of("id", btn.id(), "title", btn.title())
            ));
        }

        Map<String, Object> interactive = Map.of(
                "type", "button",
                "body", Map.of("text", bodyText),
                "action", Map.of("buttons", buttonList)
        );

        Map<String, Object> payload = Map.of(
                "messaging_product", "whatsapp",
                "to", toPhone,
                "type", "interactive",
                "interactive", interactive
        );

        postToWhatsApp(payload);
    }

    /**
     * Send Interactive List Message (e.g. Nearby Hospitals or Doctors).
     */
    public void sendInteractiveList(String toPhone, String title, String bodyText, String buttonText,
                                    List<Map<String, String>> rows) {
        Map<String, Object> section = Map.of(
                "title", title,
                "rows", rows
        );

        Map<String, Object> interactive = Map.of(
                "type", "list",
                "body", Map.of("text", bodyText),
                "action", Map.of(
                        "button", buttonText,
                        "sections", List.of(section)
                )
        );

        Map<String, Object> payload = Map.of(
                "messaging_product", "whatsapp",
                "to", toPhone,
                "type", "interactive",
                "interactive", interactive
        );

        postToWhatsApp(payload);
    }

    /**
     * Send Document (e.g., Appointment PDF slip, past lab report).
     */
    public void sendDocumentMessage(String toPhone, String documentUrl, String caption, String filename) {
        Map<String, Object> doc = Map.of(
                "link", documentUrl,
                "caption", caption,
                "filename", filename
        );

        Map<String, Object> payload = Map.of(
                "messaging_product", "whatsapp",
                "to", toPhone,
                "type", "document",
                "document", doc
        );

        postToWhatsApp(payload);
    }

    private void postToWhatsApp(Map<String, Object> payload) {
        if (accessToken == null || accessToken.isBlank() || accessToken.contains("SAMPLE")) {
            logger.info("[DEV / SANDBOX] WhatsApp Outbound Message to {}: {}", payload.get("to"), payload);
            return;
        }

        try {
            String url = apiUrl + "/" + phoneNumberId + "/messages";
            logger.info("Posting outbound message to Meta API: url={}, recipient={}", url, payload.get("to"));
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(accessToken.trim());

            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(payload, headers);
            ResponseEntity<String> response = restTemplate.postForEntity(url, entity, String.class);
            logger.info("WhatsApp message sent successfully! Meta Response: {}", response.getBody());
        } catch (org.springframework.web.client.HttpStatusCodeException e) {
            logger.error("Meta API HTTP Error {}: {}", e.getStatusCode(), e.getResponseBodyAsString());
        } catch (Exception e) {
            logger.error("Failed to send WhatsApp message: {}", e.getMessage(), e);
        }
    }
}
