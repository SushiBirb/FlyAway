/**
 * FlyAway Dashboard Logic
 */

let currentTab = 'dashboard';
let allRecords = [];
let allUsers = [];
let allTokens = [];
let allAdmins = [];
let pollingInterval = null;

// Generic API caller through /api/proxy.php
async function apiRequest(payload) {
    try {
        const response = await fetch('/api/proxy.php', {
            method: 'POST',
            headers: {
                'Content-Type': 'application/json',
                'Accept': 'application/json'
            },
            body: JSON.stringify(payload)
        });

        if (response.status === 401) {
            showToast('Session expired or unauthorized. Please re-login.', 'error');
            setTimeout(() => { window.location.href = '/login/'; }, 1500);
            return null;
        }

        const data = await response.json();
        return data;
    } catch (err) {
        console.error('API Request failed:', err);
        showToast('Communication with server failed.', 'error');
        return null;
    }
}

// Toast notification helper
function showToast(message, type = 'info') {
    const container = document.getElementById('toast-container');
    if (!container) return;

    const toast = document.createElement('div');
    toast.className = `toast toast-${type}`;
    toast.style.cssText = `
        padding: 12px 18px;
        margin-top: 8px;
        border-radius: 8px;
        color: #fff;
        font-size: 0.9rem;
        background: ${type === 'error' ? 'rgba(239, 68, 68, 0.9)' : type === 'success' ? 'rgba(16, 185, 129, 0.9)' : 'rgba(59, 130, 246, 0.9)'};
        box-shadow: 0 4px 12px rgba(0,0,0,0.3);
        backdrop-filter: blur(8px);
        transition: opacity 0.3s ease, transform 0.3s ease;
    `;
    toast.innerText = message;
    container.appendChild(toast);

    setTimeout(() => {
        toast.style.opacity = '0';
        toast.style.transform = 'translateY(-10px)';
        setTimeout(() => toast.remove(), 300);
    }, 3500);
}

// Tab navigation
function switchTab(tab) {
    currentTab = tab;
    const tabs = ['dashboard', 'permissions', 'tokens', 'admins'];
    const titleMap = {
        'dashboard': 'Dashboard Overview',
        'permissions': 'Students List',
        'tokens': 'Endpoint Access Tokens',
        'admins': 'Dashboard Administrators'
    };

    tabs.forEach(t => {
        const view = document.getElementById(`view-${t}`);
        const nav = document.getElementById(`nav-${t}`);
        if (view) view.style.display = (t === tab) ? 'grid' : 'none';
        if (nav) {
            if (t === tab) {
                nav.classList.add('sidebar-item-selected');
            } else {
                nav.classList.remove('sidebar-item-selected');
            }
        }
    });

    const pageTitle = document.getElementById('page-title');
    if (pageTitle && titleMap[tab]) {
        pageTitle.innerText = titleMap[tab];
    }

    if (tab === 'dashboard' || tab === 'permissions') {
        loadDashboardData();
    } else if (tab === 'tokens') {
        loadTokensData();
    } else if (tab === 'admins') {
        loadAdminAccounts();
    }
}

// Data loaders
async function loadDashboardData() {
    if (!token) return;
    const res = await apiRequest({ GetDashboardData: { token: token } });
    if (!res) return;

    if (res.records) {
        allRecords = res.records;
        renderRecordsTable(allRecords);
        updateStatistics(allRecords, res.users || []);
    }

    if (res.users) {
        allUsers = res.users;
        renderUsersTable(allUsers);
        updateAllowedUsersStat(allUsers);
    }
}

function updateStatistics(records, users) {
    const totalRecordsElem = document.getElementById('stat-total-records');
    const approvedScansElem = document.getElementById('stat-approved-scans');
    const rejectedScansElem = document.getElementById('stat-rejected-scans');

    if (totalRecordsElem) totalRecordsElem.innerText = records.length;

    let approved = 0;
    let rejected = 0;
    records.forEach(r => {
        if (r.result === 'APPROVED') approved++;
        else if (r.result === 'REJECTED') rejected++;
    });

    if (approvedScansElem) approvedScansElem.innerText = approved;
    if (rejectedScansElem) rejectedScansElem.innerText = rejected;
}

