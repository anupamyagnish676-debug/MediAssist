const API_BASE = '/api/v1';

const App = {
    // Fetch all active hospitals
    async getHospitals() {
        const res = await fetch(`${API_BASE}/hospitals`);
        return await res.json();
    },

    // Fetch doctors for a specific hospital
    async getHospitalDoctors(hospitalId) {
        const res = await fetch(`${API_BASE}/hospitals/${hospitalId}/doctors`);
        return await res.json();
    },

    // Toggle doctor availability (Available Today / On Leave)
    async toggleDoctorAvailability(doctorId) {
        const res = await fetch(`${API_BASE}/hospitals/doctors/${doctorId}/toggle-availability`, {
            method: 'POST'
        });
        return await res.json();
    },

    // Update doctor's daily token limit
    async updateTokenLimit(doctorId, limit) {
        const res = await fetch(`${API_BASE}/hospitals/doctors/${doctorId}/limit?limit=${limit}`, {
            method: 'POST'
        });
        return await res.json();
    },

    // Receptionist Check-in by QR code token
    async checkInPatient(qrToken, hospitalId = null) {
        const hospQuery = hospitalId ? `&hospitalId=${hospitalId}` : '';
        const res = await fetch(`${API_BASE}/appointments/check-in?token=${encodeURIComponent(qrToken)}${hospQuery}`, {
            method: 'POST'
        });
        return await res.json();
    },

    // Get today's appointments for hospital
    async getTodayAppointments(hospitalId = 1) {
        const res = await fetch(`${API_BASE}/appointments/today?hospitalId=${hospitalId}`);
        return await res.json();
    },

    // Get live queue data for waiting room TV
    async getLiveQueue(hospitalId = 1) {
        const res = await fetch(`${API_BASE}/appointments/live-queue?hospitalId=${hospitalId}`);
        return await res.json();
    }
};
