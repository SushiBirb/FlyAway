/**
 * @file dashboard.js
 * @brief FlyAway Administration Dashboard Logic
 *
 * Implements client-side MVC architecture for the FlyAway web management portal.
 * Features high-throughput pagination for 50,000+ student rosters, debounced search,
 * real-time aggregate statistics, badge scan activity monitoring, token issuance/revocation,
 * and multi-admin credential controls.
 */

// Application state
let currentTab = 'dashboard';
let allRecords = [];
let allTokens = [];
let allAdmins = [];
let pollingInterval = null;

// Students pagination and search state
let studentPage = 1;
let studentPageSize = 50;
let studentSearch = '';
let studentFilter = 'ALL';
let totalStudents = 0;
let totalStudentPages = 1;
let searchDebounceTimer = null;

/**
 * Dispatches an authenticated JSON request to the dedicated server via proxy.php
 */
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
            showToast('Session expired or unauthorized. Redirecting to login...', 'error');
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

/**
 * Toast notification banner.
 */
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

/**
 * Switch active navigation tab.
 */
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

    if (tab === 'dashboard') {
        loadDashboardData();
    } else if (tab === 'permissions') {
        loadStudentsPage(studentPage);
    } else if (tab === 'tokens') {
        loadTokensData();
    } else if (tab === 'admins') {
        loadAdminAccounts();
    }
}

/**
 * Loads aggregated dashboard metrics and recent scan activity records.
 */
async function loadDashboardData() {
    if (!token) return;
    const res = await apiRequest({
        GetDashboardData: {
            token: token,
            page: 1,
            pageSize: 10
        }
    });
    if (!res) return;

    if (res.stats) {
        updateStatistics(res.stats);
    }

    if (res.records) {
        allRecords = res.records;
        renderRecordsTable(allRecords);
    }
}

/**
 * Updates UI counter cards from SQL aggregate metrics.
 */
function updateStatistics(stats) {
    const totalRecordsElem = document.getElementById('stat-total-records');
    const approvedScansElem = document.getElementById('stat-approved-scans');
    const rejectedScansElem = document.getElementById('stat-rejected-scans');
    const allowedUsersElem = document.getElementById('stat-allowed-users');

    if (totalRecordsElem) totalRecordsElem.innerText = (stats.totalRecords !== undefined) ? stats.totalRecords : '-';
    if (approvedScansElem) approvedScansElem.innerText = (stats.approvedScans !== undefined) ? stats.approvedScans : '-';
    if (rejectedScansElem) rejectedScansElem.innerText = (stats.rejectedScans !== undefined) ? stats.rejectedScans : '-';
    if (allowedUsersElem) {
        const allowed = stats.allowedStudents || 0;
        const total = stats.totalStudents || 0;
        allowedUsersElem.innerText = `${allowed.toLocaleString()} / ${total.toLocaleString()}`;
    }
}

/**
 * Renders the recent scan activity log table.
 */
function renderRecordsTable(records) {
    const tbody = document.querySelector('#records-table tbody');
    if (!tbody) return;
    tbody.innerHTML = '';

    if (!records || records.length === 0) {
        tbody.innerHTML = '<tr><td colspan="3" style="text-align:center; color: var(--text-secondary); padding: 24px;">No scan records found</td></tr>';
        return;
    }

    records.forEach(r => {
        const tr = document.createElement('tr');
        const isApproved = r.result === 'APPROVED';
        const badgeClass = isApproved ? 'status-allowed' : 'status-blocked';
        tr.innerHTML = `
            <td><strong>#${escapeHtml(String(r.sid))}</strong></td>
            <td>${escapeHtml(r.timestamp || '-')}</td>
            <td><span class="status-badge ${badgeClass}">${escapeHtml(r.result)}</span></td>
        `;
        tbody.appendChild(tr);
    });
}

/**
 * Filter activity records table locally (for the recent 50 records).
 */
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

/**
 * Export recent records to CSV file.
 */
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

/**
 * High-performance paginated student loader.
 * Queries the dedicated server for a slice of students matching search & filter.
 */
