# Step-by-Step Setup Guide: What You Need To Do

This guide explains **every external account, API key, and step** you need to take to connect this project to real WhatsApp and launch it.

---

## Checklist of Things You Have to Do

1. [ ] **Run the project locally** with the included Maven Wrapper.
2. [ ] **Get a free Google Gemini API Key** (for medical AI and prescription OCR).
3. [ ] **Setup Meta WhatsApp Cloud API** on Facebook Developers.
4. [ ] **Expose your local server to Meta** using `ngrok` (free HTTPS tunnel).
5. [ ] **Test the patient WhatsApp journey & hospital portal**.

---

## Step 1: Run the Application Locally (Zero Configuration)

You already have Java 23 installed on your machine. The project includes `mvnw.cmd` which automatically configures Maven.

1. Open your terminal in the project directory:
   ```powershell
   cd C:\Users\KIIT0001\Documents\antigravity\magical-planck\whatsapp-medical-assistant
   ```

2. Run the application:
   ```powershell
   .\mvnw.cmd spring-boot:run
   ```
   *(On the first run, the wrapper will download Maven and project dependencies. Spring Boot will start on `http://localhost:8085`)*

3. **Verify the Hospital Portal**:
   Open your browser to:
   * **Dashboard Overview**: `http://localhost:8085/index.html`
   * **Doctor Availability Roster**: `http://localhost:8085/doctor-roster.html`
   * **Receptionist QR Scanner**: `http://localhost:8085/receptionist.html`
   * **Clinic Waiting Room TV Screen**: `http://localhost:8085/token-display.html`

---

## Step 2: Get your Google Gemini API Key

1. Visit [Google AI Studio](https://aistudio.google.com/).
2. Sign in with your Google account and click **Get API Key**.
3. Create a key in a new or existing Google Cloud project.
4. Set it as an environment variable in Windows:
   ```powershell
   $env:GEMINI_API_KEY="AIzaSyYourKeyHere..."
   ```
   *Or paste it directly into `src/main/resources/application.yml` under `app.ai.gemini.api-key`.*

---

## Step 3: Setup Meta WhatsApp Business Cloud API

Meta provides a free developer sandbox with test phone numbers and 1,000 free conversations per month.

### 1. Create your Meta Developer App
1. Go to [developers.facebook.com](https://developers.facebook.com/) and log in with your Facebook account.
2. Click **My Apps** -> **Create App**.
3. Select **Other** -> click Next -> Select **Business** as the app type.
4. Name your app (e.g., `MedAssist-AI`) and click **Create App**.

### 2. Add WhatsApp to Your App
1. On the App Dashboard, scroll to **WhatsApp** and click **Set up**.
2. Click **API Setup** under the WhatsApp menu on the left sidebar.
3. You will see:
   * **Temporary Access Token** (or create a System User for a permanent token).
   * **Phone Number ID** (e.g., `105948372615...`).
   * **Test Phone Number** provided by Meta.
4. Under **"To"**, add your personal WhatsApp phone number as a recipient and enter the verification code sent to your phone.

### 3. Expose your Local Server with `ngrok`
Meta requires a public HTTPS URL to verify your webhook.

1. Download [ngrok](https://ngrok.com/) (free).
2. In a separate terminal, run:
   ```powershell
   ngrok http 8085
   ```
3. Copy the secure Forwarding URL (e.g., `https://xxxx-xx-xx.ngrok-free.app`).

### 4. Configure Meta Webhook
1. In Meta Developer Portal -> WhatsApp -> **Configuration**.
2. Under **Webhook**, click **Edit**:
   * **Callback URL**: `https://xxxx-xx-xx.ngrok-free.app/api/v1/whatsapp/webhook`
   * **Verify Token**: `my_medical_bot_verify_token_2026` *(defined in your `application.yml`)*
3. Click **Verify and Save**. (Your Spring Boot console will log: `Webhook verified successfully with Meta challenge`).
4. Click **Manage** under Webhook fields, and check **`messages`** -> click **Subscribe**.

---

## Step 4: Test the Complete End-to-End System

### A. Test From Your Phone (WhatsApp)
1. Open WhatsApp on your phone.
2. Send `Hi` to the Meta test number.
3. **Emergency Red-Flag Test**: Send `"I have sudden chest pain and trouble breathing"`.
   * *The bot immediately halts normal chat and replies with emergency hotlines.*
4. **Symptom Triage Test**: Send `"I have a sore throat and fever for 2 days"`.
   * *The bot responds with advice and advises consulting a physician.*
5. **Location Booking Test**: Tap the WhatsApp attachment paperclip 📎 -> **Location** -> **Send Your Current Location**.
   * *The bot calculates distances, returns nearby registered hospitals, and lets you pick a doctor.*
   * *The bot issues **Token #03** and generates your official PDF slip.*
6. **Report Wardrobe Test**: Send a photo of any medicine or report.
   * *The bot indexes it and gives an AI explanation.*

### B. Test at the Hospital Desk
1. Open `http://localhost:8085/receptionist.html`.
2. Allow webcam access.
3. Hold the QR code from the generated PDF up to your camera (or type `DEMO-QR-001` into the manual box).
4. Watch it instantly confirm **Check-In**!
5. Check `http://localhost:8085/token-display.html` — the Waiting Room TV display will immediately show that token as **Now Serving**!

---

## Step 5: Going to Cloud Production (When Ready)

When you are ready to deploy to a real cloud server (AWS, DigitalOcean, etc.):
1. Copy `.env.example` to `.env` and fill in your production credentials.
2. Run:
   ```bash
   docker compose up -d --build
   ```
3. Connect your custom domain and add free SSL with Let's Encrypt (as documented in `Dockerfile` and `docker-compose.yml`).