function updateAllowedUsersStat(users) {
    const allowedUsersElem = document.getElementById('stat-allowed-users');
    if (allowedUsersElem) {
        const allowedCount = users.filter(u => u.exitallowed).length;
        allowedUsersElem.innerText = allowedCount;
    }
}

// Render records table
function renderRecordsTable(records) {
    const tbody = document.querySelector('#records-table tbody');
    if (!tbody) return;
    tbody.innerHTML = '';

    if (records.length === 0) {
        tbody.innerHTML = '<tr><td colspan="3" style="text-align:center; color: var(--text-secondary); padding: 24px;">No scan records found</td></tr>';
        return;
    }

    records.forEach(r => {
        const tr = document.createElement('tr');
        const isApproved = r.result === 'APPROVED';
        const badgeClass = isApproved ? 'status-allowed' : 'status-blocked';
        tr.innerHTML = `
            <td><strong>#${r.sid}</strong></td>
            <td>${r.timestamp || '-'}</td>
            <td><span class="status-badge ${badgeClass}">${r.result}</span></td>
        `;
        tbody.appendChild(tr);
    });
}

// Filtering records
function filterRecordsTable() {
    const searchVal = (document.getElementById('records-search')?.value || '').trim().toLowerCase();
    const statusVal = document.getElementById('records-status-filter')?.value || 'ALL';
    const timelineVal = document.getElementById('records-timeline-filter')?.value || 'ALL';

    const now = new Date();
    const filtered = allRecords.filter(r => {
        const matchesSearch = !searchVal || String(r.sid).includes(searchVal);
        const matchesStatus = (statusVal === 'ALL') || (r.result === statusVal);

        let matchesTimeline = true;
        if (timelineVal !== 'ALL' && r.timestamp) {
            const recordDate = new Date(r.timestamp.replace(' ', 'T'));
            if (!isNaN(recordDate)) {
                const diffMs = now - recordDate;
                if (timelineVal === 'DAILY') {
                    matchesTimeline = diffMs <= 24 * 60 * 60 * 1000;
                } else if (timelineVal === 'WEEKLY') {
                    matchesTimeline = diffMs <= 7 * 24 * 60 * 60 * 1000;
                } else if (timelineVal === 'MONTHLY') {
                    matchesTimeline = diffMs <= 30 * 24 * 60 * 60 * 1000;
                }
            }
        }

        return matchesSearch && matchesStatus && matchesTimeline;
    });

    renderRecordsTable(filtered);
}

function onTimelineFilterChange() {
    filterRecordsTable();
}

function exportRecordsToCSV() {
    if (allRecords.length === 0) {
        showToast('No records available to export.', 'info');
        return;
    }

    let csvContent = "data:text/csv;charset=utf-8,Student ID,Timestamp,Result\n";
    allRecords.forEach(r => {
        csvContent += `"${r.sid}","${r.timestamp}","${r.result}"\n`;
    });

    const encodedUri = encodeURI(csvContent);
    const link = document.createElement("a");
    link.setAttribute("href", encodedUri);
    link.setAttribute("download", `flyaway-records-${new Date().toISOString().slice(0, 10)}.csv`);
    document.body.appendChild(link);
    link.click();
    document.body.removeChild(link);
}

