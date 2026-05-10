#include <cpr/cpr.h>
#include <iostream>
#include <string>
#include <limits>

// TODO: Enable SSL verification for production
// Change these to true to verify server certificates
static constexpr bool VERIFY_SSL_HOST = false;
static constexpr bool VERIFY_SSL_PEER = false;
static constexpr bool VERIFY_SSL_STATUS = false;

// Configure server URL here
static const std::string SERVER_URL = "https://192.168.1.193:8000/";

int main()
{
    cpr::SslOptions sslOpts = cpr::Ssl(
        cpr::ssl::VerifyHost{VERIFY_SSL_HOST},
        cpr::ssl::VerifyPeer{VERIFY_SSL_PEER},
        cpr::ssl::VerifyStatus{VERIFY_SSL_STATUS}
    );

    while (true) {
        int id;
        std::cout << "Enter id (-1 to quit): ";
        std::cin >> id;

        if (std::cin.fail()) {
            std::cerr << "Invalid input. Please enter a number." << std::endl;
            std::cin.clear();
            std::cin.ignore(std::numeric_limits<std::streamsize>::max(), '\n');
            continue;
        }

        if (id == -1) {
            break;
        }

        std::string query = "{\"sendrecord\":\"" + std::to_string(id) + "\"}";
        std::cout << query << std::endl;

        cpr::Response r = cpr::Post(
            cpr::Url{SERVER_URL},
            cpr::Body{query},
            cpr::Header{{"Content-Type", "application/json"}},
            cpr::Timeout{5000},
            sslOpts
        );

        if (r.error) {
            std::cerr << "Request failed: " << r.error.message << std::endl;
        } else if (r.status_code != 200) {
            std::cerr << "Server returned " << r.status_code << ": " << r.text << std::endl;
        } else {
            std::cout << r.text << std::endl;
        }
    }
    return 0;
}