async function loadStudentsPage(page) {
    if (!token) return;
    studentPage = Math.max(1, page);

    const tbody = document.querySelector('#users-table tbody');
    if (tbody) {
        tbody.innerHTML = '<tr><td colspan="3" style="text-align:center; color: var(--text-secondary); padding: 24px;">Loading student records...</td></tr>';
    }

    const res = await apiRequest({
        GetStudents: {
            token: token,
            page: studentPage,
            pageSize: studentPageSize,
            search: studentSearch,
            filter: studentFilter
        }
    });

    if (!res || !res.users) {
        if (tbody) tbody.innerHTML = '<tr><td colspan="3" style="text-align:center; color: var(--accent-red); padding: 24px;">Failed to load students</td></tr>';
        return;
    }

    totalStudents = res.total || 0;
    totalStudentPages = res.totalPages || 1;
    studentPage = res.page || 1;

    renderUsersTable(res.users);
    updatePaginationControls();
}

/**
 * Render paginated student rows into the DOM.
 */
function renderUsersTable(users) {
    const tbody = document.querySelector('#users-table tbody');
    if (!tbody) return;
    tbody.innerHTML = '';

    if (!users || users.length === 0) {
        tbody.innerHTML = '<tr><td colspan="3" style="text-align:center; color: var(--text-secondary); padding: 24px;">No students matching criteria</td></tr>';
        return;
    }

    users.forEach(u => {
        const tr = document.createElement('tr');
        const isAllowed = Boolean(u.exitallowed);
        const statusBadge = isAllowed
            ? '<span class="status-badge status-allowed">EXIT ALLOWED</span>'
            : '<span class="status-badge status-blocked">BLOCKED</span>';

        const toggleBtnText = isAllowed ? 'Block Exit' : 'Allow Exit';
        const toggleBtnClass = isAllowed ? 'btn-danger' : 'btn-success';

        tr.innerHTML = `
            <td><strong>#${escapeHtml(String(u.studentid))}</strong></td>
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

/**
 * Updates the pagination indicators and button states.
 */
function updatePaginationControls() {
    const infoElem = document.getElementById('users-pagination-info');
    const pageIndicator = document.getElementById('users-page-indicator');
    const btnPrev = document.getElementById('btn-users-prev');
    const btnNext = document.getElementById('btn-users-next');

    const startIdx = totalStudents === 0 ? 0 : (studentPage - 1) * studentPageSize + 1;
    const endIdx = Math.min(studentPage * studentPageSize, totalStudents);

    if (infoElem) {
        infoElem.innerText = `Showing ${startIdx.toLocaleString()} to ${endIdx.toLocaleString()} of ${totalStudents.toLocaleString()} students`;
    }
    if (pageIndicator) {
        pageIndicator.innerText = `Page ${studentPage} of ${totalStudentPages}`;
    }
    if (btnPrev) {
        btnPrev.disabled = (studentPage <= 1);
    }
    if (btnNext) {
        btnNext.disabled = (studentPage >= totalStudentPages);
    }
}

function prevStudentPage() {
    if (studentPage > 1) {
        loadStudentsPage(studentPage - 1);
    }
}

function nextStudentPage() {
    if (studentPage < totalStudentPages) {
        loadStudentsPage(studentPage + 1);
    }
}

function onStudentSearchInput() {
    clearTimeout(searchDebounceTimer);
    searchDebounceTimer = setTimeout(() => {
        studentSearch = (document.getElementById('users-search')?.value || '').trim();
        loadStudentsPage(1);
    }, 300);
}

function onStudentFilterChange() {
    studentFilter = document.getElementById('users-filter')?.value || 'ALL';
    loadStudentsPage(1);
}

function onStudentPageSizeChange() {
    studentPageSize = parseInt(document.getElementById('users-page-size')?.value || '50', 10);
    loadStudentsPage(1);
}

/**
 * Toggle student early dismissal privilege.
 */
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
        loadStudentsPage(studentPage);
    } else {
        showToast('Failed to update student permission', 'error');
    }
}

/**
 * Delete a student record.
 */
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
        loadStudentsPage(studentPage);
    } else {
        showToast('Failed to delete student', 'error');
    }
}

/**
 * Register or update an individual student.
 */
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
        showToast(`Student #${sid} successfully saved`, 'success');
        closeModal('modal-add-student');
        idInput.value = '';
        allowInput.checked = false;
        loadStudentsPage(studentPage);
    } else {
        showToast('Failed to save student', 'error');
    }
}

/**
 * Batch import students from CSV file using high-throughput batching.
 */
async function submitImportCSV() {
    const fileInput = document.getElementById('csv-file-input');
    if (!fileInput.files || fileInput.files.length === 0) {
        showToast('Please select a CSV file to upload', 'error');
        return;
    }

    const file = fileInput.files[0];
    const reader = new FileReader();

    reader.onload = async (e) => {
        const text = e.target.result;
        const lines = text.split(/\r\n|\n/);
        const students = [];

        for (let i = 0; i < lines.length; i++) {
            const line = lines[i].trim();
            if (!line || line.toLowerCase().startsWith('studentid')) continue;

            const parts = line.split(',');
            const sid = parseInt(parts[0].replace(/['"]/g, '').trim(), 10);
            if (isNaN(sid) || sid < 1) continue;

            let allow = false;
            if (parts.length > 1) {
                const val = parts[1].replace(/['"]/g, '').trim().toLowerCase();
                allow = (val === '1' || val === 'true' || val === 'yes' || val === 'allowed');
            }
            students.push({ studentid: sid, allow: allow });
        }

        if (students.length === 0) {
            showToast('No valid student records found in CSV', 'error');
            return;
        }

        showToast(`Importing ${students.length} student records...`, 'info');

        // Batch into chunks of 500 records
        const chunkSize = 500;
        let totalImported = 0;

        for (let i = 0; i < students.length; i += chunkSize) {
            const chunk = students.slice(i, i + chunkSize);
            const res = await apiRequest({
                ImportStudents: {
                    token: token,
                    students: chunk
                }
            });

            if (res && res.success) {
                totalImported += (res.importedCount || chunk.length);
            } else {
                showToast(`Batch import encountered an issue at record ${i}`, 'error');
                break;
            }
        }

        showToast(`Successfully imported ${totalImported} students!`, 'success');
        closeModal('modal-import-csv');
        fileInput.value = '';
        loadStudentsPage(1);
    };

    reader.readAsText(file);
}

/**
 * Token management loader.
 */
async function loadTokensData() {
    if (!token) return;
    const res = await apiRequest({ GetTokensData: { token: token } });
    if (!res) return;

    allTokens = res;
    renderTokensTable(allTokens);
}

function renderTokensTable(tokens) {
    const tbody = document.querySelector('#tokens-table tbody');
    if (!tbody) return;
    tbody.innerHTML = '';

    if (tokens.length === 0) {
        tbody.innerHTML = '<tr><td colspan="7" style="text-align:center; color: var(--text-secondary); padding: 24px;">No tokens active</td></tr>';
        return;
    }

    tokens.forEach(t => {
        const tr = document.createElement('tr');
        const isValid = t.status === 'VALIDATED';
        const statusBadge = isValid
            ? '<span class="status-badge status-allowed">VALID</span>'
            : '<span class="status-badge status-blocked">INVALIDATED</span>';

        const roleText = t.admin === 1 ? 'Administrator' : 'Edge Scanner';
        const actionHtml = isValid
            ? `<button class="btn btn-danger" style="padding: 6px 12px; font-size: 0.8rem;" onclick="revokeToken(${t.id})">Revoke</button>`
            : '<span style="color: var(--text-muted); font-size: 0.8rem;">Revoked</span>';

        tr.innerHTML = `
            <td><code>${escapeHtml(t.token)}</code></td>
            <td>${escapeHtml(t.sessionid || 'N/A')}</td>
            <td><strong>${roleText}</strong></td>
            <td>${statusBadge}</td>
            <td>${escapeHtml(t.creationdate || '-')}</td>
            <td>${escapeHtml(t.expdate || 'Never')}</td>
            <td>${actionHtml}</td>
        `;
        tbody.appendChild(tr);
    });
}

async function submitGenerateToken() {
    const ssidInput = document.getElementById('token-session-desc');
    const sessionDesc = ssidInput.value.trim() || 'Edge-Scanner';

    const res = await apiRequest({
        GenerateToken: {
            token: token,
            sessionid: sessionDesc
        }
    });

    if (res && res.success) {
        showToast('Token generated successfully!', 'success');
        closeModal('modal-add-token');
        ssidInput.value = '';
        prompt('Copy the generated scanner token below (this will only be shown once):', res.token);
        loadTokensData();
    } else {
        showToast('Failed to generate token', 'error');
    }
}

async function revokeToken(tokenId) {
    if (!confirm('Are you sure you want to revoke this token? Any scanner using it will lose access immediately.')) return;

    const res = await apiRequest({
        RevokeToken: {
            token: token,
            tokenId: tokenId
        }
    });

    if (res && res.success) {
        showToast('Token revoked', 'success');
        loadTokensData();
    } else {
        showToast('Failed to revoke token', 'error');
    }
}

/**
 * Admin account management loader.
 */
async function loadAdminAccounts() {
    if (!token) return;
    const res = await apiRequest({ GetAdminAccounts: { token: token } });
    if (!res) return;

    allAdmins = res;
    renderAdminsTable(allAdmins);
}

function renderAdminsTable(admins) {
    const tbody = document.querySelector('#admins-table tbody');
    if (!tbody) return;
    tbody.innerHTML = '';

    if (admins.length === 0) {
        tbody.innerHTML = '<tr><td colspan="6" style="text-align:center; color: var(--text-secondary); padding: 24px;">No admin accounts found</td></tr>';
        return;
    }

    admins.forEach(a => {
        const tr = document.createElement('tr');
        tr.innerHTML = `
            <td>#${escapeHtml(String(a.id))}</td>
            <td><strong>${escapeHtml(a.username)}</strong></td>
            <td><code>${escapeHtml(String(a.permsum))}</code></td>
            <td>${escapeHtml(a.creationdate || '-')}</td>
            <td>${escapeHtml(a.lastlogin || 'Never')}</td>
            <td>
                <button class="btn btn-secondary" style="padding: 6px 12px; font-size: 0.8rem;" onclick="deleteAdminAccount(${a.id}, '${escapeHtml(a.username)}')">
                    Delete
                </button>
            </td>
        `;
        tbody.appendChild(tr);
    });
}

async function submitCreateAdmin() {
    const unameInput = document.getElementById('new-admin-username');
    const pwd1Input = document.getElementById('new-admin-password');
    const pwd2Input = document.getElementById('new-admin-confirm');

    const username = unameInput.value.trim();
    const pwd1 = pwd1Input.value;
    const pwd2 = pwd2Input.value;

    if (!username || username.length < 3) {
        showToast('Username must be at least 3 characters', 'error');
        return;
    }
    if (!pwd1 || pwd1.length < 5) {
        showToast('Password must be at least 5 characters', 'error');
        return;
    }
    if (pwd1 !== pwd2) {
        showToast('Passwords do not match', 'error');
        return;
    }

    const clientHash = new Hashes.SHA256().hex(pwd1);

    const res = await apiRequest({
        CreateAdminAccount: {
            token: token,
            username: username,
            password: clientHash
        }
    });

    if (res && res.success) {
        showToast(`Administrator '${username}' created successfully`, 'success');
        closeModal('modal-add-admin');
        unameInput.value = '';
        pwd1Input.value = '';
        pwd2Input.value = '';
        loadAdminAccounts();
    } else {
        showToast(res?.message || 'Failed to create administrator account', 'error');
    }
}

async function deleteAdminAccount(adminId, username) {
    if (!confirm(`Are you sure you want to delete administrator account '${username}'?`)) return;

    const res = await apiRequest({
        DeleteAdminAccount: {
            token: token,
            id: adminId
        }
    });

    if (res && res.success) {
        showToast(`Administrator '${username}' deleted`, 'success');
        loadAdminAccounts();
    } else {
        showToast(res?.message || 'Failed to delete administrator account', 'error');
    }
}

// Modal controls
function openModal(modalId) {
    const modal = document.getElementById(modalId);
    if (modal) modal.style.display = 'flex';
}

function closeModal(modalId) {
    const modal = document.getElementById(modalId);
    if (modal) modal.style.display = 'none';
}

function closeModalOnOuterClick(e, modalId) {
    if (e.target.id === modalId) {
        closeModal(modalId);
    }
}

// HTML escape helper
function escapeHtml(str) {
    if (!str) return '';
    return String(str)
        .replace(/&/g, '&amp;')
        .replace(/</g, '&lt;')
        .replace(/>/g, '&gt;')
        .replace(/"/g, '&quot;')
        .replace(/'/g, '&#039;');
}

// Initialize on page load
document.addEventListener('DOMContentLoaded', () => {
    switchTab('dashboard');
    // Poll dashboard data every 10 seconds
    pollingInterval = setInterval(() => {
        if (currentTab === 'dashboard') {
            loadDashboardData();
        }
    }, 10000);
});
