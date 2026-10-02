/**
 * @file main.cpp
 * @brief FlyAway Edge Scanner Kiosk Client
 *
 * This client runs on attendance kiosk terminals and edge scanning stations
 * (e.g., Raspberry Pi, school entrance PCs, barcode/RFID scanners). It securely
 * communicates with the FlyAway Dedicated Server (ds) over TLS 1.3 / HTTPS to
 * verify student early dismissal credentials in real time.
 */

#include <cpr/cpr.h>

#include <atomic>
#include <chrono>
#include <csignal>
#include <cstdlib>
#include <filesystem>
#include <iomanip>
#include <iostream>
#include <limits>
#include <string>
#include <string_view>
#include <vector>

#ifdef _WIN32
#include <windows.h>
/**
 * @brief Enables Windows 10/11 Virtual Terminal Processing for ANSI colors.
 */
static void enableVirtualTerminalProcessing() {
    HANDLE hOut = GetStdHandle(STD_OUTPUT_HANDLE);
    if (hOut == INVALID_HANDLE_VALUE) {
        return;
    }
    DWORD dwMode = 0;
    if (GetConsoleMode(hOut, &dwMode)) {
        dwMode |= ENABLE_VIRTUAL_TERMINAL_PROCESSING;
        SetConsoleMode(hOut, dwMode);
    }
}
#endif

// Version and default configurations
static constexpr std::string_view CLIENT_VERSION = "1.1.0";
static constexpr std::string_view DEFAULT_SERVER_URL = "https://localhost:8000/";
static constexpr long DEFAULT_TIMEOUT_MS = 5000;

// ANSI color escape sequences
namespace Color {
    static constexpr const char* RESET  = "\033[0m";
    static constexpr const char* BOLD   = "\033[1m";
    static constexpr const char* RED    = "\033[1;31m";
    static constexpr const char* GREEN  = "\033[1;32m";
    static constexpr const char* YELLOW = "\033[1;33m";
    static constexpr const char* CYAN   = "\033[1;36m";
    static constexpr const char* GRAY   = "\033[0;90m";
}

// Global atomic flag for clean signal handling (SIGINT/SIGTERM)
static std::atomic<bool> g_running{true};

static void signalHandler(int signum) {
    (void)signum;
    g_running = false;
}

/**
 * @brief Returns the current system timestamp formatted as [YYYY-MM-DD HH:MM:SS].
 */
static std::string getFormattedTimestamp() {
    auto now = std::chrono::system_clock::now();
    std::time_t now_time = std::chrono::system_clock::to_time_t(now);
    std::tm tm_buf{};
#if defined(_WIN32)
    localtime_s(&tm_buf, &now_time);
#else
    localtime_r(&now_time, &tm_buf);
#endif
    std::ostringstream oss;
    oss << std::put_time(&tm_buf, "%Y-%m-%d %H:%M:%S");
    return oss.str();
}

/**
 * @brief Trims leading and trailing whitespace and carriage returns from a string.
 */
static std::string trimString(std::string_view sv) {
    size_t first = sv.find_first_not_of(" \t\r\n");
    if (first == std::string_view::npos) {
        return "";
    }
    size_t last = sv.find_last_not_of(" \t\r\n");
    return std::string(sv.substr(first, (last - first + 1)));
}

/**
 * @brief Checks if a string contains exclusively numeric digits.
 */
static bool isNumericString(std::string_view sv) {
    if (sv.empty()) {
        return false;
    }
    for (char c : sv) {
        if (c < '0' || c > '9') {
            return false;
        }
    }
    return true;
}

/**
 * @brief Attempts to locate a valid CA certificate file.
 * Checks CLI override, environment variable, and standard relative search paths.
 */
static std::string resolveCertificatePath(const std::string& cliPath) {
    if (!cliPath.empty() && std::filesystem::exists(cliPath)) {
        return cliPath;
    }

    const char* envCert = std::getenv("FLYAWAY_CERT");
    if (envCert != nullptr && envCert[0] != '\0' && std::filesystem::exists(envCert)) {
        return envCert;
    }

    const std::vector<std::string> searchPaths = {
        "cert.pem",
        "../cert.pem",
        "../../cert.pem",
        "/etc/flyaway/cert.pem"
    };

    for (const auto& path : searchPaths) {
        if (std::filesystem::exists(path)) {
            return path;
        }
    }

    return "cert.pem";
}

/**
 * @brief Prints program usage and CLI arguments.
 */
