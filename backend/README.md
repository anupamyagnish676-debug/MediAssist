# MediAssist — Backend Service (Spring Boot 3 / Java 21)

This directory contains the core backend microservice for **MediAssist**, built on Spring Boot 3 with Java 21.

## 🚀 Key Modules & Architecture

- **`config/`**: Security configuration (JWT + Cookie session filter), CORS, Database auto-detection (PostgreSQL / H2).
- **`controller/`**: REST controllers:
  - `WhatsAppWebhookController`: Handles Meta Cloud WhatsApp webhooks, interactive buttons, document/prescription uploads, and location pins.
  - `AuthController`: User login, token refresh, and role validation.
  - `SuperAdminApiController`: Hospital approval queue, credential dispatch via Brevo/SMTP, and platform auditing.
  - `HospitalManagerApiController`: Doctor rosters, daily token schedules, live queues, and QR check-in verification.
  - `ReceptionistApiController`: QR code check-in scanner and patient queue queueing.
- **`model/`**: JPA Entities (`Hospital`, `Doctor`, `Appointment`, `MedicationReminder`, `MedicalDocument`, `User`, `HospitalApplication`).
- **`service/`**:
  - `GeminiAiService`: Multimodal Gemini VLM for prescription reading and clinical symptom triage with auto-failover.
  - `MedicationReminderService`: Background cron scheduler for daily medication alarms.
  - `WhatsAppClientService`: Meta Graph API integration with retry and document delivery.
  - `AppointmentSlipPdfService`: PDFBox appointment slip generator with embedded QR tokens.
  - `EmailService`: Automated email credential delivery via Brevo HTTPS API.
  - `LocationService`: Geo-coordinate distance calculation for nearest hospital matching.

## 🛠️ Local Development & Running

```bash
# Run backend locally with embedded H2 database
./mvnw spring-boot:run

# Run package
./mvnw clean package -DskipTests
```
