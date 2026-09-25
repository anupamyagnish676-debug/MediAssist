-- ====================================================================
-- MediAssist WhatsApp Medical Assistant - Supabase PostgreSQL Schema
-- ====================================================================
-- Paste and execute this entire script in Supabase SQL Editor:
-- Dashboard -> SQL Editor -> New Query -> Run
-- ====================================================================

-- 1. Enable Useful Extensions
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";
CREATE EXTENSION IF NOT EXISTS "postgis";

-- 2. Hospitals Table
CREATE TABLE IF NOT EXISTS hospitals (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    address VARCHAR(255),
    latitude DOUBLE PRECISION NOT NULL,
    longitude DOUBLE PRECISION NOT NULL,
    phone VARCHAR(50),
    logo_url TEXT,
    brand_color VARCHAR(50) DEFAULT '#0284c7',
    active BOOLEAN DEFAULT TRUE,
    registration_number VARCHAR(255),
    contact_person_name VARCHAR(255),
    contact_person_email VARCHAR(255),
    contact_person_phone VARCHAR(255),
    specialties VARCHAR(500),
    number_of_beds INTEGER DEFAULT 0,
    application_note TEXT,
    city VARCHAR(255),
    state VARCHAR(255),
    applied_at TIMESTAMP,
    reviewed_at TIMESTAMP,
    rejection_reason TEXT,
    status VARCHAR(50) DEFAULT 'ACTIVE'
);

-- 3. Doctors Table (With dual-shifts and duration)
CREATE TABLE IF NOT EXISTS doctors (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    department VARCHAR(255) NOT NULL,
    hospital_id BIGINT NOT NULL REFERENCES hospitals(id) ON DELETE CASCADE,
    daily_token_limit INTEGER DEFAULT 25,
    current_token_count INTEGER DEFAULT 0,
    available_today BOOLEAN DEFAULT TRUE,
    room_number VARCHAR(50) DEFAULT '101',
    consultation_fee DOUBLE PRECISION DEFAULT 500.0,
    qualification VARCHAR(255) DEFAULT 'MBBS, MD',
    available_days VARCHAR(255) DEFAULT 'MON,TUE,WED,THU,FRI,SAT',
    first_half_time VARCHAR(255) DEFAULT '09:00 AM - 01:00 PM',
    second_half_time VARCHAR(255) DEFAULT '05:00 PM - 09:00 PM',
    consultation_duration_minutes INTEGER DEFAULT 15,
    first_half_limit INTEGER DEFAULT 15,
    second_half_limit INTEGER DEFAULT 15,
    available_time VARCHAR(255),
    rating DOUBLE PRECISION DEFAULT 4.9,
    total_reviews INTEGER DEFAULT 42
);