// Render Users table
function renderUsersTable(users) {
    const tbody = document.querySelector('#users-table tbody');
    if (!tbody) return;
    tbody.innerHTML = '';

    if (users.length === 0) {
        tbody.innerHTML = '<tr><td colspan="3" style="text-align:center; color: var(--text-secondary); padding: 24px;">No students registered yet</td></tr>';
        return;
    }

    users.forEach(u => {
        const tr = document.createElement('tr');
        const isAllowed = u.exitallowed;
        const statusBadge = isAllowed
            ? '<span class="status-badge status-allowed">EXIT ALLOWED</span>'
            : '<span class="status-badge status-blocked">BLOCKED</span>';

        const toggleBtnText = isAllowed ? 'Block Exit' : 'Allow Exit';
        const toggleBtnClass = isAllowed ? 'btn-danger' : 'btn-success';

        tr.innerHTML = `
            <td><strong>#${u.studentid}</strong></td>
            <td>${statusBadge}</td>
            <td style="display: flex; gap: 8px;">
                <button class="btn ${toggleBtnClass}" style="padding: 6px 12px; font-size: 0.8rem;" onclick="toggleStudentPermission(${u.studentid}, ${!isAllowed})">
                    ${toggleBtnText}
                </button>
                <button class="btn btn-secondary" style="padding: 6px 12px; font-size: 0.8rem;" onclick="deleteStudent(${u.studentid})">
                    Delete
                </button>
            </td>
        `;
        tbody.appendChild(tr);
    });
}

function filterUsersTable() {
    const searchVal = (document.getElementById('users-search')?.value || '').trim().toLowerCase();
    const filtered = allUsers.filter(u => !searchVal || String(u.studentid).includes(searchVal));
    renderUsersTable(filtered);
}

async function toggleStudentPermission(studentid, allow) {
    const res = await apiRequest({
        SetPermission: {
            token: token,
            studentid: studentid,
            allow: allow
        }
    });

    if (res && res.success) {
        showToast(`Student #${studentid} permissions updated`, 'success');
        loadDashboardData();
    } else {
        showToast('Failed to update student permission', 'error');
    }
}

async function deleteStudent(studentid) {
    if (!confirm(`Are you sure you want to delete Student #${studentid}?`)) return;

    const res = await apiRequest({
        DeleteStudent: {
            token: token,
            studentid: studentid
        }
    });

    if (res && res.success) {
        showToast(`Student #${studentid} deleted`, 'success');
        loadDashboardData();
    } else {
        showToast('Failed to delete student', 'error');
    }
}

async function submitRegisterStudent() {
    const idInput = document.getElementById('new-student-id');
    const allowInput = document.getElementById('new-student-allow');
    const sid = parseInt(idInput.value, 10);

    if (isNaN(sid) || sid < 1 || sid > 999999999) {
        showToast('Please enter a valid student ID (1 - 999,999,999)', 'error');
        return;
    }

    const res = await apiRequest({
        SetPermission: {
            token: token,
            studentid: sid,
            allow: allowInput.checked
        }
    });

    if (res && res.success) {
        showToast(`Student #${sid} registered successfully`, 'success');
        closeModal('modal-add-student');
        idInput.value = '';
        allowInput.checked = false;
        loadDashboardData();
    } else {
        showToast('Failed to register student', 'error');
    }
}

