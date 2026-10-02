# FlyAway Web Management Portal (web)

The FlyAway Web Management Portal (`web`) is an administrative dashboard for school administrators, security personnel, and attendance staff. It provides real-time visibility into student badge scans, allows granular toggling of early dismissal privileges across thousands of students, manages edge scanner device tokens, and controls administrative login accounts.

The portal consists of a responsive HTML5 / CSS3 / JavaScript Single Page Application (SPA) served by PHP with a secure internal reverse proxy layer connecting to the Dedicated Server (`ds`).

---

## Features

- **High-Capacity Student Directory**: Built and benchmarked to smoothly manage 50,000+ student records with zero client-side browser latency using server-side pagination (25, 50, 100, 200 rows per page).
- **Debounced Live Search & Filtering**: Instant search across student badge numbers with 300ms input debouncing and privilege status filtering (All, Allowed Only, Blocked Only).
- **Real-Time Aggregate Dashboard**: Live metrics for total students, authorized vs. blocked student counts, cumulative scans, and approval/rejection rates computed directly in SQL.
- **Bulk CSV Data Interchange**:
  - Export recent scan logs or student rosters to standard `.csv` files.
  - High-throughput client-side CSV import chunking thousands of student records into batches of 500 for rapid database ingestion.
- **Device Token Lifecycle**: Issue, inspect (with masked tokens), and instantly revoke scanner kiosk hardware tokens.
- **Multi-Admin Administration**: Create administrative accounts with client-side SHA-256 password pre-hashing and self-deletion prevention guards.
- **Defense-in-Depth Security**:
  - Authenticated reverse proxy (`/api/proxy.php`) preventing SSRF attacks.
  - Strict session cookie attributes (`HttpOnly`, `SameSite=Strict`, `lifetime=0`).
  - Session fixation protection with automatic ID regeneration upon login.
  - Cryptographically secure CSRF protection.
  - Content Security Policy (CSP), strict MIME type sniffing prevention (`nosniff`), and frame restriction (`DENY`).

---

## Prerequisites and Dependencies

### Linux (Arch Linux, Ubuntu, Debian, Fedora)
- PHP 8.2 or newer with extensions:
  - `php-curl` (for proxying HTTPS API requests to `ds`)
  - `php-json` (bundled in modern PHP)
  - `php-session` (bundled in modern PHP)

#### Installation Commands:
- **Arch Linux**:
  ```bash
  sudo pacman -S php php-curl
  ```
- **Ubuntu / Debian**:
  ```bash
  sudo apt-get update && sudo apt-get install -y php php-curl php-json
  ```
- **Fedora**:
  ```bash
  sudo dnf install -y php php-curl php-json
  ```

### Windows (Windows 10, Windows 11, Windows Server)
- PHP 8.2+ for Windows (Non-Thread-Safe or Thread-Safe x64 ZIP from windows.php.net).
- Ensure `extension=curl` is uncommented in `php.ini`.

---

## Directory Structure

```text
web/
├── cert.pem                     # CA Certificate for verifying ds:8000 TLS connection
├── ext/                         # Optional Windows extension binaries
├── php.ini                      # Local development PHP configuration
├── run.bat                      # Windows local server launcher
├── run.sh                       # Linux local server launcher
└── wwwroot/                     # Document root
    ├── api/
    │   ├── api.js               # Legacy API reference helper
    │   └── proxy.php            # Secure internal API reverse proxy
    ├── components/              # Reusable UI fragments (header, footer, version)
    ├── css/
    │   └── stylesheet-default.css
    ├── img/                     # Logos, icons, and diagrams
    ├── index.css                # Modern glassmorphism dashboard styling
    ├── index.php                # Authenticated dashboard SPA shell
    ├── dashboard.js             # Client-side MVC application controller
    ├── lib/
    │   └── hashes.js            # Client-side cryptographic hashing library
    ├── loader.php               # Security headers and session initialization
    ├── login/
    │   ├── index.css            # Login screen styling
    │   ├── index.js             # Client-side authentication logic
    │   ├── index.php            # Login page template
    │   └── result.php           # Secure token session handler
    ├── logout.php               # Session termination and cookie invalidation
    └── validateToken.php        # Backend session verification helper
```

---

## Local Development Server

### Linux

Run the launch script from the repository root or `web` directory:

```bash
cd web
chmod +x run.sh
./run.sh
```

The script will automatically source `.env` if present and bind the built-in PHP web server to `http://0.0.0.0:3000`.

### Windows

Run the batch launcher:

```cmd
cd web
run.bat
```

Navigate to `http://localhost:3000` in your web browser.

---

## Production Deployment Guides

### Option 1: Caddy Web Server (Recommended)

Caddy provides automatic HTTPS and reverse proxying with minimal configuration:

```caddyfile
flyaway.example.com {
    root * /opt/flyaway/web/wwwroot
    php_fastcgi unix//run/php-fpm/php-fpm.sock
    file_server

    # Security headers
    header {
        Strict-Transport-Security "max-age=31536000; includeSubDomains; preload"
        X-Content-Type-Options "nosniff"
        X-Frame-Options "DENY"
        Referrer-Policy "no-referrer"
    }
}
```

### Option 2: Nginx + PHP-FPM

```nginx
server {
    listen 80;
    server_name flyaway.example.com;
    root /opt/flyaway/web/wwwroot;
    index index.php;

    location / {
        try_files $uri $uri/ /index.php?$query_string;
    }

    location ~ \.php$ {
        include fastcgi_params;
        fastcgi_pass unix:/run/php/php8.2-fpm.sock;
        fastcgi_param SCRIPT_FILENAME $document_root$fastcgi_script_name;
        fastcgi_param FLYAWAY_DS_URL "https://127.0.0.1:8000/";
    }

    location ~ /\. {
        deny all;
    }
}
```