-- 4. Users Table (Super Admin, Hospital Managers, Receptionists)
CREATE TABLE IF NOT EXISTS users (
    id BIGSERIAL PRIMARY KEY,
    email VARCHAR(255) UNIQUE NOT NULL,
    password VARCHAR(255) NOT NULL,
    name VARCHAR(255) NOT NULL,
    role VARCHAR(50) NOT NULL,
    hospital_id BIGINT REFERENCES hospitals(id) ON DELETE SET NULL,
    active BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 5. Appointments Table (Dual QR, ratings, time mapping)
CREATE TABLE IF NOT EXISTS appointments (
    id BIGSERIAL PRIMARY KEY,
    hospital_id BIGINT NOT NULL REFERENCES hospitals(id) ON DELETE CASCADE,
    doctor_id BIGINT NOT NULL REFERENCES doctors(id) ON DELETE CASCADE,
    patient_phone VARCHAR(50) NOT NULL,
    patient_name VARCHAR(255) DEFAULT 'Patient',
    appointment_date DATE NOT NULL,
    time_slot VARCHAR(50) DEFAULT '10:00 AM',
    shift VARCHAR(50),
    serial_number INTEGER NOT NULL,
    qr_code_token VARCHAR(255) UNIQUE NOT NULL,
    status VARCHAR(50) DEFAULT 'CONFIRMED',
    pdf_file_path VARCHAR(500),
    consultation_start_time TIMESTAMP,
    consultation_end_time TIMESTAMP,
    rating INTEGER,
    feedback_text TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 6. Medication Reminders Table
CREATE TABLE IF NOT EXISTS medication_reminders (
    id BIGSERIAL PRIMARY KEY,
    patient_phone VARCHAR(50) NOT NULL,
    medicine_name VARCHAR(255) NOT NULL,
    dosage VARCHAR(100),
    reminder_time VARCHAR(50) NOT NULL,
    frequency VARCHAR(50) DEFAULT 'DAILY',
    status VARCHAR(50) DEFAULT 'ACTIVE',
    snooze_count INTEGER DEFAULT 0,
    snooze_until TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 7. Patient Sessions Table (GPS & triage memory)
CREATE TABLE IF NOT EXISTS patient_sessions (
    id BIGSERIAL PRIMARY KEY,
    phone_number VARCHAR(50) UNIQUE NOT NULL,
    latitude DOUBLE PRECISION,
    longitude DOUBLE PRECISION,
    preferred_department VARCHAR(100),
    last_interaction TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- 8. Medical Documents / Wardrobe Table
CREATE TABLE IF NOT EXISTS medical_documents (
    id BIGSERIAL PRIMARY KEY,
    patient_phone VARCHAR(50) NOT NULL,
    title VARCHAR(255) NOT NULL,
    document_type VARCHAR(100) DEFAULT 'LAB_REPORT',
    file_path VARCHAR(500),
    ai_summary TEXT,
    extracted_text TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- ====================================================================
-- SEED DATA: Super Admin, Bhubaneswar Clinics & Delhi Clinics
-- ====================================================================

-- Super Admin: admin@mediassist.com / Admin@123
INSERT INTO users (email, password, name, role)
VALUES ('admin@mediassist.com', '$2a$10$w0u35U7d1VjK9m4e6O8J.uGz6K5uR.GvU0G5C8q1i2o3p4q5r6s7t', 'Platform Administrator', 'SUPER_ADMIN')
ON CONFLICT (email) DO NOTHING;

-- 1. KIMS Hospital, KIIT Campus 5, Bhubaneswar (Local Clinic)
INSERT INTO hospitals (name, address, latitude, longitude, phone, specialties, brand_color, status, active)
VALUES ('KIMS Hospital (Kalinga Institute of Medical Sciences)', 'KIMS Hospital Road, KIIT Campus 5, Patia, Bhubaneswar, Odisha 751024', 20.3535, 85.8155, '+91 674-710-5300', 'General Medicine, Cardiology, Pediatrics, Orthopedics, ENT, Dermatology', '#0284c7', 'ACTIVE', true)
ON CONFLICT DO NOTHING;

-- 2. Apollo Hospitals, Bhubaneswar
INSERT INTO hospitals (name, address, latitude, longitude, phone, specialties, brand_color, status, active)
VALUES ('Apollo Hospitals Bhubaneswar', 'Plot No. 251, Sainik School Road, Unit 15, Bhubaneswar, Odisha 751005', 20.3056, 85.8316, '+91 674-666-1066', 'General Medicine, Cardiology, Neurology, Oncology, Nephrology', '#0d9488', 'ACTIVE', true)
ON CONFLICT DO NOTHING;

-- 3. City Care Multispeciality Hospital, New Delhi
INSERT INTO hospitals (name, address, latitude, longitude, phone, specialties, brand_color, status, active)
VALUES ('City Care Multispeciality Hospital', '104 Health Avenue, Central District, New Delhi', 28.6139, 77.2090, '+91 11-2334-1100', 'Cardiology, Pediatrics, General Medicine, Dermatology', '#0284c7', 'ACTIVE', true)
ON CONFLICT DO NOTHING;

-- Seed on-duty doctors for KIMS Bhubaneswar
INSERT INTO doctors (name, department, hospital_id, daily_token_limit, consultation_fee, qualification, available_days, first_half_time, second_half_time, consultation_duration_minutes, available_time, rating, total_reviews, available_today)
SELECT 'Dr. Rajesh Mohapatra', 'General Medicine', id, 40, 400.0, 'MBBS, MD (Medicine)', 'MON,TUE,WED,THU,FRI,SAT', '09:00 AM - 01:00 PM', '05:00 PM - 09:00 PM', 15, '09:00 AM - 01:00 PM & 05:00 PM - 09:00 PM', 4.9, 68, true
FROM hospitals WHERE name LIKE '%KIMS Hospital%'
ON CONFLICT DO NOTHING;

INSERT INTO doctors (name, department, hospital_id, daily_token_limit, consultation_fee, qualification, available_days, first_half_time, second_half_time, consultation_duration_minutes, available_time, rating, total_reviews, available_today)
SELECT 'Dr. Subrat Mishra', 'Cardiology', id, 30, 600.0, 'MBBS, DM (Cardio)', 'MON,TUE,WED,THU,FRI,SAT', '10:00 AM - 02:00 PM', '06:00 PM - 09:00 PM', 20, '10:00 AM - 02:00 PM & 06:00 PM - 09:00 PM', 4.8, 52, true
FROM hospitals WHERE name LIKE '%KIMS Hospital%'
ON CONFLICT DO NOTHING;

INSERT INTO doctors (name, department, hospital_id, daily_token_limit, consultation_fee, qualification, available_days, first_half_time, second_half_time, consultation_duration_minutes, available_time, rating, total_reviews, available_today)
SELECT 'Dr. Priyadarshini Panda', 'Pediatrics', id, 25, 450.0, 'MBBS, MD (Pediatrics)', 'MON,TUE,WED,THU,FRI,SAT', '11:00 AM - 03:00 PM', '05:30 PM - 08:30 PM', 15, '11:00 AM - 03:00 PM & 05:30 PM - 08:30 PM', 4.9, 39, true
FROM hospitals WHERE name LIKE '%KIMS Hospital%'
ON CONFLICT DO NOTHING;

-- Seed doctors for Apollo Hospitals Bhubaneswar
INSERT INTO doctors (name, department, hospital_id, daily_token_limit, consultation_fee, qualification, available_days, first_half_time, second_half_time, consultation_duration_minutes, available_time, rating, total_reviews, available_today)
SELECT 'Dr. Amartya Patnaik', 'General Medicine', id, 35, 500.0, 'MBBS, MD', 'MON,TUE,WED,THU,FRI,SAT', '09:00 AM - 01:00 PM', '04:00 PM - 08:00 PM', 15, '09:00 AM - 01:00 PM & 04:00 PM - 08:00 PM', 4.9, 45, true
FROM hospitals WHERE name LIKE '%Apollo Hospitals Bhubaneswar%'
ON CONFLICT DO NOTHING;

INSERT INTO doctors (name, department, hospital_id, daily_token_limit, consultation_fee, qualification, available_days, first_half_time, second_half_time, consultation_duration_minutes, available_time, rating, total_reviews, available_today)
SELECT 'Dr. Suchitra Das', 'Cardiology', id, 30, 700.0, 'MBBS, DM (Cardio)', 'MON,TUE,WED,THU,FRI,SAT', '10:30 AM - 02:30 PM', '05:00 PM - 08:30 PM', 20, '10:30 AM - 02:30 PM & 05:00 PM - 08:30 PM', 5.0, 74, true
FROM hospitals WHERE name LIKE '%Apollo Hospitals Bhubaneswar%'
ON CONFLICT DO NOTHING;

-- Seed Hospital Manager accounts
INSERT INTO users (email, password, name, role, hospital_id)
SELECT 'manager@kims.com', '$2a$10$w0u35U7d1VjK9m4e6O8J.uGz6K5uR.GvU0G5C8q1i2o3p4q5r6s7t', 'KIMS Hospital Admin', 'HOSPITAL_MANAGER', id
FROM hospitals WHERE name LIKE '%KIMS Hospital%'
ON CONFLICT (email) DO NOTHING;

INSERT INTO users (email, password, name, role, hospital_id)
SELECT 'manager@citycare.com', '$2a$10$w0u35U7d1VjK9m4e6O8J.uGz6K5uR.GvU0G5C8q1i2o3p4q5r6s7t', 'City Care Hospital Admin', 'HOSPITAL_MANAGER', id
FROM hospitals WHERE name LIKE '%City Care%'
ON CONFLICT (email) DO NOTHING;
