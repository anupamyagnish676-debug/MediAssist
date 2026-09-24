# MediAssist — Frontend Portal Suite

This directory contains the entire client-facing web application suite for **MediAssist**. All user interfaces are responsive single-page web applications communicating directly with the backend Spring Boot REST APIs.

## 📁 Portal & Page Overview

| File | Portal / Purpose | Description & Access |
|---|---|---|
| [`index.html`](./index.html) | **Main Platform Landing Page** | Navigation hub directing patients and staff to the portals and WhatsApp bot. |
| [`login.html`](./login.html) | **Role-Based Authentication** | Secure login portal supporting Super Admin, Hospital Manager, and Receptionist roles with JWT / session authentication. |
| [`admin-portal.html`](./admin-portal.html) | **Super Admin Dashboard** | Application approval queue, hospital management, system audit logs, and global user management. |
| [`manager-portal.html`](./manager-portal.html) | **Hospital Manager Portal** | Real-time queue monitor, doctor roster & schedule management, token limits, and QR check-in scanner. |
| [`receptionist.html`](./receptionist.html) | **OPD Reception Desk** | Fast camera-based QR code check-in, token validation, and patient queue handling. |
| [`doctor-roster.html`](./doctor-roster.html) | **Doctor Availability Roster** | Live public view of doctors on duty, room numbers, consultation fees, and available hours. |
| [`token-display.html`](./token-display.html) | **Waiting Room TV Display** | Full-screen live digital signage displaying currently called tokens per doctor. |
| [`whatsapp-simulator.html`](./whatsapp-simulator.html) | **WhatsApp Interactive Simulator** | Live visual smartphone simulator for testing WhatsApp bot interactions (triage, bookings, reminders) directly in the browser. |
| [`apply.html`](./apply.html) | **Hospital Registration Form** | Public onboarding application for hospitals to submit credentials and request platform access. |
| [`hospital-register.html`](./hospital-register.html) | **Alternative Hospital Sign-Up** | Detailed onboarding workflow for medical centers and clinics. |
| [`privacy.html`](./privacy.html) | **Privacy Policy** | Official compliance and privacy statement required for Meta / WhatsApp Cloud API verification. |

## 🎨 Assets & Styling
- **`css/style.css`**: Core stylesheet providing responsive typography, health-themed color palette, badges, and layout grids.
- **`js/app.js`**: Shared JavaScript utilities for API calls, token persistence, and toast notifications.
- **`phone_box.png` / `phone_box_large.png`**: Smartphone frame assets for the WhatsApp simulator.
