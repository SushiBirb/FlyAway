<?php include("loader.php")?>
<!DOCTYPE html>
<html lang="en">
    <head>
        <meta charset="utf-8"/>
        <meta name="viewport" content="width=device-width, initial-scale=1.0" />
        <link rel="icon" href="/img/flyaway-logo-filled.ico" type="image/x-icon"/>
        <link rel="stylesheet" href="/index.css"/>
        <script>
            let token = null;
        </script>
        <script src="/lib/hashes.js"></script>
        <script src="/dashboard.js" defer></script>
        <title><?php include('components/titlemode')?> - Dashboard</title>
    </head>
    <body id="root">
        <!-- Sidebar Navigation -->
        <div class="sidebar-container">
            <div class="sidebar-header">
                <img src="/img/flyaway-logo.png" alt="FlyAway Logo">
                <h2>FlyAway</h2>
            </div>
            
            <div class="sidebar-menu">
                <div class="sidebar-item-selected" id="nav-dashboard" onclick="switchTab('dashboard')">
                    <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="3" y="3" width="7" height="9"/><rect x="14" y="3" width="7" height="5"/><rect x="14" y="12" width="7" height="9"/><rect x="3" y="16" width="7" height="5"/></svg>
                    Dashboard
                </div>
                <div class="sidebar-item" id="nav-permissions" onclick="switchTab('permissions')">
                    <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M17 21v-2a4 4 0 0 0-4-4H5a4 4 0 0 0-4 4v2"/><circle cx="9" cy="7" r="4"/><path d="M23 21v-2a4 4 0 0 0-3-3.87"/><path d="M16 3.13a4 4 0 0 1 0 7.75"/></svg>
                    Students List
                </div>
                <div class="sidebar-item" id="nav-tokens" onclick="switchTab('tokens')">
                    <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><rect x="2" y="2" width="20" height="8" rx="2" ry="2"/><rect x="2" y="14" width="20" height="8" rx="2" ry="2"/><line x1="6" y1="6" x2="6.01" y2="6"/><line x1="6" y1="18" x2="6.01" y2="18"/></svg>
                    Device Tokens
                </div>
                <div class="sidebar-item" id="nav-admins" onclick="switchTab('admins')">
                    <svg width="20" height="20" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z"/></svg>
                    Admin Accounts
                </div>
            </div>
            
            <div class="sidebar-footer">
                <div>Flyaway <?php include('components/version'); ?></div>
                <div style="font-size: 0.65rem; margin-top: 4px;">Aspectious & Sushibirb</div>
            </div>
        </div>

        <!-- Main Content Root -->
        <div id="content-root" class="content-root">
            <!-- Header bar -->
            <div class="content-header">
                <div class="content-header-title" id="page-title">Dashboard Overview</div>
                <a class="content-header-account" href="/logout.php">Logout</a>
            </div>
            
            <!-- Dashboard View -->
            <div id="view-dashboard" class="dashboard-grid">
                <!-- Statistics Blocks -->
                <div class="stats-grid">
                    <div class="stat-card">
                        <div class="stat-title">Total Records</div>
                        <div class="stat-value" id="stat-total-records">-</div>
                        <div class="stat-footer">Cumulative badge scans</div>
                    </div>
                    <div class="stat-card">
                        <div class="stat-title" style="color: var(--accent-green);">Approved exits</div>
                        <div class="stat-value" id="stat-approved-scans" style="color: var(--accent-green);">-</div>
                        <div class="stat-footer">Scanned early exit approvals</div>
                    </div>
                    <div class="stat-card">
                        <div class="stat-title" style="color: var(--accent-red);">Rejected exits</div>
                        <div class="stat-value" id="stat-rejected-scans" style="color: var(--accent-red);">-</div>
                        <div class="stat-footer">Scanned early exit rejections</div>
                    </div>
                    <div class="stat-card">
                        <div class="stat-title" style="color: var(--accent-purple);">Allowed Students</div>
                        <div class="stat-value" id="stat-allowed-users" style="color: var(--accent-purple);">-</div>
                        <div class="stat-footer">Students with exit allowed status</div>
                    </div>
                </div>

                <div class="glass-card">
                    <div class="card-title-bar">
                        <div class="card-title">Recent Activity Log</div>
                        <button class="btn btn-secondary" onclick="exportRecordsToCSV()">
                            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" style="margin-right: 6px;"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/></svg>
                            Export to CSV
                        </button>
                    </div>
                    
                    <div class="controls-row">
                        <div class="search-input-wrapper">
                            <input type="text" id="records-search" class="search-input" placeholder="Search by Student ID..." oninput="filterRecordsTable()">
                        </div>
                        <div class="form-group" style="flex-direction: row; align-items: center; gap: 8px;">
                            <label class="form-label" style="margin-bottom: 0;">Status:</label>
                            <select id="records-status-filter" class="search-input" style="padding: 10px 14px; max-width: 140px;" onchange="filterRecordsTable()">
                                <option value="ALL">All Statuses</option>
                                <option value="APPROVED">Approved</option>
                                <option value="REJECTED">Rejected</option>
                            </select>
                        </div>
                        <div class="form-group" style="flex-direction: row; align-items: center; gap: 8px;">
                            <label class="form-label" style="margin-bottom: 0;">Timeline:</label>
                            <select id="records-timeline-filter" class="search-input" style="padding: 10px 14px; max-width: 140px;" onchange="onTimelineFilterChange()">
                                <option value="ALL">All Time</option>
                                <option value="DAILY">Daily View</option>
                                <option value="WEEKLY">Weekly View</option>
                                <option value="MONTHLY">Monthly View</option>
                            </select>
                        </div>
                    </div>

                    <div class="table-container">
                        <table class="data-table" id="records-table">
                            <thead>
                                <tr>
                                    <th>Student ID</th>
                                    <th>Timestamp</th>
                                    <th>Status Outcome</th>
                                </tr>
                            </thead>
                            <tbody>
                                <!-- Populated dynamically by dashboard.js -->
                            </tbody>
                        </table>
                    </div>
                </div>
            </div>

            <!-- Student Permissions View -->
            <div id="view-permissions" class="dashboard-grid" style="display: none;">
                <div class="glass-card">
                    <div class="card-title-bar">
                        <div class="card-title">Managed Students List</div>
                        <div style="display: flex; gap: 12px;">
                            <button class="btn btn-secondary" onclick="openModal('modal-import-csv')">
                                <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/></svg>
                                Import CSV
                            </button>
                            <button class="btn btn-primary" onclick="openModal('modal-add-student')">
                                <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/></svg>
                                Register Student
                            </button>
                        </div>
                    </div>

                    <div class="controls-row">
                        <div class="search-input-wrapper">
                            <input type="text" id="users-search" class="search-input" placeholder="Search Student ID..." oninput="filterUsersTable()">
                        </div>
                    </div>

                    <div class="table-container">
                        <table class="data-table" id="users-table">
                            <thead>
                                <tr>
                                    <th>Student ID</th>
                                    <th>Exit Privilege Status</th>
                                    <th style="width: 250px;">Actions</th>
                                </tr>
                            </thead>
                            <tbody>
                                <!-- Populated dynamically by dashboard.js -->
                            </tbody>
                        </table>
                    </div>
                </div>
            </div>

            <!-- Device Access Tokens View -->
            <div id="view-tokens" class="dashboard-grid" style="display: none;">
                <div class="glass-card">
                    <div class="card-title-bar">
                        <div class="card-title">Endpoint Access Tokens</div>
                        <button class="btn btn-success" onclick="openModal('modal-add-token')">
                            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/></svg>
                            Generate Scanner Token
                        </button>
                    </div>

                    <div class="table-container">
                        <table class="data-table" id="tokens-table">
                            <thead>
                                <tr>
                                    <th>Token (Masked)</th>
                                    <th>Session/Description</th>
                                    <th>Role</th>
                                    <th>Status</th>
                                    <th>Created On</th>
                                    <th>Expiration</th>
                                    <th>Action</th>
                                </tr>
                            </thead>
                            <tbody>
                                <!-- Populated dynamically by dashboard.js -->
                            </tbody>
                        </table>
                    </div>
                </div>
            </div>

            <!-- Administrative Accounts View -->
            <div id="view-admins" class="dashboard-grid" style="display: none;">
                <div class="glass-card">
                    <div class="card-title-bar">
                        <div class="card-title">Dashboard Administrators</div>
                        <button class="btn btn-primary" onclick="openModal('modal-add-admin')">
                            <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><line x1="12" y1="5" x2="12" y2="19"/><line x1="5" y1="12" x2="19" y2="12"/></svg>
                            Create Administrator
                        </button>
                    </div>

                    <div class="table-container">
                        <table class="data-table" id="admins-table">
                            <thead>
                                <tr>
                                    <th>User ID</th>
                                    <th>Username</th>
                                    <th>Permission Sum</th>
                                    <th>Creation Date</th>
                                    <th>Last Login Date</th>
                                    <th>Action</th>
                                </tr>
                            </thead>
                            <tbody>
                                <!-- Populated dynamically by dashboard.js -->
                            </tbody>
                        </table>
                    </div>
                </div>
            </div>
        </div>

        <!-- MODALS -->

        <!-- Add/Edit Student Modal -->
        <div id="modal-add-student" class="modal" onclick="closeModalOnOuterClick(event, 'modal-add-student')">
            <div class="modal-content">
                <div class="modal-header">
                    <div class="modal-title">Register Student ID</div>
                    <button class="modal-close" onclick="closeModal('modal-add-student')">&times;</button>
                </div>
                <div class="form-group">
                    <label for="new-student-id" class="form-label">Student ID Number</label>
                    <input type="number" id="new-student-id" class="form-input" min="1" max="999999999" placeholder="e.g. 100234">
                </div>
                <div class="form-group">
                    <label class="form-row-checkbox">
                        <input type="checkbox" id="new-student-allow" class="form-checkbox">
                        <span class="form-label" style="margin-bottom: 0; cursor: pointer;">Allow Early Exit</span>
                    </label>
                </div>
                <div style="display: flex; justify-content: flex-end; gap: 12px; margin-top: 12px;">
                    <button class="btn btn-secondary" onclick="closeModal('modal-add-student')">Cancel</button>
                    <button class="btn btn-primary" onclick="submitRegisterStudent()">Submit Registration</button>
                </div>
            </div>
        </div>

        <!-- Generate Token Modal -->
        <div id="modal-add-token" class="modal" onclick="closeModalOnOuterClick(event, 'modal-add-token')">
            <div class="modal-content">
                <div class="modal-header">
                    <div class="modal-title">Generate Access Token</div>
                    <button class="modal-close" onclick="closeModal('modal-add-token')">&times;</button>
                </div>
                
                <div id="token-form-container">
                    <div class="form-group">
                        <label for="new-token-session" class="form-label">Device Session ID / Description</label>
                        <input type="text" id="new-token-session" class="form-input" placeholder="e.g. FrontGate-Scanner">
                    </div>
                    <div class="form-group">
                        <label class="form-row-checkbox">
                            <input type="checkbox" id="new-token-admin" class="form-checkbox">
                            <span class="form-label" style="margin-bottom: 0; cursor: pointer;">Grant Administrative Privileges</span>
                        </label>
                    </div>
                    <div style="display: flex; justify-content: flex-end; gap: 12px; margin-top: 12px;">
                        <button class="btn btn-secondary" onclick="closeModal('modal-add-token')">Cancel</button>
                        <button class="btn btn-success" onclick="submitGenerateToken()">Generate</button>
                    </div>
                </div>

                <div id="token-result-container" style="display: none; flex-direction: column; gap: 16px;">
                    <div class="status-badge status-allowed" style="width: 100%; justify-content: center; padding: 10px;">Token Generated Successfully!</div>
                    <div class="form-group">
                        <label class="form-label">Authorization Token Code (Copy now - will not show again)</label>
                        <div style="display: flex; gap: 8px;">
                            <input type="text" id="generated-token-value" class="form-input" style="flex: 1; font-family: monospace; font-size: 0.85rem;" readonly>
                            <button class="btn btn-secondary" style="padding: 10px;" onclick="copyGeneratedToken()">Copy</button>
                        </div>
                    </div>
                    <div style="display: flex; justify-content: flex-end; margin-top: 12px;">
                        <button class="btn btn-primary" onclick="closeTokenResultModal()">Done</button>
                    </div>
                </div>
            </div>
        </div>

        <!-- Add Admin Modal -->
        <div id="modal-add-admin" class="modal" onclick="closeModalOnOuterClick(event, 'modal-add-admin')">
            <div class="modal-content">
                <div class="modal-header">
                    <div class="modal-title">Create Administrator Account</div>
                    <button class="modal-close" onclick="closeModal('modal-add-admin')">&times;</button>
                </div>
                <div class="form-group">
                    <label for="new-admin-uname" class="form-label">Username</label>
                    <input type="text" id="new-admin-uname" class="form-input" placeholder="e.g. staff_member">
                </div>
                <div class="form-group">
                    <label for="new-admin-pwd" class="form-label">Password</label>
                    <input type="password" id="new-admin-pwd" class="form-input" placeholder="••••••••">
                </div>
                <div class="form-group">
                    <label for="new-admin-pwd-confirm" class="form-label">Confirm Password</label>
                    <input type="password" id="new-admin-pwd-confirm" class="form-input" placeholder="••••••••">
                </div>
                <div style="display: flex; justify-content: flex-end; gap: 12px; margin-top: 12px;">
                    <button class="btn btn-secondary" onclick="closeModal('modal-add-admin')">Cancel</button>
                    <button class="btn btn-primary" onclick="submitCreateAdmin()">Create Administrator</button>
                </div>
            </div>
        </div>
        <!-- Import CSV Modal -->
        <div id="modal-import-csv" class="modal" onclick="closeModalOnOuterClick(event, 'modal-import-csv')">
            <div class="modal-content">
                <div class="modal-header">
                    <div class="modal-title">Bulk Import Students</div>
                    <button class="modal-close" onclick="closeModal('modal-import-csv')">&times;</button>
                </div>
                
                <div id="csv-form-container" style="display: flex; flex-direction: column; gap: 16px;">
                    <div class="status-badge status-admin" style="width: 100%; justify-content: center; padding: 10px; font-size: 0.8rem; line-height: 1.4; text-align: left;">
                        CSV Format: Student ID,Exit Allowed<br>
                        Example:<br>
                        100201,1 &nbsp;(Allowed)<br>
                        100202,0 &nbsp;(Blocked)
                    </div>
                    
                    <div class="form-group">
                        <label for="csv-file-input" class="form-label">Select CSV File</label>
                        <input type="file" id="csv-file-input" class="form-input" accept=".csv">
                    </div>
                    
                    <div style="display: flex; justify-content: flex-end; gap: 12px; margin-top: 12px;">
                        <button class="btn btn-secondary" onclick="closeModal('modal-import-csv')">Cancel</button>
                        <button class="btn btn-primary" onclick="submitImportCSV()">Import Students</button>
                    </div>
                </div>

                <div id="csv-progress-container" style="display: none; flex-direction: column; gap: 16px;">
                    <div class="card-title" id="csv-progress-title">Processing CSV...</div>
                    <div style="width: 100%; height: 8px; background: rgba(255,255,255,0.05); border-radius: 4px; overflow: hidden; position: relative;">
                        <div id="csv-progress-bar" style="width: 0%; height: 100%; background: var(--accent-blue); transition: width 0.1s ease; border-radius: 4px;"></div>
                    </div>
                    <div id="csv-progress-text" style="font-size: 0.85rem; color: var(--text-secondary); text-align: center;">0 / 0 students loaded</div>
                </div>
            </div>
        </div>

        <!-- Toast Notifications Container -->
        <div class="toast-container" id="toast-container"></div>
    </body>
</html>