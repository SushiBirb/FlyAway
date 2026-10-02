# FlyAway Scanner Endpoint Client (ep)

The FlyAway Scanner Endpoint (`ep`) is a high-performance, edge attendance scanner kiosk client written in modern C++20. It runs on physical entrance scanning kiosks, security desks, and attendance terminals equipped with barcode scanners, magnetic stripe readers, or RFID card scanners.

The client communicates directly with the FlyAway Dedicated Server (`ds`) over TLS 1.3 / HTTPS to authorize and log student early dismissal credentials in real time.

---

## Features

- High-speed attendance verification via HTTPS POST requests using libcpr (C++ Requests).
- End-to-end TLS 1.3 encryption with strict certificate validation and auto-discovery.
- Barcode and RFID scanner input normalization (keyboard-wedge input, line trimming, digit validation).
- Cross-platform ANSI color-coded terminal output (green for APPROVED, red for REJECTED) with Windows Virtual Terminal Processing support.
- Non-interactive and kiosk-ready: supports graceful shutdown on SIGINT/SIGTERM, EOF detection, and configurable network timeouts.
- Flexible configuration via command-line arguments and environment variables.

---

## Prerequisites and Dependencies

### Linux (Arch Linux, Ubuntu, Debian, Fedora, Alpine)
- CMake 3.20 or newer
- C++20 compatible compiler:
  - GCC 11 or newer (`g++`)
  - Clang 13 or newer (`clang++`)
- OpenSSL development headers (`openssl` / `libssl-dev`)
- libcurl development libraries (`curl` / `libcurl4-openssl-dev`)
- Git (used by CMake FetchContent to retrieve libcpr)

#### Package Installation Commands:
- **Arch Linux**:
  ```bash
  sudo pacman -S base-devel cmake curl openssl git
  ```
- **Ubuntu / Debian**:
  ```bash
  sudo apt-get update && sudo apt-get install -y build-essential cmake libcurl4-openssl-dev libssl-dev git
  ```
- **Fedora**:
  ```bash
  sudo dnf install -y gcc-c++ cmake libcurl-devel openssl-devel git
  ```

### Windows (Windows 10, Windows 11, Windows Server)
- Visual Studio 2022 (Community or higher) with "Desktop development with C++" workload, or Visual Studio Build Tools.
- CMake (bundled with Visual Studio or via `winget install Kitware.CMake`).
- Git for Windows (`winget install Git.Git`).

---

## Building the Client

### Linux Build Instructions

```bash
cd ep

# Generate build configuration
cmake -B build -S . -DCMAKE_BUILD_TYPE=Release

# Compile binary using available CPU cores
cmake --build build -j$(nproc)
```

The compiled binary will be placed at `ep/build/ep`.

### Windows Build Instructions (Developer Command Prompt / PowerShell)

```powershell
cd ep

# Generate Visual Studio solution
cmake -B build -S .

# Build Release configuration
cmake --build build --config Release
```

The compiled binary will be placed at `ep/build/Release/ep.exe`.

---

## Configuration and Usage

The endpoint client accepts options via command-line arguments or environment variables.

### Command-Line Arguments

```text
Usage: ep [OPTIONS]

Options:
  -u, --url <URL>        Dedicated Server HTTPS URL (default: https://localhost:8000/)
  -t, --token <TOKEN>    Hardware / Session API token
  -c, --cert <PATH>      Path to CA certificate or server cert.pem
  -k, --insecure         Allow insecure SSL connections (skip certificate verification)
  -h, --help             Display this help message and exit
  -v, --version          Display version information and exit
```

### Environment Variables

| Variable | Description | Default |
| :--- | :--- | :--- |
| `FLYAWAY_SERVER_URL` | Dedicated Server HTTPS address | `https://localhost:8000/` |
| `FLYAWAY_TOKEN` | Hardware authorization API token | *(prompts if unset)* |
| `FLYAWAY_CERT` | CA certificate path for TLS verification | `cert.pem` |
| `FLYAWAY_INSECURE_SSL` | Set to `1` or `true` to skip certificate validation | `false` |

---

## Running the Endpoint Scanner

### Interactive Mode

```bash
# Export the scanner token and run the binary
export FLYAWAY_TOKEN="e1111111-1111-1111-1111-111111111111"
./build/ep
```

### Headless / Kiosk Startup Command

```bash
./build/ep --url https://ds.eastern.net:8000/ --token e1111111-1111-1111-1111-111111111111 --cert /etc/flyaway/cert.pem
```

When a student scans their badge or enters their student ID:
- If early dismissal is permitted:
  ```text
  [APPROVED] Student #100204 early dismissal verified (2026-10-01 23:47:39)
  ```
- If early dismissal is blocked:
  ```text
  [REJECTED] Student #100001 is NOT authorized for early dismissal (2026-10-01 23:47:39)
  ```
- If the token is revoked or unauthorized:
  ```text
  [SECURITY ALERT] Token '...' is invalid or unauthorized!
  ```

Type `quit`, `exit`, or `-1` to terminate the scanner process cleanly.

---

## Systemd Service Setup (Linux Kiosks)

To run the endpoint scanner client automatically at system startup on kiosk hardware:

Create `/etc/systemd/system/flyaway-endpoint.service`:

```ini
[Unit]
Description=FlyAway Attendance Scanner Kiosk Client
After=network.target

[Service]
Type=simple
User=kiosk
WorkingDirectory=/opt/flyaway/ep
ExecStart=/opt/flyaway/ep/bin/ep --url https://localhost:8000/ --token e1111111-1111-1111-1111-111111111111 --cert /opt/flyaway/ep/cert.pem
Restart=always
RestartSec=5

[Install]
WantedBy=multi-user.target
```

Enable and start the service:
```bash
sudo systemctl daemon-reload
sudo systemctl enable --now flyaway-endpoint.service
```
