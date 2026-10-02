# FlyAway Dedicated Server (ds)

The FlyAway Dedicated Server (`ds`) is the core backend service of the FlyAway attendance system. Built with Java 21, it exposes an encrypted TLS 1.3 / HTTPS JSON API that handles badge verification scans from edge endpoints, powers administrative management queries from the web dashboard, and coordinates database persistence.

It also provides an interactive CLI shell on standard input for console administration, account bootstrapping, and direct query execution.

---

## Architecture Overview

- **Embedded HTTPS Server**: Uses `com.sun.net.httpserver.HttpsServer` configured with PKCS12 key stores and a multi-threaded worker executor pool.
- **Dual Database Engine Architecture**: Dynamically connects to remote MySQL / MariaDB databases using connection pooling, or falls back to an embedded local SQLite file (`flyaway.db`).
- **Scalability**: Optimized for high-volume student rosters (tested with 50,000+ records) using database indexes, SQL aggregation functions, and paginated record streaming.
- **Cryptographic Security**: Employs salted `PBKDF2WithHmacSHA256` hashing (65,536 iterations) for administrative credentials, token-based API authentication, and per-client IP sliding-window rate limiting.

---

## Prerequisites and Dependencies

### Linux (Arch Linux, Ubuntu, Debian, Fedora)
- Java 21 OpenJDK or newer (`jdk21-openjdk` / `openjdk-21-jdk`)
- Linux Shell (`bash` or `sh`)

#### Installation Commands:
- **Arch Linux**:
  ```bash
  sudo pacman -S jdk21-openjdk
  ```
- **Ubuntu / Debian**:
  ```bash
  sudo apt-get update && sudo apt-get install -y openjdk-21-jdk
  ```
- **Fedora**:
  ```bash
  sudo dnf install -y java-21-openjdk-devel
  ```

### Windows (Windows 10, Windows 11, Windows Server)
- Eclipse Temurin OpenJDK 21, Microsoft Build of OpenJDK 21, or Oracle JDK 21.
  ```powershell
  winget install EclipseAdoptium.Temurin.21.JDK
  ```

### Bundled Java Libraries (in `ds/lib/`)
- `mysql-connector-j-8.4.0.jar` (MySQL / MariaDB JDBC driver)
- `sqlite-jdbc-3.46.1.3.jar` (SQLite JDBC driver)
- `json-20240303.jar` (JSON parsing and serialization)
- `slf4j-api-2.0.13.jar` (Logging abstraction)

---

## Configuration

Configuration parameters can be defined in a `.env` file at the repository root or set as environment variables.

| Variable | Description | Default |
| :--- | :--- | :--- |
| `DB_HOST` | Remote MySQL / MariaDB host IP or hostname | *(omitted: uses SQLite)* |
| `DB_PORT` | Remote MySQL / MariaDB port | `3306` |
| `DB_DATABASE` | Database name | `flyaway` |
| `DB_USER` | Database username | `flyaway` |
| `DB_PASSWORD` | Database user password | `flyawaypass` |
| `DB_URL` | Direct JDBC connection string override | *(auto-constructed)* |
| `FLYAWAY_BIND_HOST`| Network interface for HTTPS server | `0.0.0.0` |
| `FLYAWAY_PORT` | TCP port for HTTPS server | `8000` |
| `FLYAWAY_RATE_LIMIT`| Maximum requests per minute per IP | `180` |
| `FLYAWAY_WORKER_THREADS`| Number of concurrent request threads | `16` |

---

## Building and Running

### Linux

Use the provided startup script, which compiles modified sources and launches the JVM:

```bash
cd ds
chmod +x run.sh
./run.sh
```

Or manually:
```bash
cd ds
mkdir -p build/classes
javac -cp "lib/*:res" -d build/classes $(find src -name "*.java")
java -cp "build/classes:lib/*:res" net.eastern.FlyAway.Main
```

### Windows (Command Prompt / PowerShell)

```cmd
cd ds
if not exist build\classes mkdir build\classes
javac -cp "lib/*;res" -d build/classes src/net/eastern/FlyAway/*.java src/net/eastern/FlyAway/*/*.java
java -cp "build/classes;lib/*;res" net.eastern.FlyAway.Main
```

---

## CLI Shell Command Reference

When running, the server presents an interactive shell (`# FLA-DS > `):