// Bulk CSV Import
async function submitImportCSV() {
    const fileInput = document.getElementById('csv-file-input');
    if (!fileInput.files || fileInput.files.length === 0) {
        showToast('Please select a CSV file to import.', 'error');
        return;
    }

    const file = fileInput.files[0];
    const text = await file.text();
    const lines = text.split(/\r?\n/).map(l => l.trim()).filter(l => l.length > 0);

    const formContainer = document.getElementById('csv-form-container');
    const progressContainer = document.getElementById('csv-progress-container');
    const progressBar = document.getElementById('csv-progress-bar');
    const progressText = document.getElementById('csv-progress-text');

    if (formContainer) formContainer.style.display = 'none';
    if (progressContainer) progressContainer.style.display = 'flex';

    let successCount = 0;
    let totalLines = lines.length;

    for (let i = 0; i < totalLines; i++) {
        const line = lines[i];
        const parts = line.split(',');
        if (parts.length >= 1) {
            const sid = parseInt(parts[0].trim(), 10);
            const allow = parts.length > 1 ? (parts[1].trim() === '1' || parts[1].trim().toLowerCase() === 'true') : false;

            if (!isNaN(sid) && sid >= 1 && sid <= 999999999) {
                await apiRequest({
                    SetPermission: {
                        token: token,
                        studentid: sid,
                        allow: allow
                    }
                });
                successCount++;
            }
        }

        const pct = Math.round(((i + 1) / totalLines) * 100);
        if (progressBar) progressBar.style.width = `${pct}%`;
        if (progressText) progressText.innerText = `${i + 1} / ${totalLines} students processed`;
    }

    showToast(`Successfully imported ${successCount} students!`, 'success');
    setTimeout(() => {
        closeModal('modal-import-csv');
        if (formContainer) formContainer.style.display = 'flex';
        if (progressContainer) progressContainer.style.display = 'none';
        fileInput.value = '';
        loadDashboardData();
    }, 1000);
}

// Device Tokens
async function loadTokensData() {
    if (!token) return;
    const tokens = await apiRequest({ GetTokensData: { token: token } });
    if (!tokens || !Array.isArray(tokens)) return;

    allTokens = tokens;
    const tbody = document.querySelector('#tokens-table tbody');
    if (!tbody) return;
    tbody.innerHTML = '';

    if (tokens.length === 0) {
        tbody.innerHTML = '<tr><td colspan="7" style="text-align:center; color: var(--text-secondary); padding: 24px;">No device tokens generated yet</td></tr>';
        return;
    }

    tokens.forEach(t => {
        const tr = document.createElement('tr');
        const isValid = t.status === 'VALIDATED';
        const role = t.admin === 1 ? '<span class="status-badge status-admin">ADMIN</span>' : '<span class="status-badge">SCANNER</span>';
        const statusBadge = isValid ? '<span class="status-badge status-allowed">VALIDATED</span>' : '<span class="status-badge status-blocked">INVALIDATED</span>';

        const actionBtn = isValid
            ? `<button class="btn btn-danger" style="padding: 6px 12px; font-size: 0.8rem;" onclick="revokeToken(${t.id})">Revoke</button>`
            : '<span style="color: var(--text-secondary); font-size: 0.8rem;">Revoked</span>';

        tr.innerHTML = `
            <td><code>${t.token}</code></td>
            <td>${t.sessionid || 'N/A'}</td>
            <td>${role}</td>
            <td>${statusBadge}</td>
            <td>${t.creationdate || '-'}</td>
            <td>${t.expdate || 'Never'}</td>
            <td>${actionBtn}</td>
        `;
        tbody.appendChild(tr);
    });
}

async function submitGenerateToken() {
    const sessionInput = document.getElementById('new-token-session');
    const sessionDesc = sessionInput.value.trim() || 'Kiosk-Endpoint';

    const res = await apiRequest({
        GenerateToken: {
            token: token,
            sessionid: sessionDesc
        }
    });

    if (res && res.success && res.token) {
        const formContainer = document.getElementById('token-form-container');
        const resultContainer = document.getElementById('token-result-container');
        const tokenVal = document.getElementById('generated-token-value');

        if (tokenVal) tokenVal.value = res.token;
        if (formContainer) formContainer.style.display = 'none';
        if (resultContainer) resultContainer.style.display = 'flex';
        showToast('Token generated! Copy it now.', 'success');
    } else {
        showToast('Failed to generate token', 'error');
    }
}

function copyGeneratedToken() {
    const tokenVal = document.getElementById('generated-token-value');
    if (tokenVal) {
        navigator.clipboard.writeText(tokenVal.value);
        showToast('Token copied to clipboard!', 'info');
    }
}

