<?php
// Security headers
header_remove('X-Powered-By');
header("Access-Control-Allow-Origin: http://localhost:3000");
header("X-Content-Type-Options: nosniff");
header("X-Frame-Options: DENY");
header("Referrer-Policy: no-referrer");
header("Content-Security-Policy: default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline' https://fonts.googleapis.com; font-src 'self' https://fonts.gstatic.com; img-src 'self' data:");

// Secure session configuration
$sessDir = dirname(__DIR__) . "/sessions";
if (!is_dir($sessDir)) {
    @mkdir($sessDir, 0700, true);
}
if (is_dir($sessDir) && is_writable($sessDir)) {
    session_save_path($sessDir);
}

session_set_cookie_params([
    'lifetime' => 0,
    'path' => '/',
    'httponly' => true,
    'samesite' => 'Strict',
]);

if (session_status() === PHP_SESSION_NONE) {
    session_start();
}

// Generate CSRF token if not set
if (!isset($_SESSION['csrf_token'])) {
    $_SESSION['csrf_token'] = bin2hex(random_bytes(32));
}

$cond1 = (!isset($_SESSION["loggedin"]) || $_SESSION["loggedin"] !== true);
$cond2 = ($_SERVER["REQUEST_URI"] != "/login/");
$cond3 = (!str_contains($_SERVER["SCRIPT_NAME"], "result.php"));
$will_redirect = $cond1 && $cond2 && $cond3;

if ($will_redirect) {
    header("location: /login/");
    exit();
}
if (isset($_SESSION["loggedin"]) && $_SERVER["REQUEST_URI"] == "/login/") {
    header("location: /");
    exit();
}
?>