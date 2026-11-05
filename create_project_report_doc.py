import os
import docx
from docx import Document
from docx.shared import Inches, Pt, RGBColor
from docx.enum.text import WD_ALIGN_PARAGRAPH
from docx.enum.table import WD_TABLE_ALIGNMENT, WD_ALIGN_VERTICAL
from docx.oxml import parse_xml, OxmlElement
from docx.oxml.ns import nsdecls, qn

def set_cell_background(cell, fill_hex):
    tcPr = cell._tc.get_or_add_tcPr()
    tcPr.append(parse_xml(f'<w:shd {nsdecls("w")} w:fill="{fill_hex}"/>'))

def set_cell_margins(cell, top=100, bottom=100, left=150, right=150):
    tcPr = cell._tc.get_or_add_tcPr()
    tcMar = parse_xml(f'<w:tcMar {nsdecls("w")}><w:top w:w="{top}" w:type="dxa"/><w:bottom w:w="{bottom}" w:type="dxa"/><w:left w:w="{left}" w:type="dxa"/><w:right w:w="{right}" w:type="dxa"/></w:tcMar>')
    tcPr.append(tcMar)

def create_document():
    doc = Document()

    # Page Margins
    for section in doc.sections:
        section.top_margin = Inches(1.0)
        section.bottom_margin = Inches(1.0)
        section.left_margin = Inches(1.0)
        section.right_margin = Inches(1.0)

    # Styles & Colors
    # Primary: #0066FF (Blue), Secondary: #0F172A (Dark Navy), Accent: #16A34A (Green)
    PRIMARY_COLOR = RGBColor(0, 102, 255)
    NAVY_COLOR = RGBColor(15, 23, 42)
    GRAY_COLOR = RGBColor(100, 116, 139)

    # Title
    title = doc.add_paragraph()
    title.alignment = WD_ALIGN_PARAGRAPH.CENTER
    run_title = title.add_run("MediAssist: Intelligent AI-Powered Hospital Operations & WhatsApp OPD Ecosystem")
    run_title.font.name = "Arial"
    run_title.font.size = Pt(24)
    run_title.font.bold = True
    run_title.font.color.rgb = PRIMARY_COLOR

    # Subtitle
    subtitle = doc.add_paragraph()
    subtitle.alignment = WD_ALIGN_PARAGRAPH.CENTER
    run_sub = subtitle.add_run("Comprehensive Technical Architecture, System Workflows, Production Hardening & Operational Reference Manual\nPrepared for Claude / Comprehensive Analysis & Report Generation")
    run_sub.font.name = "Arial"
    run_sub.font.size = Pt(12)
    run_sub.font.italic = True
    run_sub.font.color.rgb = GRAY_COLOR

    doc.add_paragraph() # Spacer

    # Callout Box: Executive Metadata
    meta_table = doc.add_table(rows=1, cols=1)
    meta_table.alignment = WD_TABLE_ALIGNMENT.CENTER
    cell = meta_table.cell(0, 0)
    set_cell_background(cell, "F1F5F9")
    set_cell_margins(cell, top=140, bottom=140, left=200, right=200)
    p = cell.paragraphs[0]
    p.add_run("PROJECT METADATA\n").bold = True
    p.add_run("• Platform Name: ").bold = True
    p.add_run("MediAssist Platform (Multi-Tenant Outpatient Operations Engine)\n")
    p.add_run("• Core Technology: ").bold = True
    p.add_run("Spring Boot 3.3.3 (Java 21), PostgreSQL (Supabase with PostGIS), Meta WhatsApp Cloud API (v20.0), Google Gemini 2.0 Flash VLM, Brevo Transactional Email API, Apache PDFBox 2.0, ZXing QR Engine\n")
    p.add_run("• Deployment Target: ").bold = True
    p.add_run("Cloud Containerized on Render (Production: https://mediassist-1hdl.onrender.com)\n")
    p.add_run("• Database Cluster: ").bold = True
    p.add_run("Supabase Dedicated PostgreSQL (aws-0-ap-northeast-1.pooler.supabase.com:5432)\n")
    p.add_run("• Author / Maintainer: ").bold = True
    p.add_run("Engineering & Clinical Innovation Team (Anupam Yagnish)\n")
    p.add_run("• Document Version: ").bold = True
    p.add_run("2.4.0 (Enterprise Production Build)")

    doc.add_paragraph()

    # Section 1: Executive Summary
    h1 = doc.add_heading("1. Executive Summary & Problem Statement", level=1)
    h1.runs[0].font.color.rgb = NAVY_COLOR

    p = doc.add_paragraph()
    p.add_run("Modern outpatient department (OPD) healthcare delivery across Tier-1 and Tier-2 hospitals suffers from severe operational bottlenecks: long unmanaged queues, overcrowded waiting lounges, lost paper prescriptions, cumbersome proprietary hospital apps that patients refuse to install, and fragmented scheduling systems. Hospital managers lack real-time visibility into doctor room progression, while patients experience intense frustration with no real-time transparency regarding their token position.")

    p = doc.add_paragraph()
    p.add_run("MediAssist solves this through a zero-friction paradigm: ").bold = True
    p.add_run("Instead of demanding patient app downloads, MediAssist brings full-lifecycle hospital discovery, doctor roster exploration, appointment booking, digital OPD case sheet generation, queue tracking, and prescription reminders directly into WhatsApp — the messaging interface patients already use daily. For healthcare facilities, MediAssist provides a multi-tenant hospital onboarding pipeline, customized clinical branding for official A4 OP case sheets, live receptionist check-in consoles, and real-time consultation progression tracking.")

    # Section 2: End-to-End System Architecture
    h1 = doc.add_heading("2. High-Level Technical Architecture", level=1)
    h1.runs[0].font.color.rgb = NAVY_COLOR

    p = doc.add_paragraph()
    p.add_run("The MediAssist platform is structured as an event-driven, microservices-ready modular monolith built upon Spring Boot 3 and Java 21, communicating with high-reliability cloud services:")

    arch_points = [
        ("Presentation & Bot Layer", "Meta WhatsApp Cloud API v20.0 Webhook Controller handles incoming patient messages with zero-blocking asynchronous CompletableFuture processing (< 25ms response). Responsive HTML5/Tailwind/CSS3 portals provide interfaces for Super Admins, Hospital Managers, and Public Hospital Microsites."),
        ("Application & Domain Logic", "Spring Boot 3 REST controllers and Spring Security 6 provide role-based access control (SUPER_ADMIN, HOSPITAL_MANAGER, PATIENT_SESSION). Services handle appointment slot concurrency locking, PDFBox clinical vector rendering, rate limiting, and Brevo transactional emailing."),
        ("AI & Intelligence Services", "Google Gemini 2.0 Flash Vision-Language Model (VLM) parses handwritten and printed medical prescriptions directly from WhatsApp camera uploads, extracting medicine names, dosages, frequencies, and durations with zero hallucination regex safety guards."),
        ("Persistence & Geospatial Layer", "PostgreSQL database hosted on Supabase featuring PostGIS for spatial radius search (Haversine/ST_Distance calculations), HikariCP high-performance connection pooling with explicit read-write transaction guarantees, and JPA/Hibernate entity mappings."),
        ("Companion Mobile Ecosystem", "Native Android Jetpack Compose application designed for smart medication alarms, synchronizing prescription schedules via secured REST APIs with HIPAA/DPDP Act compliant OTP verification.")
    ]

    for title_text, desc_text in arch_points:
        p = doc.add_paragraph(style='List Bullet')
        p.add_run(f"{title_text}: ").bold = True
        p.add_run(desc_text)

    # Section 3: Deep Dive into Core Workflows
    h1 = doc.add_heading("3. Detailed End-to-End System Workflows", level=1)
    h1.runs[0].font.color.rgb = NAVY_COLOR

    # Workflow 1
    h2 = doc.add_heading("Workflow 1: Multi-Tenant Hospital Onboarding & Self-Healing Approval", level=2)
    h2.runs[0].font.color.rgb = PRIMARY_COLOR

    p = doc.add_paragraph()
    p.add_run("1. Submission: ").bold = True
    p.add_run("A hospital applies publicly via ")
    p.add_run("POST /api/v1/apply").italic = True
    p.add_run(" supplying hospital name, registration number, address, contact administrator name, phone, email, and bed capacity. Status is set to PENDING_REVIEW.")
    
    p = doc.add_paragraph()
    p.add_run("2. Review & Self-Healing Approval: ").bold = True
    p.add_run("The Super Admin inspects pending hospitals in the Admin Portal (admin-portal.html). Upon clicking 'Approve':\n")
    p.add_run("   • The hospital record status is transitioned to ACTIVE.\n")
    p.add_run("   • A corresponding Hospital Manager account is created/updated in the users table with role HOSPITAL_MANAGER.\n")
    p.add_run("   • A cryptographically strong temporary password is generated (e.g. Temp@1234).\n")
    p.add_run("   • The system dispatches an authentic credentials email via Brevo HTTPS API with login portal links.\n")
    p.add_run("   • In addition to email delivery, the temporary password and manager email are displayed immediately on-screen in an administrative dialog.\n")
    p.add_run("   • Self-Healing Protection: If an application was previously interrupted or in ACTIVE state with a missing user, the approval endpoint automatically self-heals by provisioning the missing user and regenerating credentials without throwing 400 Bad Request errors.")

    p = doc.add_paragraph()
    p.add_run("3. Explicit Credentials Resend: ").bold = True
    p.add_run("Admins can click 'Resend Email' at any point via ")
    p.add_run("POST /api/v1/admin/applications/{id}/resend-credentials").italic = True
    p.add_run(" to generate a fresh password and dispatch it without modifying hospital roster state.")

    # Workflow 2
    h2 = doc.add_heading("Workflow 2: Hospital Customization & OP Case Sheet Clinical PDF Engine", level=2)
    h2.runs[0].font.color.rgb = PRIMARY_COLOR

    p = doc.add_paragraph()
    p.add_run("Hospital managers log into the Manager Portal (manager-portal.html) to tailor their hospital's operational and clinical identity:")
    p.add_run("\n• Real-Time Logo Auto-Save: Selecting a hospital medical crest (PNG/JPG) immediately uploads and saves it to the database via ")
    p.add_run("POST /api/v1/hospital-manager/settings/logo").italic = True
    p.add_run(", eliminating lost unsaved state.")
    p.add_run("\n• Visual Branding: Managers configure primary brand accent color (hex color picker), hospital tagline, emergency 24x7 phone, department listings, and custom case sheet footer instructions (e.g. 'Valid for 7 days. Please bring this slip on follow-up.').")
    p.add_run("\n• A4 Clinical OP Case Sheet Rendering: Modeled directly upon premier tertiary hospital standards (such as KIMS Outpatient Record). Generated dynamically using Apache PDFBox with LosslessFactory vector rasterization:")
    p.add_run("\n   - Left Header: Official MediAssist platform brand logo.")
    p.add_run("\n   - Right Header: Hospital's custom medical crest/logo (decoded seamlessly from base64/URL).")
    p.add_run("\n   - Center Header: Hospital official title, accreditation subline, and department badge.")
    p.add_run("\n   - Banner Bar: Hospital-accent colored banner with dynamic OP Token Number and Barcode Token.")
    p.add_run("\n   - Two-Column Patient Grid: MRN, Patient Name, Age/Sex, Visit Date & Time, Doctor Room Number, and Allergies/Blood Group.")
    p.add_run("\n   - Clinical Work Area: Watermarked sections for Chief Complaints, Clinical Observations, Diagnosis, Lab Orders, and Rx Treatment Plan.")
    p.add_run("\n   - Dual QR Codes: QR #1 encodes the patient verification token; QR #2 encodes the hospital's WhatsApp booking bot for easy re-engagement.")

    # Workflow 3
    h2 = doc.add_heading("Workflow 3: WhatsApp Patient Discovery, Smart Slot Allocation & Token Booking", level=2)
    h2.runs[0].font.color.rgb = PRIMARY_COLOR

    p = doc.add_paragraph()
    p.add_run("1. Discovery & Geolocation: ").bold = True
    p.add_run("Patients message the verified WhatsApp Business Number. They can send their GPS pin location or type a hospital/city name (e.g. 'hospitals in Bhubaneswar'). MediAssist calculates distances using PostGIS/Haversine and presents the top nearby facilities.")
    p.add_run("\n2. Doctor & Slot Exploration: ").bold = True
    p.add_run("The patient selects their required department (Cardiology, Orthopedics, Pediatrics, General Medicine). The bot returns available specialist doctors, their consultation fees, room numbers, and OPD shift timings (Morning 1st Half / Evening 2nd Half).")
    p.add_run("\n3. Concurrency-Safe Token Issuance: ").bold = True
    p.add_run("When a slot is booked, the backend activates a per-doctor ReentrantLock and queries active tokens. It allocates the next sequential integer token (#01, #02, etc.), checks the doctor's daily quota limit, and persists the booking. The patient immediately receives an interactive WhatsApp confirmation containing their token number, room assignment, arrival instructions, and a one-click link to view/download their authentic A4 OP Case Sheet PDF.")

    # Workflow 4
    h2 = doc.add_heading("Workflow 4: Receptionist QR Check-In, Walk-In Tokens & Consultation Progression", level=2)
    h2.runs[0].font.color.rgb = PRIMARY_COLOR

    p = doc.add_paragraph()
    p.add_run("• Physical Check-In: ").bold = True
    p.add_run("Upon arrival at the hospital, the patient presents their WhatsApp appointment slip. The receptionist uses the integrated camera scanner in the Manager Portal to scan the QR code (or inputs the 6-character token code). The system validates the token and marks the patient status from CONFIRMED to CHECKED_IN.")
    p.add_run("\n• Instant Walk-In Token Generator: ").bold = True
    p.add_run("For walk-in patients without prior bookings, the reception desk issues an instant OPD Token with one click via ")
    p.add_run("POST /api/v1/appointments/walk-in").italic = True
    p.add_run(", generating a live serial number, printing the branded OP slip, and simultaneously dispatching a WhatsApp confirmation to the patient's phone.")
    p.add_run("\n• Consultation Progression & Session Security: ").bold = True
    p.add_run("The receptionist clicks 'Start Consultation', transitioning the patient to IN_CONSULTATION and generating a dynamic 6-digit Session PIN. This PIN prevents unauthorized clinical access. Crucially, the system identifies the next waiting patients in queue and fires a WhatsApp alert: ")
    p.add_run("'Doctor is now seeing Token #14. You are next in line (#15)! Please proceed outside Room 104.'").italic = True

    # Workflow 5 (The Rescheduling & Reminder Engine)
    h2 = doc.add_heading("Workflow 5: Non-Intrusive Reminders & 1-Tap Rescheduling (Deep Dive)", level=2)
    h2.runs[0].font.color.rgb = PRIMARY_COLOR

    p = doc.add_paragraph()
    p.add_run("A critical innovation of MediAssist is eliminating irritating, repetitive reminder alarms while keeping queues strictly synchronized:")
    
    p = doc.add_paragraph(style='List Bullet')
    p.add_run("Strict Two-Reminder Limit: ").bold = True
    p.add_run("Reminders fire ONLY TWICE before the appointment in Asia/Kolkata (IST) time: ")
    p.add_run("(1) Exactly 1 hour before").italic = True
    p.add_run(" (~60 min prior to start time), and ")
    p.add_run("(2) Exactly 30 minutes before").italic = True
    p.add_run(" (~30 min prior to start time). Persistent database columns (reminded_1h, reminded_30m) guarantee that server restarts or redeploys can never re-trigger these messages.")

    p = doc.add_paragraph(style='List Bullet')
    p.add_run("Single Post-Slot Reschedule Notice (No Irritating Alarms): ").bold = True
    p.add_run("If the appointment time passes and the patient has not arrived or checked in (status remains CONFIRMED), the system stops all alarms and sends exactly ONE polite reschedule message: ")
    p.add_run("'🗓️ Missed Your Appointment? Hello [Name], we noticed you were unable to make it today. Would you like to reschedule your consultation for another date? Reply RESCHEDULE to choose a convenient new slot.'").italic = True
    p.add_run(" The appointment status is marked CANCELLED and missed_reschedule_sent is set to true. No further messages are ever sent.")

    p = doc.add_paragraph(style='List Bullet')
    p.add_run("How Next Available Slot Rescheduling Works Under the Hood: ").bold = True
    p.add_run("When the patient replies 'RESCHEDULE':\n")
    p.add_run("   a. Slot Release: The webhook engine matches the keyword and immediately marks the previous appointment as CANCELLED in Supabase PostgreSQL, releasing the integer token back into the doctor's quota.\n")
    p.add_run("   b. Live Roster Inspection (Not Patient Choice / Guesswork): Instead of letting the patient type arbitrary or off-duty dates, the system automatically checks the doctor's weekly roster (doc.isAvailableOnDay), verifies daily quota caps, and calculates booked vs limit for the upcoming 7 days.\n")
    p.add_run("   c. Next Available Slots Menu: The bot immediately replies with the verified Next Available Slots interactive list showing real-time token availability (e.g., '1. Tomorrow (Wed, 6 Nov) - 🟢 14 tokens left', '2. Thursday (7 Nov) - 🟢 20 tokens left').\n")
    p.add_run("   d. Instant 1-Tap Allocation: The patient simply replies with the number corresponding to the next available slot. The system automatically allocates the next sequential integer token (#01, #02...), generates a unique SHA-256 QR code, and sends a new digital appointment slip via WhatsApp.")


    p = doc.add_paragraph(style='List Bullet')
    p.add_run("How the Hospital Admin Knows It Is Rescheduled: ").bold = True
    p.add_run("In the Hospital Manager Portal (manager-portal.html), the Appointments table updates in real time. The old slot row turns red with badge CANCELLED and drops out of the active waiting count. On the newly selected date, a new row appears with the new token number and patient details. The doctor's daily quota counter immediately reflects the freed slot on the old day and the allocated slot on the new day.")

    p = doc.add_paragraph(style='List Bullet')
    p.add_run("How Other Patients Know & Benefit: ").bold = True
    p.add_run("In WhatsApp, waiting patients can text 'queue' at any time to receive real-time queue intelligence ('Patients ahead of you: X'). When a no-show slot is released, the queue length automatically decreases, moving everyone behind them forward. If a popular doctor's daily quota was capped, the release allows an urgent walk-in patient to secure an appointment.")

    # Section 4: Production Hardening & Solved Issues
    h1 = doc.add_heading("4. Production Engineering & Architectural Fixes", level=1)
    h1.runs[0].font.color.rgb = NAVY_COLOR

    fixes = [
        ("Supabase Connection Pooler Read-Only Contamination", 
         "Symptom: Intermittent 'ERROR: cannot execute INSERT in a read-only transaction' when saving hospital applications or users.\n"
         "Root Cause: Transaction pooler (port 6543) connections inherited read-only state from earlier read transactions.\n"
         "Permanent Fix: Reconfigured HikariCP in DatabaseConfig.java with config.setReadOnly(false) and config.setConnectionInitSql('SET default_transaction_read_only = off;'), ensuring all pooled sessions explicitly permit writes."),
        
        ("Database Schema Column Mismatch (users.name NOT NULL)",
         "Symptom: Super Admin approval endpoint threw HTTP 500 Internal Server Error when creating hospital manager accounts.\n"
         "Root Cause: Supabase database had a legacy column 'name' marked as NOT NULL, while JPA entity User.java only mapped 'fullName', resulting in null-value constraint violations on Hibernate inserts.\n"
         "Permanent Fix: Dropped the NOT NULL constraint on users.name in PostgreSQL, mapped both 'name' and 'fullName' in User.java with synchronized getters/setters, and added automated idempotent migrations in DatabaseConfig.java."),
        
        ("Meta WhatsApp Webhook Timeout Drops",
         "Symptom: Meta marked incoming webhooks as dropped/retried if AI processing or database queries took longer than 3 seconds.\n"
         "Permanent Fix: Refactored WhatsAppWebhookController.java to process incoming payloads asynchronously using CompletableFuture.runAsync, returning an immediate HTTP 200 OK to Meta in < 25ms."),
        
        ("Timezone Calibration (UTC vs IST)",
         "Symptom: Appointment reminders repeatedly fired at night on Render because the server evaluated time in UTC (14:45 UTC = 20:15 IST) against evening appointment slots.\n"
         "Permanent Fix: Calibrated all scheduler computations in AppointmentReminderService.java to ZoneId.of('Asia/Kolkata')."),
        
        ("Case Sheet Hospital Logo Isolation",
         "Symptom: Manager preview of OP Case Sheet displayed a default crest instead of the newly uploaded hospital crest.\n"
         "Root Cause: /sample-pdf was hardcoded to fetch the last appointment across all hospitals, and preview clicks did not pass hospital context.\n"
         "Permanent Fix: Added ?hospitalId=X support to /sample-pdf, wired manager portal preview button to supply current hospital ID, implemented immediate auto-save on logo selection, and bypassed in-memory PDF cache with buildFreshPdf().")
    ]

    for title_text, desc_text in fixes:
        p = doc.add_paragraph()
        p.add_run(f"• {title_text}:\n").bold = True
        p.add_run(desc_text)

    # Section 5: Database Schema Reference
    h1 = doc.add_heading("5. Database Schema & Data Models", level=1)
    h1.runs[0].font.color.rgb = NAVY_COLOR

    schema_table = doc.add_table(rows=1, cols=3)
    schema_table.alignment = WD_TABLE_ALIGNMENT.CENTER
    hdr_cells = schema_table.rows[0].cells
    hdr_cells[0].text = "Table Name"
    hdr_cells[1].text = "Key Columns & Constraints"
    hdr_cells[2].text = "Functional Purpose"
    for c in hdr_cells:
        set_cell_background(c, "0066FF")
        c.paragraphs[0].runs[0].font.color.rgb = RGBColor(255, 255, 255)
        c.paragraphs[0].runs[0].font.bold = True

    tables_data = [
        ("hospitals", "id (PK), name, registration_number, address, city, state, phone, contact_person_name, contact_person_email, contact_person_phone, specialties, number_of_beds, brand_color, logo_url (TEXT), tagline, emergency_phone, custom_footer_notes, status (ACTIVE, PENDING_REVIEW, REJECTED, SUSPENDED), applied_at, reviewed_at", "Multi-tenant hospital facilities and clinical branding data."),
        ("doctors", "id (PK), hospital_id (FK), name, department, qualification, room_number, consultation_fee, daily_token_limit, available_days, is_available", "Specialist doctor profiles, consultation fees, rooms, and daily token quotas."),
        ("users", "id (PK), email (UNIQUE), password (BCrypt), name, full_name, phone_number, role (SUPER_ADMIN, HOSPITAL_MANAGER), hospital_id (FK), active, must_change_password", "Hospital administrators and system super-admins with credential tracking."),
        ("appointments", "id (PK), hospital_id (FK), doctor_id (FK), patient_phone, patient_name, appointment_date, time_slot, serial_number, qr_code_token (UNIQUE), status (CONFIRMED, CHECKED_IN, IN_CONSULTATION, COMPLETED, CANCELLED), reminded_1h, reminded_30m, missed_reschedule_sent, reminded_24h", "Live appointment records, integer token sequences, QR tokens, and reminder state."),
        ("patient_profiles", "phone_number (PK), full_name, age, gender, blood_group, known_allergies, emergency_contact, is_profile_complete", "Longitudinal patient clinical profile used on OP Case Sheets."),
        ("patient_sessions", "phone_number (PK), latitude, longitude, preferred_department, updated_at", "Transient GPS location and search preferences for discovery."),
        ("medication_reminders", "id (PK), patient_phone, medicine_name, dosage_instruction, reminder_time, active, created_at", "Prescription reminder schedules extracted by Gemini 2.0 Flash VLM."),
        ("medical_documents", "id (PK), patient_phone, title, document_url, document_type, uploaded_at", "Digital medical wardrobe / locker reports and prescriptions.")
    ]

    for t_name, cols, purpose in tables_data:
        row_cells = schema_table.add_row().cells
        row_cells[0].text = t_name
        row_cells[0].paragraphs[0].runs[0].font.bold = True
        row_cells[1].text = cols
        row_cells[2].text = purpose
        for c in row_cells:
            set_cell_margins(c, top=80, bottom=80, left=100, right=100)

    doc.add_paragraph()

    # Section 6: API Catalog
    h1 = doc.add_heading("6. API Catalog & Endpoint Specification", level=1)
    h1.runs[0].font.color.rgb = NAVY_COLOR

    apis = [
        ("POST /api/v1/apply", "Public hospital self-service application submission."),
        ("POST /api/v1/auth/login", "Portal authentication for Super Admins and Hospital Managers."),
        ("GET /api/v1/admin/applications", "Super Admin endpoint to list all pending and reviewed hospitals."),
        ("POST /api/v1/admin/applications/{id}/approve", "Self-healing approval creating manager, setting credentials, and emailing applicant."),
        ("POST /api/v1/admin/applications/{id}/resend-credentials", "Generates fresh credentials and dispatches welcome email on demand."),
        ("GET /api/v1/hospital-manager/my-hospital", "Returns authenticated manager's hospital profile and stats."),
        ("PUT /api/v1/hospital-manager/settings", "Updates hospital branding, tagline, emergency phone, footer notes, and colors."),
        ("POST /api/v1/hospital-manager/settings/logo", "Multipart file upload for hospital medical crest with auto-compression."),
        ("GET /api/v1/hospital-manager/doctors", "Returns doctor roster for authenticated manager's facility."),
        ("POST /api/v1/appointments/walk-in", "Issues instant reception desk walk-in OPD token with concurrency safety."),
        ("GET /api/v1/appointments/{id}/pdf", "Generates authentic A4 OP Case Sheet PDF with latest hospital branding."),
        ("GET /api/v1/appointments/sample-pdf?hospitalId={id}", "Previews branded case sheet for a specific hospital."),
        ("POST /api/v1/appointments/start-consultation", "Transitions patient to IN_CONSULTATION, creates session PIN, and notifies next in queue."),
        ("POST /api/v1/whatsapp/webhook", "Meta WhatsApp Cloud API bidirectional message handler.")
    ]

    for ep, desc in apis:
        p = doc.add_paragraph(style='List Bullet')
        p.add_run(f"{ep}: ").bold = True
        p.add_run(desc)

    # Section 7: Future Enterprise Roadmap
    h1 = doc.add_heading("7. Future Enterprise Roadmap", level=1)
    h1.runs[0].font.color.rgb = NAVY_COLOR

    roadmap_items = [
        ("ABDM (Ayushman Bharat Digital Mission) Integration", "M1/M2/M3 compliance for creating ABHA ID, linking health records, and generating ABDM consent tokens."),
        ("Teleconsultation & Video Bridge", "Seamless WebRTC video consultation link dispatched via WhatsApp when doctor starts a virtual OPD session."),
        ("Pharmacy & Lab Order Dispatch", "Direct integration with hospital pharmacy dispensary to notify patients when prescribed medications are packaged and ready for pickup."),
        ("Multilingual Vernacular Voice AI", "Voice-based OPD booking via WhatsApp audio notes in Odia, Hindi, Bengali, Telugu, and Tamil using Gemini Multimodal Audio.")
    ]

    for title_text, desc_text in roadmap_items:
        p = doc.add_paragraph(style='List Bullet')
        p.add_run(f"{title_text}: ").bold = True
        p.add_run(desc_text)

    # Save to file
    filenames = [
        "MediAssist_Complete_Project_Report_and_Architecture.docx",
        "MediAssist_Project_Report_For_Claude.docx"
    ]
    saved = []
    for fn in filenames:
        try:
            doc.save(fn)
            saved.append(fn)
            print(f"Document created successfully: {fn}")
        except Exception as e:
            print(f"Could not save {fn}: {e}")
    
    # Also copy to parent root if saved
    import shutil
    for s in saved:
        try:
            parent_path = os.path.join("..", s)
            shutil.copyfile(s, parent_path)
            print(f"Copied to workspace root: {parent_path}")
        except Exception as e:
            print(f"Could not copy to parent: {e}")

if __name__ == "__main__":
    create_document()