function closeTokenResultModal() {
    closeModal('modal-add-token');
    const formContainer = document.getElementById('token-form-container');
    const resultContainer = document.getElementById('token-result-container');
    const sessionInput = document.getElementById('new-token-session');

    if (sessionInput) sessionInput.value = '';
    if (formContainer) formContainer.style.display = 'block';
    if (resultContainer) resultContainer.style.display = 'none';
    loadTokensData();
}

async function revokeToken(tokenId) {
    if (!confirm('Are you sure you want to revoke this token? Devices using it will lose access immediately.')) return;

    const res = await apiRequest({
        RevokeToken: {
            token: token,
            tokenId: tokenId
        }
    });

    if (res && res.success) {
        showToast('Token revoked successfully', 'success');
        loadTokensData();
    } else {
        showToast('Failed to revoke token', 'error');
    }
}

// Administrators
async function loadAdminAccounts() {
    if (!token) return;
    const admins = await apiRequest({ GetAdminAccounts: { token: token } });
    if (!admins || !Array.isArray(admins)) return;

    allAdmins = admins;
    const tbody = document.querySelector('#admins-table tbody');
    if (!tbody) return;
    tbody.innerHTML = '';

    admins.forEach(a => {
        const tr = document.createElement('tr');
        tr.innerHTML = `
            <td>#${a.id}</td>
            <td><strong>${a.username}</strong></td>
            <td>${a.permsum}</td>
            <td>${a.creationdate || '-'}</td>
            <td>${a.lastlogin || 'Never'}</td>
            <td>
                <button class="btn btn-secondary" style="padding: 6px 12px; font-size: 0.8rem;" onclick="deleteAdmin(${a.id})">
                    Delete
                </button>
            </td>
        `;
        tbody.appendChild(tr);
    });
}

async function submitCreateAdmin() {
    const unameInput = document.getElementById('new-admin-uname');
    const pwdInput = document.getElementById('new-admin-pwd');
    const confirmInput = document.getElementById('new-admin-pwd-confirm');

    const uname = unameInput.value.trim();
    const pwd = pwdInput.value;
    const confirmPwd = confirmInput.value;

    if (!uname || !pwd) {
        showToast('Please enter both username and password.', 'error');
        return;
    }

    if (pwd !== confirmPwd) {
        showToast('Passwords do not match.', 'error');
        return;
    }

    // Client-side SHA-256 pre-hash matching Hashes.js
    const clientHash = new Hashes.SHA256().hex(pwd);

    const res = await apiRequest({
        CreateAdminAccount: {
            token: token,
            username: uname,
            password: clientHash
        }
    });

    if (res && res.success) {
        showToast(`Administrator '${uname}' created successfully!`, 'success');
        closeModal('modal-add-admin');
        unameInput.value = '';
        pwdInput.value = '';
        confirmInput.value = '';
        loadAdminAccounts();
    } else {
        showToast(res?.message || 'Failed to create administrator', 'error');
    }
}

async function deleteAdmin(adminId) {
    if (!confirm('Are you sure you want to delete this administrator account?')) return;

    const res = await apiRequest({
        DeleteAdminAccount: {
            token: token,
            id: adminId
        }
    });

    if (res && res.success) {
        showToast('Administrator account deleted', 'success');
        loadAdminAccounts();
    } else {
        showToast(res?.message || 'Failed to delete administrator account', 'error');
    }
}

// Modal management
function openModal(id) {
    const modal = document.getElementById(id);
    if (modal) modal.style.display = 'flex';
}

function closeModal(id) {
    const modal = document.getElementById(id);
    if (modal) modal.style.display = 'none';
}

function closeModalOnOuterClick(event, id) {
    if (event.target.id === id) {
        closeModal(id);
    }
}

// Initialize on page load
window.addEventListener('DOMContentLoaded', () => {
    switchTab('dashboard');

    // Auto-refresh dashboard data every 5 seconds
    if (pollingInterval) clearInterval(pollingInterval);
    pollingInterval = setInterval(() => {
        if (currentTab === 'dashboard') {
            loadDashboardData();
        }
    }, 5000);
});
