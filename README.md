# WhatsApp-Based AI Medical Assistant & Hospital Portal

An end-to-end intelligent medical assistant system where **patients interact 100% via WhatsApp**, while **hospitals manage doctor rosters, patient tokens, and QR check-ins through a modern HTML5/CSS/JS web portal**.

## Key Features

* **Patient-Side (Pure WhatsApp)**:
  * 📍 **Location-Based Hospital Discovery**: Share GPS location to see nearby hospitals & available doctors.
  * 📄 **Official PDF Slips**: Generates hospital-branded appointment slips with sequential queue tokens and QR codes.
  * 🩺 **AI Medical Triage & Emergency Watchdog**: Instant detection of acute red-flag symptoms with automated emergency hotline escalation.
  * 🗄️ **Report Wardrobe**: Upload messy prescriptions and lab tests (PDF/photos) for AI extraction and instant chat retrieval.
  * 💊 **1-Click Medication Adherence**: Scheduled alerts with native `[Taken]` and `[Snooze 15m]` buttons.
  * 🎙️ **Voice Note & Vernacular Support**: Speaks and responds in the patient's language.

* **Hospital-Side (HTML5 + CSS + JavaScript)**:
  * 📊 **Operations Hub (`/index.html`)**: Real-time stats of active bookings, doctors, and check-in rates.
  * 👨‍⚕️ **Doctor Roster (`/doctor-roster.html`)**: Doctors toggle availability (`Available Today` / `On Leave`) and set token capacity.
  * 📷 **Receptionist QR Desk (`/receptionist.html`)**: Laptop webcam scanner for patient check-in.
  * 📺 **Clinic TV Display (`/token-display.html`)**: Fullscreen token calling screen for waiting rooms.

## Quick Start

1. Start the application locally:
   ```powershell
   .\mvnw.cmd spring-boot:run
   ```
2. Open the Hospital Portal at: [http://localhost:8085/index.html](http://localhost:8085/index.html)
3. For step-by-step external account setup (Meta WhatsApp API, Gemini API, ngrok), read **[SETUP_GUIDE.md](./SETUP_GUIDE.md)**.