static void printHelp(const char* progName) {
    std::cout << Color::BOLD << "FlyAway Scanner Endpoint Client v" << CLIENT_VERSION << Color::RESET << "\n\n"
              << "Usage: " << progName << " [OPTIONS]\n\n"
              << "Options:\n"
              << "  -u, --url <URL>        Dedicated Server HTTPS URL (default: " << DEFAULT_SERVER_URL << ")\n"
              << "  -t, --token <TOKEN>    Hardware / Session API token\n"
              << "  -c, --cert <PATH>      Path to CA certificate or server cert.pem\n"
              << "  -k, --insecure         Allow insecure SSL connections (skip certificate verification)\n"
              << "  -h, --help             Display this help message and exit\n"
              << "  -v, --version          Display version information and exit\n\n"
              << "Environment Variables:\n"
              << "  FLYAWAY_SERVER_URL     Target server HTTPS address\n"
              << "  FLYAWAY_TOKEN          Hardware authentication token\n"
              << "  FLYAWAY_CERT           CA certificate path for TLS verification\n"
              << "  FLYAWAY_INSECURE_SSL   Set to '1' to disable SSL certificate verification\n"
              << std::endl;
}

int main(int argc, char* argv[]) {
#ifdef _WIN32
    enableVirtualTerminalProcessing();
#endif

    // Register clean signal handling
    std::signal(SIGINT, signalHandler);
    std::signal(SIGTERM, signalHandler);

    std::string serverUrl;
    std::string token;
    std::string certPath;
    bool insecureSsl = false;

    // Read environment variables first
    const char* envUrl = std::getenv("FLYAWAY_SERVER_URL");
    if (envUrl != nullptr && envUrl[0] != '\0') {
        serverUrl = envUrl;
    } else {
        serverUrl = std::string(DEFAULT_SERVER_URL);
    }

    const char* envToken = std::getenv("FLYAWAY_TOKEN");
    if (envToken != nullptr && envToken[0] != '\0') {
        token = envToken;
    }

    const char* envInsecure = std::getenv("FLYAWAY_INSECURE_SSL");
    if (envInsecure != nullptr && (std::string_view(envInsecure) == "1" || std::string_view(envInsecure) == "true")) {
        insecureSsl = true;
    }

    // Parse command line arguments
    for (int i = 1; i < argc; ++i) {
        std::string_view arg = argv[i];
        if (arg == "-h" || arg == "--help") {
            printHelp(argv[0]);
            return 0;
        } else if (arg == "-v" || arg == "--version") {
            std::cout << "FlyAway Scanner Endpoint v" << CLIENT_VERSION << std::endl;
            return 0;
        } else if (arg == "-k" || arg == "--insecure") {
            insecureSsl = true;
        } else if ((arg == "-u" || arg == "--url") && i + 1 < argc) {
            serverUrl = argv[++i];
        } else if ((arg == "-t" || arg == "--token") && i + 1 < argc) {
            token = argv[++i];
        } else if ((arg == "-c" || arg == "--cert") && i + 1 < argc) {
            certPath = argv[++i];
        } else {
            std::cerr << Color::YELLOW << "Unknown option: " << arg << Color::RESET << "\n";
            printHelp(argv[0]);
            return 1;
        }
    }

    // Resolve TLS certificate
    certPath = resolveCertificatePath(certPath);

    std::cout << Color::CYAN << "========================================" << Color::RESET << "\n";
    std::cout << Color::BOLD << "FlyAway Edge Scanner Kiosk Client v" << CLIENT_VERSION << Color::RESET << "\n";
    std::cout << Color::CYAN << "========================================" << Color::RESET << "\n";
    std::cout << "Server URL: " << Color::BOLD << serverUrl << Color::RESET << "\n";
    if (insecureSsl) {
        std::cout << Color::YELLOW << "SSL Verification: DISABLED (insecure)" << Color::RESET << "\n";
    } else {
        std::cout << "SSL CA Certificate: " << certPath;
        if (!std::filesystem::exists(certPath)) {
            std::cout << Color::RED << " [NOT FOUND!]" << Color::RESET;
            std::cerr << "\n" << Color::YELLOW
                      << "Warning: Certificate file '" << certPath << "' does not exist.\n"
                      << "Connections may fail if the server uses a self-signed certificate.\n"
                      << "Pass -k/--insecure for testing or specify --cert <path>." << Color::RESET << "\n";
        } else {
            std::cout << Color::GREEN << " [OK]" << Color::RESET << "\n";
        }
    }

    // Configure CPR SSL options
    cpr::SslOptions sslOpts = insecureSsl
        ? cpr::Ssl(
            cpr::ssl::VerifyHost{false},
            cpr::ssl::VerifyPeer{false},
            cpr::ssl::VerifyStatus{false}
          )
        : cpr::Ssl(
            cpr::ssl::VerifyHost{true},
            cpr::ssl::VerifyPeer{true},
            cpr::ssl::VerifyStatus{false},
            cpr::ssl::CaInfo{certPath}
          );

    // Prompt for token if not supplied via env or flag
    if (token.empty()) {
        std::cout << "Enter scanner authorization token: ";
        if (!std::getline(std::cin, token) || !g_running) {
            std::cout << "\nOperation aborted.\n";
            return 0;
        }
        token = trimString(token);
    }

    if (token.empty()) {
        std::cerr << Color::RED << "Error: Authorization token cannot be empty." << Color::RESET << "\n";
        return 1;
    }

    std::cout << Color::GRAY << "Ready to scan. Present student barcode/RFID or enter ID ('quit' to exit)." << Color::RESET << "\n\n";

    // Main interactive scan loop
    std::string inputLine;
    while (g_running) {
        std::cout << Color::BOLD << "Scan Badge ID > " << Color::RESET;
        if (!std::getline(std::cin, inputLine)) {
            // EOF encountered (e.g. redirected input stream or terminal closed)
            std::cout << "\nInput stream closed. Exiting.\n";
            break;
        }

        std::string rawId = trimString(inputLine);
        if (rawId.empty()) {
            continue;
        }

        // Sentinel checks for interactive exit
        if (rawId == "quit" || rawId == "exit" || rawId == "-1" || rawId == "q") {
            std::cout << "Shutting down scanner client.\n";
            break;
        }

        // Validate numeric input
        if (!isNumericString(rawId)) {
            std::cerr << Color::YELLOW << "[!] Invalid input: Student ID must be numeric digits." << Color::RESET << "\n\n";
            continue;
        }

        // Build JSON request payload
        // Format expected by ds: {"sendrecord":"<studentid>","token":"<token>"}
        std::string query = "{\"sendrecord\":\"" + rawId + "\",\"token\":\"" + token + "\"}";

        std::string timeStr = getFormattedTimestamp();

        // Dispatch HTTPS POST request to dedicated server
        cpr::Response r = cpr::Post(
            cpr::Url{serverUrl},
            cpr::Body{query},
            cpr::Header{{"Content-Type", "application/json"}},
            cpr::Timeout{DEFAULT_TIMEOUT_MS},
            sslOpts
        );

        if (r.error) {
            std::cerr << Color::RED << "[" << timeStr << "] Connection Error: "
                      << r.error.message << Color::RESET << "\n";
            if (r.error.code == cpr::ErrorCode::SSL_CONNECT_ERROR) {
                std::cerr << Color::YELLOW << "Tip: Ensure '" << certPath
                          << "' matches the server certificate, or run with --insecure for development."
                          << Color::RESET << "\n";
            }
            std::cerr << "\n";
            continue;
        }

        if (r.status_code != 200) {
            std::cerr << Color::RED << "[" << timeStr << "] Server Error (HTTP "
                      << r.status_code << "): " << r.text << Color::RESET << "\n\n";
            continue;
        }

        // Parse verification response
        if (r.text.find("APPROVED") != std::string::npos) {
            std::cout << Color::GREEN << "[APPROVED] " << Color::RESET
                      << "Student #" << Color::BOLD << rawId << Color::RESET
                      << " early dismissal verified (" << timeStr << ")\n\n";
        } else if (r.text.find("REJECTED") != std::string::npos) {
            std::cout << Color::RED << "[REJECTED] " << Color::RESET
                      << "Student #" << Color::BOLD << rawId << Color::RESET
                      << " is NOT authorized for early dismissal (" << timeStr << ")\n\n";
        } else if (r.text.find("REJECTED_TOKEN") != std::string::npos) {
            std::cerr << Color::RED << "[SECURITY ALERT] Token '" << token
                      << "' is invalid or unauthorized!" << Color::RESET << "\n\n";
        } else {
            std::cout << Color::CYAN << "[" << timeStr << "] Response: "
                      << r.text << Color::RESET << "\n\n";
        }
    }

    std::cout << "Clean shutdown completed.\n";
    return 0;
}
