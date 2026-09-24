# WhatsApp-Based AI Medical Assistant & Hospital Portal (MediAssist)

An end-to-end intelligent healthcare platform where **patients interact 100% via WhatsApp**, while **hospital staff manage operations through dedicated web portals**.

---

## 📁 Repository Structure

```text
MediAssist/
├── 🌐 frontend/                 # Client-Facing Web Portals & UI
│   ├── index.html              # Main Landing Page
│   ├── login.html              # Role-Based Login (Admin, Manager, Receptionist)
│   ├── admin-portal.html       # Super Admin Approval & Management Portal
│   ├── manager-portal.html     # Hospital Manager Dashboard & QR Check-In
│   ├── receptionist.html       # Reception Desk Webcam QR Check-In Scanner
│   ├── doctor-roster.html      # Live Doctor Availability & OPD Hours
│   ├── token-display.html      # Waiting Room TV Digital Queue Display
│   ├── whatsapp-simulator.html # Interactive In-Browser WhatsApp Simulator
│   ├── apply.html              # Hospital Registration & Onboarding Form
│   ├── privacy.html            # Meta Compliance Privacy Policy
│   ├── css/                    # Responsive Styling (style.css)
│   ├── js/                     # Shared Frontend Utilities (app.js)
│   └── README.md               # Frontend Documentation
│
├── ⚙️ backend/                  # Spring Boot 3 & Java 21 Microservice
│   ├── src/main/java/          # Controllers, Services, Models, Repositories
│   ├── src/main/resources/     # application.yml & Email Templates
│   ├── pom.xml                 # Backend Maven Dependencies
│   ├── Dockerfile              # Backend Container Configuration
│   └── README.md               # Backend API Documentation
│
├── Dockerfile                  # Multi-Stage Production Build (Render / Cloud)
├── pom.xml                     # Root Aggregator POM
├── docker-compose.yml          # Container Orchestration
└── SETUP_GUIDE.md              # Detailed Setup & Configuration Guide
```

---

## 🌟 Key Features

* **Patient-Side (Pure WhatsApp Bot)**:
  * 📍 **Location-Based Doctor Discovery**: Share GPS location to discover nearest hospitals and available specialists.
  * 📄 **Instant PDF Appointment Slips**: Generates official hospital slips with sequential queue tokens and QR check-in codes.
  * 💊 **Multimodal Prescription OCR & Alarms**: Gemini Vision AI automatically reads doctor prescriptions and schedules daily alarms with interactive `[✅ Taken]` and `[⏰ Snooze 15m]` buttons.
  * 🩺 **AI Medical Symptom Triage**: Instant clinical guidance in vernacular languages with emergency SOS watchdog.
  * 🗄️ **Digital Report Wardrobe**: Safely archives prescription images and lab results for quick patient retrieval.

* **Hospital-Side (Web Portals)**:
  * 📊 **Super Admin Portal (`/admin-portal.html`)**: Review hospital registrations, approve accounts, and auto-dispatch credentials via email.
  * 👨‍⚕️ **Hospital Manager Portal (`/manager-portal.html`)**: Manage doctor rosters, consultation fees, token limits, and live queues.
  * 📷 **Reception Desk (`/receptionist.html`)**: Fast camera-based QR code check-in scanner.
  * 📺 **Clinic TV Display (`/token-display.html`)**: Full-screen live digital queue calling display.
  * 📱 **WhatsApp Simulator (`/whatsapp-simulator.html`)**: Test all bot workflows directly in the browser.

---

## 🚀 Quick Start

```powershell
# Run backend locally (serves frontend portals automatically):
.\mvnw.cmd spring-boot:run -f backend/pom.xml

# Package full application:
.\mvnw.cmd clean package -DskipTests
```
