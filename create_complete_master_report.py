import os
import shutil
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

def add_callout(doc, title, text, bg_color="F1F5F9", border_color="0066FF"):
    tbl = doc.add_table(rows=1, cols=1)
    tbl.alignment = WD_TABLE_ALIGNMENT.CENTER
    cell = tbl.cell(0, 0)
    set_cell_background(cell, bg_color)
    set_cell_margins(cell, top=140, bottom=140, left=200, right=200)
    
    p = cell.paragraphs[0]
    r_title = p.add_run(f"📌 {title}\n")
    r_title.bold = True
    r_title.font.name = "Arial"
    r_title.font.size = Pt(11)
    r_title.font.color.rgb = RGBColor(0, 102, 255)
    
    r_text = p.add_run(text)
    r_text.font.name = "Arial"
    r_text.font.size = Pt(10)
    r_text.font.color.rgb = RGBColor(51, 65, 85)
    doc.add_paragraph()

def build_master_document():
    doc = Document()

    # Page Margins (0.8 inches all around for professional density)
    for section in doc.sections:
        section.top_margin = Inches(0.8)
        section.bottom_margin = Inches(0.8)
        section.left_margin = Inches(0.8)
        section.right_margin = Inches(0.8)
        # Footer
        footer = section.footer
        f_p = footer.paragraphs[0]
        f_p.alignment = WD_ALIGN_PARAGRAPH.RIGHT
        f_run = f_p.add_run("© 2026 MediAssist Digital Health. All rights reserved. | Confidential")
        f_run.font.name = "Arial"
        f_run.font.size = Pt(8.5)
        f_run.font.color.rgb = RGBColor(100, 116, 139)

    PRIMARY = RGBColor(0, 102, 255)      # Medical Tech Blue
    NAVY = RGBColor(15, 23, 42)          # Slate Dark Navy
    GRAY = RGBColor(100, 116, 139)       # Cool Slate Gray
    GREEN = RGBColor(22, 163, 74)        # Success Green
    RED = RGBColor(220, 38, 38)          # Alert Red

    # Document Header / Banner
    title_p = doc.add_paragraph()
    title_p.alignment = WD_ALIGN_PARAGRAPH.CENTER
    r_title = title_p.add_run("MediAssist: Intelligent Hospital Operations &\nWhatsApp OPD Ecosystem")
    r_title.font.name = "Arial"
    r_title.font.size = Pt(24)
    r_title.font.bold = True
    r_title.font.color.rgb = PRIMARY

    sub_p = doc.add_paragraph()
    sub_p.alignment = WD_ALIGN_PARAGRAPH.CENTER
    r_sub = sub_p.add_run("Complete System Architecture, Operational Workflows & Exhaustive 2-Member Quality Assurance (QA) Testing Manual\nCovering All 14 Functional Tracks, Safety Guardrails, Endpoints & Edge Cases")
    r_sub.font.name = "Arial"
    r_sub.font.size = Pt(12)
    r_sub.font.italic = True
    r_sub.font.color.rgb = GRAY

    doc.add_paragraph()

    # Metadata Callout Box
    meta_box = doc.add_table(rows=1, cols=1)
    meta_box.alignment = WD_TABLE_ALIGNMENT.CENTER
    c = meta_box.cell(0, 0)
    set_cell_background(c, "F8FAFC")
    set_cell_margins(c, top=140, bottom=140, left=200, right=200)
    mp = c.paragraphs[0]
    mp.add_run("DOCUMENT METADATA & DEPLOYED SPECIFICATIONS\n").bold = True
    mp.runs[0].font.color.rgb = NAVY
    mp.add_run("• Production Platform: ").bold = True
    mp.add_run("Render Cloud PaaS (Live Service URL: https://mediassist-1hdl.onrender.com)\n")
    mp.add_run("• Core Technology Stack: ").bold = True
    mp.add_run("Java 21, Spring Boot 3.3.4, Apache PDFBox, HikariCP, PgBouncer\n")
    mp.add_run("• AI & Messaging Layer: ").bold = True
    mp.add_run("Google Gemini 2.0 Flash Vision-Language Model (VLM), Meta WhatsApp Cloud API (v21.0)\n")
    mp.add_run("• Database Engine: ").bold = True
    mp.add_run("Supabase PostgreSQL (AWS ap-northeast-1 / Transaction Pooler)\n")
    mp.add_run("• QA Team Size: ").bold = True
    mp.add_run("2 Members (Tester 1: Hospital Admin / Manager Lead; Tester 2: Patient / WhatsApp Lead)\n")
    mp.add_run("• Date & Version: ").bold = True
    mp.add_run("November 2026 | Release Candidate v1.0.0 (Production Verified)\n")
    mp.add_run("• Copyright: ").bold = True
    mp.add_run("© 2026 MediAssist Digital Healthcare Platform. All rights reserved.")
    doc.add_paragraph()

    # =========================================================================
    # SECTION 1: EXECUTIVE SUMMARY & ARCHITECTURAL HIGHLIGHTS
    # =========================================================================
    h1 = doc.add_heading("1. Executive Summary & Core Engineering", level=1)
    h1.runs[0].font.color.rgb = NAVY

    p = doc.add_paragraph()
    p.add_run("MediAssist is a unified, multi-tenant digital health ecosystem architected to resolve the two largest bottlenecks in Indian outpatient healthcare: chaotic, paper-heavy physical OPD queues and the complex friction of traditional mobile apps. By deploying an intelligent, zero-install conversational interface directly over WhatsApp paired with high-performance administrative web portals, MediAssist provides instant appointment booking, atomic sequential token allocation, high-resolution A4 dual-QR clinical case sheets, multimodal vision AI prescription digitization, calibrated reminders, and interactive slot rescheduling.")

    add_callout(doc, "Key Architectural Highlights", 
        "1. Multi-Tenant Hospital Isolation: Complete separation of branding, doctors, quotas, and logos across independent healthcare facilities.\n"
        "2. Deterministic Integer Sequencing: Daily tokens follow strict sequential ordering (#01, #02, #03...) per doctor per day, preventing queue collisions.\n"
        "3. Decoupled Webhook Ingress: Inbound Meta WhatsApp webhooks return HTTP 200 OK within 25ms via CompletableFuture async execution, eliminating webhook retry spikes.\n"
        "4. Calibrated Asia/Kolkata Scheduler: Notification background cron runs every 60 seconds calibrated strictly to Indian Standard Time (IST), querying persistent PostgreSQL flags to guarantee zero duplicate alerts.\n"
        "5. Next Available Slots Engine: Cancels no-show/rescheduled slots, frees quota back to the doctor, and displays live available days with remaining token counts instead of open-ended patient guesswork.")

    # =========================================================================
    # SECTION 2: THE 5 SYSTEM ARCHITECTURE & SEQUENCE DIAGRAMS
    # =========================================================================
    h1 = doc.add_heading("2. System Architecture & Complete Flow Diagrams", level=1)
    h1.runs[0].font.color.rgb = NAVY

    p = doc.add_paragraph()
    p.add_run("The MediAssist platform is structured across five major operational diagrams illustrating component boundaries, client touchpoints, and sequence data flows:")

    diagrams = [
        ("Diagram 1: Master System Architecture Topology",
         "Component topology showing Client Interfaces (WhatsApp User, Reception Walk-in Desk, Super Admin & Manager Web Portals) communicating via Render Cloud Edge PaaS to the Spring Boot 3.3.4 Application Core (Webhook Controller, Auth Service, Appointment Engine, Live Roster Engine, PDFBox Slip Service, Gemini AI, Email Service) integrated with Meta Graph API, Google Gemini 2.0 Flash VLM, and Supabase PostgreSQL via PgBouncer.",
         """flowchart TD
    subgraph CLIENTS["1. Client Touchpoints & Interfaces"]
        WA["WhatsApp Client (Patient)"]
        WALKIN["Hospital Reception Desk"]
        PORTALS["Web Portals (Admin / Manager / Doctor)"]
    end
    subgraph INGRESS["2. Edge & Ingress Layer"]
        EDGE["Render Cloud Reverse Proxy & Rate Limiter"]
    end
    subgraph BACKEND["3. Spring Boot Core Application (Java 21)"]
        WEBHOOK["WhatsApp Webhook Controller (<25ms Async)"]
        AUTH["Security & Patient Auth Engine (JWT/OTP)"]
        APPT["OPD Booking Engine (Atomic #01, #02 Tokens)"]
        ROSTER["Live Doctor Roster & Slots Service"]
        REMINDER["Calibrated Reminder Scheduler (IST)"]
        PDF["OP Case Sheet PDFBox Engine (Dual QR Codes)"]
        AI["Gemini AI Service (VLM Prescription Parsing)"]
        EMAIL["Transactional Email Service (Credentials)"]
    end
    subgraph EXTERNAL["4. External Cloud Services"]
        META["Meta WhatsApp Cloud API (v21.0)"]
        GEMINI["Google Gemini 2.0 Flash Vision-Language"]
        SMTP["SMTP Mail Gateway"]
    end
    subgraph PERSISTENCE["5. Managed Cloud Persistence"]
        POOLER["PgBouncer Connection Pooler (HikariCP)"]
        DB[("Supabase PostgreSQL DB")]
    end
    WA <--> META <--> WEBHOOK
    PORTALS & WALKIN <--> EDGE --> BACKEND
    BACKEND <--> POOLER <--> DB
    AI <--> GEMINI
    EMAIL <--> SMTP
    REMINDER --> META"""),

        ("Diagram 2: Patient Onboarding & WhatsApp OTP Verification",
         "Sequence: Unregistered user sends 'Hi' -> Webhook checks DB -> Returns 1-time /register.html link -> Mobile web form captures demographics (Name, Gender, Blood Group, Allergies, DOB via calendar) -> POST OTP request -> 6-digit OTP sent to WhatsApp -> User submits OTP -> Profile saved in patient_profiles -> WhatsApp welcome message with Quick Action buttons.",
         """sequenceDiagram
    autonumber
    actor Patient as WhatsApp Patient
    participant WA as Meta WhatsApp Cloud API
    participant Bot as Webhook Controller
    participant Portal as Mobile Web (/register.html)
    participant Auth as PatientAuthService
    participant DB as Supabase PostgreSQL

    Patient->>WA: Sends "Hi" or shares GPS location
    WA->>Bot: Webhook payload received
    Bot->>DB: Query patient_profiles by phone number
    DB-->>Bot: Patient not found (Unregistered)
    Bot-->>WA: Dispatches registration link (/register.html?phone=...)
    WA-->>Patient: Receives secure registration URL
    Patient->>Portal: Opens link on mobile browser
    Patient->>Portal: Enters Name, Gender, Blood Group, Allergies, DOB
    Patient->>Portal: Clicks "Send WhatsApp OTP"
    Portal->>Auth: POST /api/v1/patient/otp/request
    Auth->>WA: Sends 6-digit OTP code directly to chat
    WA-->>Patient: "Your MediAssist verification code is: 482910"
    Patient->>Portal: Enters OTP & clicks "Complete Registration"
    Portal->>Auth: POST /api/v1/patient/otp/verify
    Auth->>DB: Saves demographic profile into patient_profiles
    Bot-->>WA: "✅ Welcome [Name]! Your profile is verified."
    WA-->>Patient: Receives interactive buttons [⚡ Book OPD] [📁 Medical Locker]"""),

        ("Diagram 3: Multi-Tenant Hospital Onboarding & Self-Healing Approval",
         "Sequence: Hospital submits registration on /apply.html with GPS autofill -> Saved as PENDING_REVIEW -> Super Admin logs into /admin-portal.html -> Reviews details and clicks 'Approve' -> Self-healing transaction sets hospital ACTIVE, creates HOSPITAL_MANAGER account, maps name & fullName, hashes BCrypt password -> Dispatches Welcome Email with credentials -> Manager logs into /manager-portal.html, uploads crest logo (/settings/logo) and configures doctors.",
         """sequenceDiagram
    autonumber
    actor Hospital as Hospital Representative
    participant Apply as Application Portal (/apply.html)
    participant Admin as Super Admin Portal (/admin-portal.html)
    participant API as SuperAdminApiController
    participant Email as Transactional Email Service
    participant DB as Supabase PostgreSQL
    actor Manager as Hospital Manager

    Hospital->>Apply: Submits Name, Address, Beds, Specialties & GPS Location
    Apply->>DB: Saves application with status PENDING_REVIEW
    Admin->>API: GET /api/v1/admin/applications (View pending queue)
    API->>DB: Query applications where status = 'PENDING_REVIEW'
    DB-->>Admin: Displays hospital application card
    Admin->>API: Clicks "Approve" (POST /api/v1/admin/applications/{id}/approve)
    rect rgb(240, 253, 244)
        Note over API,DB: Self-Healing Provisioning Transaction
        API->>DB: Update hospital status to ACTIVE
        API->>DB: Create HOSPITAL_MANAGER user account (sync name/fullName)
        API->>DB: Store BCrypt-hashed temporary password
    end
    API->>Email: Dispatch welcome email with login link & credentials
    Email-->>Hospital: "Welcome to MediAssist — Your Hospital Portal Credentials"
    API-->>Admin: Returns credentials dialog on-screen
    Manager->>Manager: Logs into /manager-portal.html
    Manager->>DB: Uploads custom medical crest (/settings/logo) & sets brand theme"""),

        ("Diagram 4: Instant OPD Token Booking & Dual-QR OP Case Sheet Generation",
         "Sequence: Patient sends 'book opd' -> Selects Hospital & Doctor -> Backend atomically computes next sequential integer token (#01, #02...) -> Generates unique SHA-256 hash -> Fetches hospital medical crest & brand colors -> In-memory Apache PDFBox renders clinical A4 Case Sheet with dual QR codes (Doctor desk check-in + Live queue tracking) and allergy alert badge -> Dispatches PDF file directly to WhatsApp chat.",
         """sequenceDiagram
    autonumber
    actor Patient as WhatsApp Patient
    participant WA as Meta WhatsApp Cloud API
    participant Bot as Webhook Controller
    participant Engine as Appointment Booking Engine
    participant PDF as AppointmentSlipPdfService (PDFBox)
    participant DB as Supabase PostgreSQL

    Patient->>WA: Taps [⚡ Instant OPD Token] or texts "book opd"
    WA->>Bot: Webhook dispatched (< 25ms async handling)
    Bot->>DB: Fetch nearby active hospitals & department doctor roster
    Bot-->>WA: Displays list of doctors & consultation timings
    WA-->>Patient: "Select Doctor: 1. Dr. Rajesh Mohapatra (Cardiology)"
    Patient->>WA: Replies "1"
    WA->>Bot: Doctor selection received
    rect rgb(255, 251, 235)
        Note over Engine,DB: Atomic Token Allocation & Verification
        Engine->>DB: Calculate next sequential integer token (#01, #02...)
        Engine->>DB: Fetch verified patient demographics (Blood Group, Allergies, Age)
        Engine->>DB: Generate unique SHA-256 verification hash
        Engine->>DB: Insert record into appointments table
    end
    rect rgb(239, 246, 255)
        Note over PDF: Dynamic Multi-Tenant A4 PDF Generation
        PDF->>DB: Fetch hospital medical crest logo bytes & brand theme color
        PDF->>PDF: Render A4 header with hospital medical crest & brand banner
        PDF->>PDF: Render high-visibility Known Allergies alert banner
        PDF->>PDF: Generate QR Code 1: Reception / Doctor Desk Check-in
        PDF->>PDF: Generate QR Code 2: Live WhatsApp Queue Tracker
    end
    PDF-->>Bot: High-resolution PDF byte stream
    Bot->>WA: Dispatches PDF document file directly into chat
    WA-->>Patient: Receives OP Case Sheet PDF + token summary (#04)"""),

        ("Diagram 5: Calibrated Reminder Engine & 'Next Available Slot' Rescheduling",
         "Sequence: Asia/Kolkata Cron runs every 60s -> T-60m: Sends 1h reminder & sets reminded_1h=true -> T-30m: Sends 30m reminder & sets reminded_30m=true -> Slot ends: Status is CONFIRMED (no-show) -> System marks CANCELLED, sets missed_reschedule_sent=true, and sends exactly ONE polite notice -> Patient texts 'RESCHEDULE' -> Bot checks doctor's 7-day working schedule and remaining quota -> Presents 'Next Available Slots' menu with real-time badges (e.g. 🟢 14 slots) -> Patient replies '1' -> Re-books next sequential token (#01) on new date and issues fresh PDF slip.",
         """sequenceDiagram
    autonumber
    actor Patient as WhatsApp Patient
    participant Sched as AppointmentReminderService (Asia/Kolkata)
    participant Meta as Meta WhatsApp Cloud API
    participant Bot as Webhook Controller
    participant Roster as Live Roster & Capacity Engine
    participant DB as Supabase PostgreSQL
    participant Mgr as Hospital Manager Portal

    Note over Sched,DB: Calibrated Cron Job runs every 60 seconds (IST)
    rect rgb(254, 249, 195)
        Note over Sched,Meta: Timed Pre-Appointment Notifications
        Sched->>DB: Query appointments where slot - now <= 60m AND reminded_1h = false
        Sched->>Meta: Dispatches 1-Hour Reminder ("Appointment in ~1 hour")
        Sched->>DB: Update reminded_1h = true
        Sched->>DB: Query appointments where slot - now <= 30m AND reminded_30m = false
        Sched->>Meta: Dispatches 30-Min Reminder ("Appointment in ~30 mins")
        Sched->>DB: Update reminded_30m = true
    end
    rect rgb(254, 242, 242)
        Note over Sched,DB: Single Post-Slot Reschedule Notice (Zero Spam)
        Sched->>DB: Appointment slot ended & status = CONFIRMED & missed_reschedule_sent = false
        Sched->>DB: Update appointment status to CANCELLED (Slot freed back to quota)
        Sched->>DB: Update missed_reschedule_sent = true
        Sched->>Meta: Send single notice: "Missed your slot? Reply RESCHEDULE to rebook"
        DB-->>Mgr: Live Manager Portal updates (Old slot turns red: CANCELLED)
    end
    Patient->>Meta: Replies "RESCHEDULE"
    Meta->>Bot: Webhook Event received
    rect rgb(240, 253, 244)
        Note over Bot,Roster: Next Available Slots Verification (No Patient Guesswork)
        Bot->>Roster: Scan doctor's roster for next 7 days
        Roster->>DB: Check doctor.isAvailableOnDay() & remaining capacity
        DB-->>Roster: Day 1 (Full: 30/30), Day 2 (Available: 16/30), Day 3 (Available: 10/30)
        Roster-->>Bot: Filtered Available Slots
        Bot->>Meta: Send Interactive Slots Menu ("1. Tomorrow - 🟢 14 slots, 2. Thursday - 🟢 20 slots")
        Meta-->>Patient: Receives available slot options
    end
    Patient->>Meta: Replies "1"
    Meta->>Bot: Slot selected
    Bot->>DB: Allocate next sequential integer token (#01) on new date
    Bot->>Meta: Dispatches fresh digital appointment slip PDF
    Meta-->>Patient: "✅ Rescheduled! Your new token is #01 for Tomorrow."
    DB-->>Mgr: Live Manager Dashboard displays new appointment under tomorrow's schedule""")
    ]

    for d_title, d_desc, d_code in diagrams:
        h2 = doc.add_heading(d_title, level=2)
        h2.runs[0].font.color.rgb = PRIMARY
        
        p = doc.add_paragraph()
        p.add_run(d_desc)
        
        # Code Box for Mermaid Diagram
        code_tbl = doc.add_table(rows=1, cols=1)
        code_tbl.alignment = WD_TABLE_ALIGNMENT.CENTER
        cell = code_tbl.cell(0, 0)
        set_cell_background(cell, "0F172A") # Dark Navy background for code
        set_cell_margins(cell, top=100, bottom=100, left=150, right=150)
        
        cp = cell.paragraphs[0]
        crun = cp.add_run(d_code)
        crun.font.name = "Consolas"
        crun.font.size = Pt(8.5)
        crun.font.color.rgb = RGBColor(226, 232, 240)
        
        doc.add_paragraph()

    # =========================================================================
    # SECTION 3: 2-MEMBER COLLABORATIVE TESTING FRAMEWORK
    # =========================================================================
    h1 = doc.add_heading("3. Dual-Tester Operational Framework & Roles", level=1)
    h1.runs[0].font.color.rgb = NAVY

    p = doc.add_paragraph()
    p.add_run("Because MediAssist operates as a bidirectional ecosystem between medical facilities and patients, end-to-end verification requires testing both perspectives simultaneously. The testing team is structured into two complementary roles:")

    role_tbl = doc.add_table(rows=1, cols=4)
    role_tbl.alignment = WD_TABLE_ALIGNMENT.CENTER
    hdrs = role_tbl.rows[0].cells
    hdrs[0].text = "Role & Assigned Lead"
    hdrs[1].text = "Target Personas"
    hdrs[2].text = "Primary Interfaces & Devices"
    hdrs[3].text = "Core Responsibilities"
    for c in hdrs:
        set_cell_background(c, "0066FF")
        c.paragraphs[0].runs[0].font.color.rgb = RGBColor(255, 255, 255)
        c.paragraphs[0].runs[0].font.bold = True

    roles_data = [
        ("Tester 1: Hospital Operations & Admin Lead", 
         "Super Admin, Hospital Applicant, Hospital Manager, OPD Receptionist, Doctor Consultation Desk", 
         "Desktop / Laptop (Chrome / Edge / Firefox)\n• /apply.html\n• /admin-portal.html\n• /manager-portal.html\n• /hospital.html?id=X", 
         "Hospital application submission with GPS autofill; Super Admin self-healing approval; Branding, color theme & crest logo configuration; Doctor roster & token limit configuration; Walk-in OPD registration; Reception QR check-in; Calling patients & generating dynamic Session PINs; Monitoring live queue state changes upon cancellations & reschedules."),

        ("Tester 2: End-User Patient & WhatsApp Lead", 
         "First-Time Unregistered Patient, Verified OPD Patient, Chronic Patient, Family Caregiver", 
         "Physical Mobile Phone\n• WhatsApp Messenger App\n• Mobile Browser (/register.html)\n• Mobile Browser (/my-records.html)", 
         "Unregistered bot greeting & link generation; Mobile demographic registration & WhatsApp OTP verification; Conversational & Express OPD booking; A4 Case Sheet PDF visual audit (crest, brand bar, allergy banner, dual QR); Live queue checking ('queue'); Consultation Session PIN reception; Next Available Slots rescheduling; Multilingual NLP & voice notes; Gemini AI prescription photo parsing & dose reminders; Medical locker record review.")
    ]

    for r_title, personas, ifaces, duties in roles_data:
        row_cells = role_tbl.add_row().cells
        row_cells[0].text = r_title
        row_cells[0].paragraphs[0].runs[0].font.bold = True
        row_cells[1].text = personas
        row_cells[2].text = ifaces
        row_cells[3].text = duties
        for c in row_cells:
            set_cell_margins(c, top=80, bottom=80, left=100, right=100)

    doc.add_paragraph()

    # =========================================================================
    # SECTION 4: SYNCHRONIZED 6-PHASE TIMELINE
    # =========================================================================
    h1 = doc.add_heading("4. Synchronized 6-Phase Collaboration Timeline", level=1)
    h1.runs[0].font.color.rgb = NAVY

    p = doc.add_paragraph()
    p.add_run("The following 6-phase sequence coordinates live interactions between Tester 1 and Tester 2, simulating realistic outpatient hospital operations:")

    timeline_data = [
        ("Phase 1: Setup & Onboarding (10 mins)",
         "Tester 1 applies for a new hospital with GPS detection, approves it as Super Admin, and configures brand colors, crest logo, and Dr. Rajesh Mohapatra in the Manager Portal.",
         "Tester 2 texts 'Hi' to WhatsApp, receives personal registration link, fills mobile demographics (Blood group O+, Allergies: Penicillin, Peanuts), and verifies 6-digit WhatsApp OTP."),
        
        ("Phase 2: OPD Booking & PDF Slip Audit (8 mins)",
         "Tester 1 monitors the live Appointments table in the Manager Portal for incoming consultations.",
         "Tester 2 books an appointment with Dr. Rajesh Mohapatra on WhatsApp, receives Token #01, and audits the generated A4 PDF (crest, color, allergy badge, dual QR)."),
        
        ("Phase 3: Clinic Queue & Session PIN (7 mins)",
         "Tester 1 simulates patient arrival: clicks 'Check In' (status -> CHECKED_IN), then clicks 'Call Patient' to start consultation (status -> IN_CONSULTATION).",
         "Tester 2 checks live position via 'queue' command, then immediately receives high-priority WhatsApp alert: 'Dr. Rajesh Mohapatra is ready in Room 104! Session PIN: XXXX'."),
        
        ("Phase 4: Rescheduling & Slot Release (10 mins)",
         "Tester 1 watches the Manager Portal as old slot row immediately updates with a red CANCELLED badge and the doctor's quota counter decrements.",
         "Tester 2 books Token #02, replies 'RESCHEDULE', receives the verified 'Next Available Slots' menu with live token counts, picks slot '1', and receives new Token #01 for tomorrow."),
        
        ("Phase 5: AI Vision & Medication Reminders (8 mins)",
         "Tester 1 verifies doctor consultation records and prescription history in the database.",
         "Tester 2 uploads a prescription photograph to WhatsApp, verifies Gemini 2.0 Flash VLM extracts drug dosages, and tests interactive WhatsApp reminder buttons: [Taken] and [Snooze 15m]."),
        
        ("Phase 6: Concurrency & Quota Stress Test (7 mins)",
         "Tester 1 prepares a walk-in token at the hospital reception counter for Dr. Rajesh Mohapatra.",
         "Tester 2 prepares to book the same doctor via WhatsApp. Both submit at the exact same second. Passes if tokens are sequentially distinct (#02 and #03) with zero database collision.")
    ]

    t_tbl = doc.add_table(rows=1, cols=3)
    t_tbl.alignment = WD_TABLE_ALIGNMENT.CENTER
    thdrs = t_tbl.rows[0].cells
    thdrs[0].text = "Phase & Duration"
    thdrs[1].text = "Tester 1 Actions (Hospital Desk)"
    thdrs[2].text = "Tester 2 Actions (Patient Phone)"
    for c in thdrs:
        set_cell_background(c, "0F172A")
        c.paragraphs[0].runs[0].font.color.rgb = RGBColor(255, 255, 255)
        c.paragraphs[0].runs[0].font.bold = True

    for p_name, t1_act, t2_act in timeline_data:
        row_cells = t_tbl.add_row().cells
        row_cells[0].text = p_name
        row_cells[0].paragraphs[0].runs[0].font.bold = True
        row_cells[1].text = t1_act
        row_cells[2].text = t2_act
        for c in row_cells:
            set_cell_margins(c, top=80, bottom=80, left=100, right=100)

    doc.add_paragraph()

    # =========================================================================
    # SECTION 5: COMPLETE 14-TRACK TESTING MATRIX (EXHAUSTIVE)
    # =========================================================================
    h1 = doc.add_heading("5. Exhaustive 14-Track Master Testing Matrix", level=1)
    h1.runs[0].font.color.rgb = NAVY

    p = doc.add_paragraph()
    p.add_run("The following comprehensive test catalog covers every single button, endpoint, webhook branch, background job, clinical guardrail, and security filter in the MediAssist application:")

    tracks = [
        ("Track 1: Hospital Onboarding, Location Pin & Multi-Tenant Branding", [
            ("1.1", "Hospital Self-Service Application Submission", "/apply.html", "Click 'Detect My Location' (verify GPS autofills); Enter name 'City Care Hospital', 250 beds, Cardiology/Neurology, phone, email; Submit.", "Success alert shown. Application inserted into Supabase PostgreSQL with status PENDING_REVIEW.", "Tester 1"),
            ("1.2", "Super Admin Review & Self-Healing Approval", "/admin-portal.html", "Login as admin@mediassist.com / admin123; View pending table; Click 'Approve'.", "Hospital status -> ACTIVE; HOSPITAL_MANAGER user created; name and fullName mapped; temporary password generated; welcome email sent; credentials modal displayed.", "Tester 1"),
            ("1.3", "Super Admin Credentials Resend", "/admin-portal.html", "Click 'Resend Credentials' button on approved hospital row.", "Toast confirms fresh password generated and welcome email re-dispatched.", "Tester 1"),
            ("1.4", "Manager Branding, Colors & Tagline Configuration", "/manager-portal.html", "Login with manager credentials; In Settings, set Brand Accent Color to #0284c7, tagline, 24x7 emergency helpline, and footer note; Save.", "Hospital branding updated in database and reflected across manager portal.", "Tester 1"),
            ("1.5", "Hospital Medical Crest Upload & Live Preview", "/manager-portal.html", "Click 'Choose File' under Hospital Logo; select transparent PNG/JPG; click 'Preview Sample OP Case Sheet'.", "Logo auto-compresses and saves immediately; sample A4 PDF opens with the newly uploaded crest on top-right and brand banner on header.", "Tester 1"),
            ("1.6", "Doctor Scheduling & Daily Quota Configuration", "/manager-portal.html", "In Doctor Management, add 'Dr. Rajesh Mohapatra', Cardiology, MD DM, Room 104, Fee ₹500, Daily Limit 25, check Mon-Fri.", "Doctor card appears in roster with Active toggle enabled.", "Tester 1"),
            ("1.7", "Public Hospital Microsite Verification", "/hospital.html?id=X", "Open microsite using hospital ID.", "Page adopts hospital brand color and crest; displays doctor roster, beds, and 'Book Token on WhatsApp' button with QR code.", "Tester 1")
        ]),

        ("Track 2: Patient Registration, Demographics & WhatsApp OTP Verification", [
            ("2.1", "First-Time Greeting & Unregistered Redirection", "WhatsApp Chat", "Send 'Hi' or 'Hello' from an unregistered phone number.", "Bot detects no record in patient_profiles and dispatches 1-time personal registration link: /register.html?phone=...", "Tester 2"),
            ("2.2", "Demographic Registration Form & Calendar DOB", "/register.html", "Open link on mobile; enter Full Name 'Yagnish Anupam', Male, Blood Group 'O+', Allergies 'Penicillin, Peanuts', select DOB via calendar picker.", "Fields validate properly; phone is locked to preventing tampering.", "Tester 2"),
            ("2.3", "WhatsApp OTP Generation & Dispatch", "/register.html", "Click 'Send WhatsApp OTP'.", "6-digit verification code arrives in WhatsApp chat within 3 seconds; AuditLogService logs OTP_REQUESTED.", "Tester 2"),
            ("2.4", "Invalid OTP Negative Test", "/register.html", "Enter '000000' and click 'Complete Registration'.", "Error banner: 'Invalid OTP, please try again'; attempt counter decrements; AuditLogService logs OTP_FAILED.", "Tester 2"),
            ("2.5", "Valid OTP Verification & Account Activation", "/register.html", "Enter correct 6-digit OTP and click 'Complete Registration'.", "Profile saved into patient_profiles table; WhatsApp receives green welcome confirmation with Quick Action buttons [⚡ Instant OPD Token] and [📁 Medical Locker].", "Tester 2"),
            ("2.6", "Profile Summary Verification", "WhatsApp Chat", "Send 'my profile' in WhatsApp.", "Bot outputs formatted demographic summary (Name, Age, Gender, Blood Group, Allergies).", "Tester 2")
        ]),

        ("Track 3: OPD Appointment Booking & High-Res Case Sheet PDF", [
            ("3.1", "Conversational OPD Booking", "WhatsApp Chat", "Tap [⚡ Instant OPD Token] or send 'book opd'.", "Bot presents active hospitals and doctor roster for Cardiology.", "Tester 2"),
            ("3.2", "Deterministic Sequential Token Allocation", "WhatsApp Chat", "Reply '1' to select Dr. Rajesh Mohapatra.", "System calculates next sequential integer (#01 or #02), locks token, generates unique SHA-256 hash, and inserts into appointments.", "Tester 2"),
            ("3.3", "A4 OP Case Sheet PDF Inspection", "WhatsApp Chat", "Download and open the generated PDF slip file.", "Verifies: 1) Left MediAssist emblem; 2) Right Hospital crest; 3) Brand color banner; 4) Prominent Token Badge; 5) Patient Name, Computed Age, Gender, Blood Group; 6) Red Allergy Alert Banner (Penicillin, Peanuts); 7) Dual QR codes.", "Tester 2 & 1"),
            ("3.4", "Reception Desk Walk-In Registration", "/manager-portal.html", "In Manager Portal, click 'Walk-in OPD Registration', enter patient phone, select doctor, submit.", "Instant token generated sequentially without colliding with online WhatsApp tokens.", "Tester 1")
        ]),

        ("Track 4: Queue Management, Doctor Consultation & Session PIN", [
            ("4.1", "Live Queue Position Query", "WhatsApp Chat", "Send 'queue' in WhatsApp.", "Bot replies: '🎫 Your Token: #X | Current Serving: #Y | Patients Ahead: Z | Est. Wait: ~W mins'.", "Tester 2"),
            ("4.2", "Reception QR Check-In", "/manager-portal.html", "Receptionist scans QR Code 1 on case sheet (or clicks 'Check In' in portal).", "Appointment status transitions from CONFIRMED to CHECKED_IN; row updates live.", "Tester 1"),
            ("4.3", "Doctor Calling Patient & Session PIN Dispatch", "/manager-portal.html", "Doctor clicks 'Call Patient' (or 'Start Consultation') on the patient row.", "Status updates to IN_CONSULTATION; WhatsApp dispatches high-priority alert to patient: 'Dr. Rajesh Mohapatra is ready in Room 104! Consultation Session PIN: XXXX'.", "Tester 1 & 2"),
            ("4.4", "Next Patient Queue Advance Alert", "WhatsApp Chat", "Observe WhatsApp of the patient who holds the subsequent token.", "Next patient automatically receives notification: 'You are next in queue! Please report near Room 104'.", "Tester 2"),
            ("4.5", "Complete Consultation", "/manager-portal.html", "Doctor clicks 'Complete Consultation'.", "Status updates to COMPLETED; patient drops out of active waiting queue.", "Tester 1")
        ]),

        ("Track 5: Calibrated Reminder Engine & Next Available Slots Rescheduling", [
            ("5.1", "Strict 1-Hour Reminder Trigger (IST)", "Background Cron", "Appointment is within 60 minutes and reminded_1h is false.", "Scheduler (Asia/Kolkata) dispatches 1-hour reminder WhatsApp alert; reminded_1h set to true.", "Tester 2"),
            ("5.2", "Strict 30-Minute Reminder Trigger (IST)", "Background Cron", "Appointment is within 30 minutes and reminded_30m is false.", "Scheduler dispatches 30-minute reminder WhatsApp alert; reminded_30m set to true; zero repeat alerts occur.", "Tester 2"),
            ("5.3", "Missed Appointment Single Reschedule Notice", "Background Cron", "Appointment slot passes, status remains CONFIRMED (no-show), and missed_reschedule_sent is false.", "Status updated to CANCELLED (slot freed back to quota); missed_reschedule_sent set to true; exactly ONE polite notice sent: '🗓️ Missed Your Appointment? Reply RESCHEDULE to choose a new slot'; all further alarms permanently cease.", "Tester 2 & 1"),
            ("5.4", "Live Roster Next Available Slots Picker", "WhatsApp Chat", "Patient replies 'RESCHEDULE'.", "Bot releases previous slot and scans doctor's weekly roster (doc.isAvailableOnDay) and quota limits for the next 7 days; displays interactive list with live availability badges (e.g. '1. Tomorrow - 🟢 24 slots left').", "Tester 2"),
            ("5.5", "1-Tap Reschedule Re-Booking", "WhatsApp Chat", "Patient replies '1'.", "New sequential token (#01) allocated for new date; fresh QR code generated; new PDF slip dispatched; Manager Portal reflects new booking on tomorrow's schedule and shows old slot as CANCELLED.", "Tester 2 & 1")
        ]),

        ("Track 6: AI Vision Prescription Scanner & Medication Dose Reminders", [
            ("6.1", "Prescription Image Upload & VLM Extraction", "WhatsApp Chat", "Send a photograph of a prescription (e.g. Amoxicillin 500mg twice daily after food for 5 days).", "Gemini 2.0 Flash VLM parses drug name, dosage, schedule, and meal instruction; bot confirms schedule and inserts records into medication_reminders.", "Tester 2"),
            ("6.2", "Interactive Dose Reminder Alert", "WhatsApp Chat", "Medication reminder time triggers.", "WhatsApp message arrives with interactive buttons: [✅ Taken], [⏰ Snooze 15m], [⏰ Snooze 30m].", "Tester 2"),
            ("6.3", "Snooze 15m Button Action", "WhatsApp Chat", "Tap [⏰ Snooze 15m].", "Bot confirms: 'Dose snoozed for 15 minutes'; reminder re-fires after 15 minutes.", "Tester 2"),
            ("6.4", "Taken Button Action & Adherence Logging", "WhatsApp Chat", "Tap [✅ Taken].", "Bot records dose adherence in database: 'Great! Dose marked as taken at [Time]'.", "Tester 2")
        ]),

        ("Track 7: Patient Medical Locker (/my-records.html)", [
            ("7.1", "Medical Locker Link Dispatch", "WhatsApp Chat", "Send 'locker' or 'records' in WhatsApp.", "Bot returns secure link: https://mediassist-1hdl.onrender.com/my-records.html?phone=...", "Tester 2"),
            ("7.2", "Past OPD Appointments Review & Slip Download", "/my-records.html", "Open locker in browser; click 'OPD Appointments' tab.", "Lists all past and upcoming visits with doctor name, date, token number, and one-click 'Download PDF' buttons.", "Tester 2"),
            ("7.3", "Active Prescriptions & Lab Reports", "/my-records.html", "Click 'Prescriptions' and 'Lab Reports' tabs.", "Displays active drug dosage schedules with adherence history and uploaded diagnostic reports.", "Tester 2")
        ]),

        ("Track 8: Clinical Safety & Emergency Red-Flag Triage", [
            ("8.1", "Cardiac Emergency Detection", "WhatsApp Chat", "Send: 'My father has severe chest pain and difficulty breathing'.", "Gemini AI detects emergency red flag; aborts regular booking; dispatches urgent alert: '🚨 MEDICAL EMERGENCY: Please call 108 or visit nearest ER immediately!' with emergency helpline contacts.", "Tester 2"),
            ("8.2", "Trauma / Severe Bleeding Detection", "WhatsApp Chat", "Send: 'Profuse bleeding from accident injury'.", "Immediate priority emergency triage response dispatched in < 2 seconds.", "Tester 2"),
            ("8.3", "Unregistered Emergency Override", "WhatsApp Chat", "Send 'emergency ambulance' from an unregistered phone number.", "Emergency triage sent immediately without redirecting to /register.html.", "Tester 2")
        ]),

        ("Track 9: Dependent & Family Member Booking", [
            ("9.1", "Switch to Dependent Patient Profile", "WhatsApp Chat", "Send: 'Book for my mother Sunita Devi'.", "Bot responds: '👤 Patient Profile Set: Sunita Devi. All subsequent OPD tokens will be issued in Sunita Devi's name.'", "Tester 2"),
            ("9.2", "Dependent Case Sheet PDF Verification", "WhatsApp Chat", "Book an appointment following dependent selection.", "Downloaded PDF slip displays patient name as 'Sunita Devi' (not the WhatsApp account holder's name).", "Tester 2 & 1"),
            ("9.3", "Explicit Patient Prefix Parsing", "WhatsApp Chat", "Send: 'patient name: Aarav Sharma'.", "Bot switches booking context and confirms dependent name updated to Aarav Sharma.", "Tester 2")
        ]),

        ("Track 10: Multilingual NLP & Audio Voice Notes", [
            ("10.1", "Language Selection Trigger (Hindi)", "WhatsApp Chat", "Send 'Hindi' or 'हिंदी' or 'bhasha'.", "Bot responds with language selection menu and adapts subsequent prompts into Hindi.", "Tester 2"),
            ("10.2", "Language Selection Trigger (Bengali)", "WhatsApp Chat", "Send 'Bengali' or 'বাংলা'.", "Bot adapts prompts into Bengali.", "Tester 2"),
            ("10.3", "WhatsApp Voice Note Audio Triage", "WhatsApp Chat", "Record and send a 5-second audio note: 'I have severe headache and nausea since yesterday'.", "Backend offloads audio to Gemini 2.0 Flash Multimodal; transcribes speech; identifies symptoms; recommends General Medicine or Neurology.", "Tester 2")
        ]),

        ("Track 11: Direct Cancellation & Natural Language Locker Retrieval", [
            ("11.1", "Direct WhatsApp Cancellation", "WhatsApp Chat", "Send 'CANCEL' or 'cancel appointment'.", "Active appointment marked CANCELLED; token released back to doctor quota; cancellation confirmation sent.", "Tester 2"),
            ("11.2", "Real-Time Manager UI Update on Cancel", "/manager-portal.html", "Observe Appointments table when patient texts 'CANCEL'.", "Row status updates to CANCELLED (red badge) without requiring page reload.", "Tester 1"),
            ("11.3", "Natural Language Report Retrieval", "WhatsApp Chat", "Send: 'Send me my blood test report'.", "ReportWardrobeService searches medical_documents by keyword and sends matching report title and AI summary in chat.", "Tester 2")
        ]),

        ("Track 12: Security Hardening, Rate Limiting & Brute Force Defense", [
            ("12.1", "OTP Request Flood Protection", "/register.html", "Click 'Send WhatsApp OTP' 4 times in rapid succession.", "4th attempt blocked with HTTP 429: 'Too many OTP requests. Please wait 5 minutes.'; AuditLogService logs OTP_RATE_LIMITED.", "Tester 2"),
            ("12.2", "Portal Login Brute Force Defense", "/admin-portal.html", "Enter incorrect password 5 consecutive times.", "LoginAttemptService triggers lockout defense, blocking further login attempts from that IP.", "Tester 1"),
            ("12.3", "Session Token Tampering Defense", "/my-records.html", "Modify phone parameter in URL to an unverified number.", "Access denied; patient records are protected from unauthorized cross-tenant browsing.", "Tester 2")
        ]),

        ("Track 13: Administrative Controls, Rejections & Doctor Inactivity", [
            ("13.1", "Hospital Application Rejection", "/admin-portal.html", "Super Admin clicks 'Reject' on an invalid application.", "Hospital status transitions to REJECTED; login credentials blocked; application moves to Rejected tab.", "Tester 1"),
            ("13.2", "Doctor Availability Toggle (Inactive)", "/manager-portal.html", "Flip Dr. Rajesh Mohapatra toggle to Inactive (OFF).", "Tester 2 attempts to book on WhatsApp; doctor is omitted from the available doctor list.", "Tester 1 & 2"),
            ("13.3", "Zero Available Days Fallback", "/manager-portal.html", "Uncheck all available working days for a doctor.", "Bot detects zero working days and informs patient that doctor is currently on leave.", "Tester 1 & 2")
        ]),

        ("Track 14: Non-Medical Image Upload & Negative AI Triage", [
            ("14.1", "Non-Prescription Image Rejection", "WhatsApp Chat", "Upload a photo of a coffee cup, landscape, or pet.", "Gemini Vision AI inspects image, determines it is non-medical, and replies: '⚠️ This does not appear to be a medical prescription. Please upload a clear photo of your prescription.' (No erroneous reminders scheduled).", "Tester 2")
        ])
    ]

    for track_title, test_cases in tracks:
        h2 = doc.add_heading(track_title, level=2)
        h2.runs[0].font.color.rgb = PRIMARY
        
        matrix_tbl = doc.add_table(rows=1, cols=6)
        matrix_tbl.alignment = WD_TABLE_ALIGNMENT.CENTER
        m_hdrs = matrix_tbl.rows[0].cells
        m_hdrs[0].text = "ID"
        m_hdrs[1].text = "Scenario"
        m_hdrs[2].text = "Target"
        m_hdrs[3].text = "Step-by-Step Action"
        m_hdrs[4].text = "Expected Result"
        m_hdrs[5].text = "Tester"
        for c in m_hdrs:
            set_cell_background(c, "0066FF")
            c.paragraphs[0].runs[0].font.color.rgb = RGBColor(255, 255, 255)
            c.paragraphs[0].runs[0].font.bold = True

        for tid, scen, tgt, act, exp, t_assign in test_cases:
            row_cells = matrix_tbl.add_row().cells
            row_cells[0].text = tid
            row_cells[0].paragraphs[0].runs[0].font.bold = True
            row_cells[1].text = scen
            row_cells[2].text = tgt
            row_cells[3].text = act
            row_cells[4].text = exp
            row_cells[5].text = t_assign
            row_cells[5].paragraphs[0].runs[0].font.bold = True
            for c in row_cells:
                set_cell_margins(c, top=60, bottom=60, left=80, right=80)

        doc.add_paragraph()

    # =========================================================================
    # SECTION 6: PRODUCTION ENGINEERING & SOLVED ARCHITECTURAL ISSUES
    # =========================================================================
    h1 = doc.add_heading("6. Production Hardening & Solved Architectural Issues", level=1)
    h1.runs[0].font.color.rgb = NAVY

    fixes = [
        ("Supabase Connection Pooler Read-Only Contamination",
         "Symptom: Intermittent 'ERROR: cannot execute INSERT in a read-only transaction' when saving hospital applications or users.\n"
         "Root Cause: Transaction pooler (port 6543) pooled connections inherited read-only state from earlier read transactions.\n"
         "Permanent Fix: Reconfigured HikariCP in DatabaseConfig.java with config.setReadOnly(false) and config.setConnectionInitSql('SET default_transaction_read_only = off;'), ensuring all pooled sessions explicitly permit writes."),

        ("Database Schema Column Mismatch (users.name NOT NULL)",
         "Symptom: Super Admin approval endpoint threw HTTP 500 Internal Server Error when creating hospital manager accounts.\n"
         "Root Cause: Supabase database had a legacy column 'name' marked as NOT NULL, while JPA entity User.java only mapped 'fullName', resulting in null-value constraint violations on Hibernate inserts.\n"
         "Permanent Fix: Dropped the NOT NULL constraint on users.name in PostgreSQL, mapped both 'name' and 'fullName' in User.java with synchronized getters/setters, and added automated idempotent migrations in DatabaseConfig.java."),

        ("Meta WhatsApp Webhook Timeout Drops (<25ms Async Engine)",
         "Symptom: Meta marked incoming webhooks as dropped/retried if AI processing or database queries took longer than 3 seconds.\n"
         "Permanent Fix: Refactored WhatsAppWebhookController.java to process incoming payloads asynchronously using CompletableFuture.runAsync, returning an immediate HTTP 200 OK to Meta in < 25ms."),

        ("Timezone Calibration (UTC vs IST Asia/Kolkata)",
         "Symptom: Appointment reminders repeatedly fired at night on Render because the server evaluated time in UTC against evening appointment slots.\n"
         "Permanent Fix: Calibrated all scheduler computations in AppointmentReminderService.java to ZoneId.of('Asia/Kolkata') and bound status to persistent PostgreSQL columns (reminded_1h, reminded_30m, missed_reschedule_sent)."),

        ("Multi-Tenant Case Sheet Logo Isolation",
         "Symptom: Manager preview of OP Case Sheet displayed a default crest instead of the newly uploaded hospital crest.\n"
         "Root Cause: /sample-pdf was hardcoded to fetch the last appointment across all hospitals, and preview clicks did not pass hospital context.\n"
         "Permanent Fix: Added ?hospitalId=X support to /sample-pdf, wired manager portal preview button to supply current hospital ID, implemented immediate auto-save on logo selection, and bypassed in-memory PDF cache with buildFreshPdf().")
    ]

    for f_title, f_desc in fixes:
        p = doc.add_paragraph()
        p.add_run(f"• {f_title}:\n").bold = True
        p.add_run(f_desc)

    doc.add_paragraph()

    # =========================================================================
    # SECTION 7: DATABASE SCHEMA REFERENCE
    # =========================================================================
    h1 = doc.add_heading("7. Database Schema & Data Dictionary", level=1)
    h1.runs[0].font.color.rgb = NAVY

    schema_table = doc.add_table(rows=1, cols=3)
    schema_table.alignment = WD_TABLE_ALIGNMENT.CENTER
    s_hdrs = schema_table.rows[0].cells
    s_hdrs[0].text = "Table Name"
    s_hdrs[1].text = "Columns, Data Types & Constraints"
    s_hdrs[2].text = "Functional Purpose"
    for c in s_hdrs:
        set_cell_background(c, "0066FF")
        c.paragraphs[0].runs[0].font.color.rgb = RGBColor(255, 255, 255)
        c.paragraphs[0].runs[0].font.bold = True

    tables_data = [
        ("hospitals", "id (BIGSERIAL PK), name (VARCHAR), registration_number (VARCHAR), address (TEXT), city (VARCHAR), state (VARCHAR), phone (VARCHAR), contact_person_name (VARCHAR), contact_person_email (VARCHAR), contact_person_phone (VARCHAR), specialties (VARCHAR[]), number_of_beds (INT), brand_color (VARCHAR), logo_url (TEXT), tagline (VARCHAR), emergency_phone (VARCHAR), custom_footer_notes (TEXT), status (VARCHAR: ACTIVE, PENDING_REVIEW, REJECTED), applied_at (TIMESTAMP), reviewed_at (TIMESTAMP)", "Multi-tenant hospital facilities, clinical metadata, and custom branding."),
        ("doctors", "id (BIGSERIAL PK), hospital_id (FK), name (VARCHAR), department (VARCHAR), qualification (VARCHAR), room_number (VARCHAR), consultation_fee (DOUBLE), daily_token_limit (INT), available_days (VARCHAR[]), is_available (BOOLEAN)", "Specialist doctor profiles, room allocations, consultation fees, and daily token limits."),
        ("users", "id (BIGSERIAL PK), email (VARCHAR UNIQUE), password (VARCHAR BCrypt), name (VARCHAR), full_name (VARCHAR), phone_number (VARCHAR), role (VARCHAR: SUPER_ADMIN, HOSPITAL_MANAGER), hospital_id (FK), active (BOOLEAN), must_change_password (BOOLEAN)", "Administrative and manager user credentials with role-based access control."),
        ("appointments", "id (BIGSERIAL PK), hospital_id (FK), doctor_id (FK), patient_phone (VARCHAR), patient_name (VARCHAR), appointment_date (DATE), time_slot (VARCHAR), serial_number (INT), qr_code_token (VARCHAR UNIQUE), status (VARCHAR: CONFIRMED, CHECKED_IN, IN_CONSULTATION, COMPLETED, CANCELLED), reminded_1h (BOOLEAN), reminded_30m (BOOLEAN), missed_reschedule_sent (BOOLEAN), reminded_24h (BOOLEAN)", "Live appointment bookings, deterministic integer token sequences (#01, #02), and notification state."),
        ("patient_profiles", "phone_number (VARCHAR PK), full_name (VARCHAR), age (INT), gender (VARCHAR), blood_group (VARCHAR), known_allergies (TEXT), emergency_contact (VARCHAR), is_profile_complete (BOOLEAN)", "Verified patient demographics and allergy records rendered on OP Case Sheets."),
        ("patient_sessions", "phone_number (VARCHAR PK), latitude (DOUBLE), longitude (DOUBLE), preferred_department (VARCHAR), updated_at (TIMESTAMP)", "Transient GPS coordinates and department search preferences for location discovery."),
        ("medication_reminders", "id (BIGSERIAL PK), patient_phone (VARCHAR), medicine_name (VARCHAR), dosage_instruction (VARCHAR), reminder_time (VARCHAR), active (BOOLEAN), created_at (TIMESTAMP)", "Prescription dose schedules extracted by Gemini 2.0 Flash VLM."),
        ("medical_documents", "id (BIGSERIAL PK), patient_phone (VARCHAR), title (VARCHAR), document_url (TEXT), document_type (VARCHAR), uploaded_at (TIMESTAMP)", "Patient Medical Locker diagnostic reports and historical case sheets.")
    ]

    for t_name, cols, purpose in tables_data:
        row_cells = schema_table.add_row().cells
        row_cells[0].text = t_name
        row_cells[0].paragraphs[0].runs[0].font.bold = True
        row_cells[1].text = cols
        row_cells[2].text = purpose
        for c in row_cells:
            set_cell_margins(c, top=70, bottom=70, left=90, right=90)

    doc.add_paragraph()

    # =========================================================================
    # SECTION 8: REST API SPECIFICATION CATALOG
    # =========================================================================
    h1 = doc.add_heading("8. REST API Specification Catalog", level=1)
    h1.runs[0].font.color.rgb = NAVY

    apis = [
        ("POST /api/v1/apply", "Public endpoint to submit hospital self-service application with GPS coordinates."),
        ("POST /api/v1/auth/login", "Portal authentication for Super Admins and Hospital Managers returning JWT session."),
        ("GET /api/v1/admin/applications", "Super Admin endpoint to list all hospital applications filtered by status."),
        ("POST /api/v1/admin/applications/{id}/approve", "Self-healing approval creating manager, setting credentials, and emailing applicant."),
        ("POST /api/v1/admin/applications/{id}/resend-credentials", "Regenerates credentials and dispatches welcome email on demand."),
        ("POST /api/v1/admin/applications/{id}/reject", "Rejects hospital application with administrative reason."),
        ("GET /api/v1/hospital-manager/my-hospital", "Returns authenticated manager's hospital profile, stats, and branding."),
        ("PUT /api/v1/hospital-manager/settings", "Updates hospital branding, tagline, emergency phone, footer notes, and colors."),
        ("POST /api/v1/hospital-manager/settings/logo", "Multipart file upload for hospital medical crest with auto-compression."),
        ("GET /api/v1/hospital-manager/doctors", "Returns doctor roster for authenticated manager's facility."),
        ("POST /api/v1/appointments/walk-in", "Issues instant reception desk walk-in OPD token with concurrency safety."),
        ("GET /api/v1/appointments/{id}/pdf", "Generates authentic A4 OP Case Sheet PDF with latest hospital branding."),
        ("GET /api/v1/appointments/sample-pdf?hospitalId={id}", "Previews branded case sheet for a specific hospital."),
        ("POST /api/v1/appointments/start-consultation", "Transitions patient to IN_CONSULTATION, creates session PIN, and notifies next in queue."),
        ("POST /api/v1/patient/otp/request", "Generates and dispatches 6-digit WhatsApp OTP with rate-limiting."),
        ("POST /api/v1/patient/otp/verify", "Verifies OTP and activates patient profile in patient_profiles table."),
        ("POST /api/v1/whatsapp/webhook", "Meta WhatsApp Cloud API bidirectional message handler (<25ms async handling).")
    ]

    for ep, desc in apis:
        p = doc.add_paragraph(style='List Bullet')
        p.add_run(f"{ep}: ").bold = True
        p.add_run(desc)

    doc.add_paragraph()

    # =========================================================================
    # SECTION 9: MASTER QA SIGN-OFF SCORECARD
    # =========================================================================
    h1 = doc.add_heading("9. Master QA Sign-off Scorecard", level=1)
    h1.runs[0].font.color.rgb = NAVY

    p = doc.add_paragraph()
    p.add_run("Upon completion of the testing protocol, both testers must review and sign off on all 14 functional tracks:")

    score_tbl = doc.add_table(rows=1, cols=5)
    score_tbl.alignment = WD_TABLE_ALIGNMENT.CENTER
    sc_hdrs = score_tbl.rows[0].cells
    sc_hdrs[0].text = "Track"
    sc_hdrs[1].text = "Functional Domain"
    sc_hdrs[2].text = "Lead"
    sc_hdrs[3].text = "Status"
    sc_hdrs[4].text = "Tester Signature / Notes"
    for c in sc_hdrs:
        set_cell_background(c, "0F172A")
        c.paragraphs[0].runs[0].font.color.rgb = RGBColor(255, 255, 255)
        c.paragraphs[0].runs[0].font.bold = True

    score_data = [
        ("Track 1", "Hospital Application & Multi-Tenant Branding", "Tester 1", "[  ] PASS", "Ready for deployment"),
        ("Track 2", "Patient Demographics & WhatsApp OTP Verification", "Tester 2", "[  ] PASS", "1-time registration enforced"),
        ("Track 3", "OPD Booking & Dual-QR A4 PDF Generation", "Both", "[  ] PASS", "PDFBox dynamic crest verified"),
        ("Track 4", "Queue Management & Doctor Desk Session PIN", "Both", "[  ] PASS", "Calling alert & PIN verified"),
        ("Track 5", "Calibrated IST Reminders & Slot Rescheduling", "Both", "[  ] PASS", "2-alert rule & next slots verified"),
        ("Track 6", "Gemini 2.0 Flash VLM Prescription Parsing", "Tester 2", "[  ] PASS", "Dosages & dose reminders active"),
        ("Track 7", "Patient Medical Records Locker (/my-records.html)", "Tester 2", "[  ] PASS", "Slip & report downloads active"),
        ("Track 8", "Clinical Safety & Emergency Red-Flag Triage", "Tester 2", "[  ] PASS", "Cardiac & trauma overrides working"),
        ("Track 9", "Dependent & Family Caregiver Booking", "Tester 2", "[  ] PASS", "Dependent name printed on slips"),
        ("Track 10", "Multilingual NLP & Audio Voice Notes", "Tester 2", "[  ] PASS", "Hindi/Bengali & audio notes active"),
        ("Track 11", "WhatsApp Cancellation & Wardrobe Retrieval", "Both", "[  ] PASS", "Real-time CANCELLED UI badge"),
        ("Track 12", "Security Rate-Limiting & Brute Force Defense", "Both", "[  ] PASS", "OTP limits & lockout working"),
        ("Track 13", "Administrative Controls & Doctor Inactivity", "Tester 1", "[  ] PASS", "Roster toggles working"),
        ("Track 14", "Non-Medical Image Rejection", "Tester 2", "[  ] PASS", "Negative AI triage functioning")
    ]

    for trk, dom, lead, stat, nts in score_data:
        row_cells = score_tbl.add_row().cells
        row_cells[0].text = trk
        row_cells[0].paragraphs[0].runs[0].font.bold = True
        row_cells[1].text = dom
        row_cells[2].text = lead
        row_cells[3].text = stat
        row_cells[3].paragraphs[0].runs[0].font.bold = True
        row_cells[4].text = nts
        for c in row_cells:
            set_cell_margins(c, top=60, bottom=60, left=80, right=80)

    # Save to file
    out_files = [
        "MediAssist_Master_System_Architecture_and_QA_Testing_Plan.docx",
        "MediAssist_Master_System_Architecture_and_QA_Testing_Plan_2026.docx"
    ]
    saved_paths = []
    for fn in out_files:
        try:
            doc.save(fn)
            saved_paths.append(fn)
            print(f"Master document saved: {fn}")
        except Exception as e:
            print(f"Failed to save {fn}: {e}")

    # Also copy to workspace root
    for sp in saved_paths:
        try:
            parent_p = os.path.join("..", sp)
            shutil.copyfile(sp, parent_p)
            print(f"Copied master document to workspace root: {parent_p}")
        except Exception as e:
            print(f"Failed to copy to parent: {e}")

if __name__ == "__main__":
    build_master_document()