- `sendrecord <studentid>`: Simulates a badge scan. Verifies permission, auto-enrolls unknown students as BLOCKED, and records the scan activity in `RECORDS`.
- `setuserallow <studentid> <0|1>`: Updates a student's early exit privilege (`1` for allowed, `0` for blocked).
- `validate <studentid>`: Checks whether a student is authorized (`1` or `0`) without logging a scan record.
- `createuser <username> <permsum>`: Securely creates an administrative user with permission sum (e.g., `777`). Prompts for password securely.
- `exec <SELECT query>`: Safely executes a read-only SQL SELECT query against the active database engine and prints tabular output.
- `help`: Displays the built-in command assistance reference.
- `exit` or `quit`: Gracefully terminates the dedicated server and worker threads.

---

## HTTPS JSON API Reference

All requests must be sent via `POST` with `Content-Type: application/json`.

### 1. Attendance Verification Scan (`sendrecord`)
Used by edge scanner kiosks (`ep`) to verify student credentials.

**Request:**
```json
{
  "sendrecord": "100204",
  "token": "e1111111-1111-1111-1111-111111111111"
}
```

**Response (Authorized):**
```json
{
  "status": "APPROVED",
  "allowed": true,
  "message": "200 ok"
}
```

**Response (Blocked):**
```json
{
  "status": "REJECTED",
  "allowed": false,
  "message": "200 ok"
}
```

### 2. Administrator Login (`LoginRequest`)
Authenticates an administrator account and returns a scoped session token. Password must be pre-hashed with client-side SHA-256.

**Request:**
```json
{
  "LoginRequest": {
    "username": "admin",
    "password": "<SHA-256 hex string>",
    "sessionID": "WEB-session-uuid"
  }
}
```

**Response:**
```json
{
  "AuthResponse": {
    "result": "200",
    "username": "admin",
    "sessionID": "WEB-session-uuid",
    "token": {
      "code": "da2d73e9-5ddb-4f00-a712-00421c15b937",
      "exp": "2026-10-02-04:00"
    }
  }
}
```

### 3. Dashboard Metrics & Overview (`GetDashboardData`)
Returns aggregate statistics computed in SQL, recent activity logs, and the first page of students.

**Request:**
```json
{
  "GetDashboardData": {
    "token": "<admin-token>",
    "page": 1,
    "pageSize": 50
  }
}
```

**Response:**
```json
{
  "stats": {
    "totalStudents": 50000,
    "allowedStudents": 14904,
    "blockedStudents": 35096,
    "totalRecords": 14,
    "approvedScans": 8,
    "rejectedScans": 6
  },
  "records": [
    { "sid": 100204, "timestamp": "2026-10-01 23:47:39", "result": "APPROVED" }
  ],
  "users": [
    { "studentid": 100001, "exitallowed": false }
  ],
  "totalUsers": 50000,
  "page": 1,
  "pageSize": 50,
  "totalPages": 1000
}
```

### 4. Paginated Student Roster (`GetStudents`)
Enables responsive search and filtering across large student directories.

**Request:**
```json
{
  "GetStudents": {
    "token": "<admin-token>",
    "page": 1,
    "pageSize": 50,
    "search": "1002",
    "filter": "ALLOWED"
  }
}
```

**Response:**
```json
{
  "users": [
    { "studentid": 100201, "exitallowed": true }
  ],
  "total": 312,
  "page": 1,
  "pageSize": 50,
  "totalPages": 7
}
```

### 5. Set Student Permission (`SetPermission`)
**Request:**
```json
{
  "SetPermission": {
    "token": "<admin-token>",
    "studentid": 100201,
    "allow": true
  }
}
```

### 6. Batch Roster Import (`ImportStudents`)
High-speed bulk enrollment and permission updates using JDBC batch execution.

**Request:**
```json
{
  "ImportStudents": {
    "token": "<admin-token>",
    "students": [
      { "studentid": 100001, "allow": true },
      { "studentid": 100002, "allow": false }
    ]
  }
}
```

### 7. Token Administration (`GetTokensData`, `GenerateToken`, `RevokeToken`)
Generates and revokes hardware and session tokens.

### 8. Admin Account Management (`GetAdminAccounts`, `CreateAdminAccount`, `DeleteAdminAccount`)
Manages administrator login accounts with self-deletion protection.

---

## Systemd Service Setup (Linux Server)

Create `/etc/systemd/system/flyaway-ds.service`:

```ini
[Unit]
Description=FlyAway Dedicated Server
After=network.target mariadb.service

[Service]
Type=simple
User=flyaway
WorkingDirectory=/opt/flyaway/ds
EnvironmentFile=/opt/flyaway/.env
ExecStart=/usr/bin/java -cp "build/classes:lib/*:res" net.eastern.FlyAway.Main
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
```

Enable and start the service:
```bash
sudo systemctl daemon-reload
sudo systemctl enable --now flyaway-ds.service
```
