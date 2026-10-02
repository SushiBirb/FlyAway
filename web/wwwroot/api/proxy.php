<?php
/**
 * FlyAway API Reverse Proxy.
 *
 * Secure internal proxy connecting the PHP web portal to the Dedicated Server (ds:8000)
 * over TLS. Validates sessions to prevent SSRF vulnerabilities and proxies requests safely.
 * Supports cURL with fallback to native PHP stream contexts.
 */

error_reporting(E_ALL & ~E_DEPRECATED & ~E_NOTICE);

$sessDir = dirname(__DIR__, 2) . "/sessions";
if (!is_dir($sessDir)) {
    @mkdir($sessDir, 0700, true);
}
if (is_dir($sessDir) && is_writable($sessDir)) {
    session_save_path($sessDir);
}

if (session_status() === PHP_SESSION_NONE) {
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

// 1. Primary transport: cURL
if (function_exists('curl_init')) {
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
        curl_setopt($ch, CURLOPT_SSL_VERIFYPEER, false);
        curl_setopt($ch, CURLOPT_SSL_VERIFYHOST, 0);
    }

    $response = curl_exec($ch);
    $httpCode = curl_getinfo($ch, CURLINFO_HTTP_CODE);
    $curlError = curl_error($ch);

    if ($response === false) {
        http_response_code(502);
        echo json_encode([
            "message" => "502 Bad Gateway: Failed to connect to Dedicated Server via cURL",
            "error" => $curlError
        ]);
        exit();
    }

    http_response_code($httpCode ?: 200);
    echo $response;
    exit();
}

// 2. Fallback transport: Native PHP Streams (if ext-curl is unavailable)
$sslOpts = [
    'verify_peer' => file_exists($certPath),
    'verify_peer_name' => file_exists($certPath),
    'allow_self_signed' => true
];
if (file_exists($certPath)) {
    $sslOpts['cafile'] = $certPath;
}

$context = stream_context_create([
    'http' => [
        'method'  => 'POST',
        'header'  => "Content-Type: application/json\r\nAccept: application/json\r\n",
        'content' => $input,
        'timeout' => 15,
        'ignore_errors' => true
    ],
    'ssl' => $sslOpts
]);

$response = @file_get_contents($backendUrl, false, $context);
if ($response === false) {
    http_response_code(502);
    echo json_encode([
        "message" => "502 Bad Gateway: Failed to connect to Dedicated Server via PHP Streams"
    ]);
    exit();
}

$status = 200;
$headers = function_exists('http_get_last_response_headers') ? @http_get_last_response_headers() : [];
if (!empty($headers[0]) && preg_match('#HTTP/\S+\s+(\d+)#', $headers[0], $matches)) {
    $status = (int)$matches[1];
}

http_response_code($status);
echo $response;
