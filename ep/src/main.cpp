#include <cpr/cpr.h>
#include <iostream>
#include <string>
#include <limits>

// Enable SSL verification for production to ensure secure data-in-transit
static constexpr bool VERIFY_SSL_HOST = true;
static constexpr bool VERIFY_SSL_PEER = true;
static constexpr bool VERIFY_SSL_STATUS = false; // Status (OCSP stapling) can be false for self-signed

// Configure server URL here (supports FLYAWAY_SERVER_URL environment override)
static const std::string DEFAULT_SERVER_URL = "https://localhost:8000/";

int main()
{
    const char* envUrl = std::getenv("FLYAWAY_SERVER_URL");
    std::string serverUrl = (envUrl != nullptr && envUrl[0] != '\0') ? envUrl : DEFAULT_SERVER_URL;

    const char* envCert = std::getenv("FLYAWAY_CERT");
    std::string certPath = (envCert != nullptr && envCert[0] != '\0') ? envCert : "cert.pem";

    // Trust the self-signed certificate by referencing cert.pem
    cpr::SslOptions sslOpts = cpr::Ssl(
        cpr::ssl::VerifyHost{VERIFY_SSL_HOST},
        cpr::ssl::VerifyPeer{VERIFY_SSL_PEER},
        cpr::ssl::VerifyStatus{VERIFY_SSL_STATUS},
        cpr::ssl::CaInfo{certPath}
    );

    std::cout << "FlyAway Scanner Endpoint" << std::endl;
    std::cout << "Server URL: " << serverUrl << std::endl;

    std::string token;
    const char* envToken = std::getenv("FLYAWAY_TOKEN");
    if (envToken != nullptr && envToken[0] != '\0') {
        token = envToken;
        std::cout << "Using authorization token from environment." << std::endl;
    } else {
        std::cout << "Enter authorization token: ";
        std::getline(std::cin, token);
    }

    while (true) {
        int id;
        std::cout << "\nScan or enter Student ID (-1 to quit): ";
        std::cin >> id;

        if (std::cin.fail()) {
            std::cerr << "Invalid input. Please enter a numeric student ID." << std::endl;
            std::cin.clear();
            std::cin.ignore(std::numeric_limits<std::streamsize>::max(), '\n');
            continue;
        }

        if (id == -1) {
            std::cout << "Exiting endpoint." << std::endl;
            break;
        }

        std::string query = "{\"sendrecord\":\"" + std::to_string(id) + "\",\"token\":\"" + token + "\"}";

        cpr::Response r = cpr::Post(
            cpr::Url{serverUrl},
            cpr::Body{query},
            cpr::Header{{"Content-Type", "application/json"}},
            cpr::Timeout{5000},
            sslOpts
        );

        if (r.error) {
            std::cerr << "Request failed: " << r.error.message << std::endl;
        } else if (r.status_code != 200) {
            std::cerr << "Server returned HTTP " << r.status_code << ": " << r.text << std::endl;
        } else {
            if (r.text.find("APPROVED") != std::string::npos) {
                std::cout << "\033[1;32m[APPROVED]\033[0m Student #" << id << " early exit AUTHORIZED." << std::endl;
            } else if (r.text.find("REJECTED") != std::string::npos) {
                std::cout << "\033[1;31m[REJECTED]\033[0m Student #" << id << " early exit DENIED." << std::endl;
            } else {
                std::cout << "Result: " << r.text << std::endl;
            }
        }
    }
    return 0;
}
