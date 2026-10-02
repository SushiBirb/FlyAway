<?php
/**
 * FlyAway API Reverse Proxy.
 *
 * Secure internal proxy connecting the PHP web portal to the Dedicated Server (ds:8000)
 * over TLS. Validates sessions to prevent SSRF vulnerabilities and proxies requests safely.
 */

if (session_status() === PHP_SESSION_NONE) {
    $sessDir = dirname(__DIR__, 2) . "/sessions";
    if (is_dir($sessDir) && is_writable($sessDir)) {
        session_save_path($sessDir);
    }
    session_start();
}

header('Content-Type: application/json; charset=utf-8');
header("X-Content-Type-Options: nosniff");
header("X-Frame-Options: DENY");

if ($_SERVER['REQUEST_METHOD'] !== 'POST') {
    http_response_code(405);
    echo json_encode(["message" => "405 Method Not Allowed"]);
    exit();
}

$input = file_get_contents('php://input');
if (empty($input)) {
    http_response_code(400);
    echo json_encode(["message" => "400 Empty Request Body"]);
    exit();
}

$decoded = json_decode($input, true);
if ($decoded === null) {
    http_response_code(400);
    echo json_encode(["message" => "400 Invalid JSON Payload"]);
    exit();
}

// Security: Restrict unauthenticated calls to login and validation endpoints
$isLogin = isset($decoded['LoginRequest']) || isset($decoded['ValidateToken']);
if (!$isLogin && empty($_SESSION['loggedin'])) {
    http_response_code(401);
    echo json_encode(["message" => "401 Unauthorized Session"]);
    exit();
}

$backendUrl = getenv('FLYAWAY_DS_URL') ?: "https://localhost:8000/";
$certPath = dirname(__DIR__, 2) . "/cert.pem";

$ch = curl_init($backendUrl);
curl_setopt($ch, CURLOPT_POST, true);
curl_setopt($ch, CURLOPT_POSTFIELDS, $input);
curl_setopt($ch, CURLOPT_HTTPHEADER, [
    'Content-Type: application/json',
    'Accept: application/json'
]);
curl_setopt($ch, CURLOPT_RETURNTRANSFER, true);
curl_setopt($ch, CURLOPT_TIMEOUT, 15);

if (file_exists($certPath)) {
    curl_setopt($ch, CURLOPT_SSL_VERIFYPEER, true);
    curl_setopt($ch, CURLOPT_SSL_VERIFYHOST, 2);
    curl_setopt($ch, CURLOPT_CAINFO, $certPath);
} else {
    // Fallback for self-signed certificates without CA bundle
    curl_setopt($ch, CURLOPT_SSL_VERIFYPEER, false);
    curl_setopt($ch, CURLOPT_SSL_VERIFYHOST, 0);
}

$response = curl_exec($ch);
$httpCode = curl_getinfo($ch, CURLINFO_HTTP_CODE);
$curlError = curl_error($ch);
curl_close($ch);

if ($response === false) {
    http_response_code(502);
    echo json_encode([
        "message" => "502 Bad Gateway: Failed to connect to Dedicated Server",
        "error" => $curlError
    ]);
    exit();
}

http_response_code($httpCode ?: 200);
echo $response;
