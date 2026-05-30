<?php
    include(__DIR__ . "/../loader.php");
    include(__DIR__ . "/../validateToken.php");

    // Only accept POST requests for token submission
    if ($_SERVER['REQUEST_METHOD'] !== 'POST') {
        header("Location: /login/");
        exit();
    }

    // CSRF validation
    $csrf = isset($_POST['csrf_token']) ? $_POST['csrf_token'] : '';
    if (!hash_equals($_SESSION['csrf_token'], $csrf)) {
        header("Location: /login/");
        exit();
    }

    // Read token from POST body (not URL)
    $token = isset($_POST["token"]) ? $_POST["token"] : "";
    
    // Validate token format (UUID only)
    if (!preg_match('/^[a-f0-9\-]{36}$/', $token)) {
        header("Location: /login/");
        exit();
    }

    $ssid = session_id();
    $code = validateToken($token, $ssid);

    if ($code === "200" || $code == 200) {
        // Regenerate session ID to prevent session fixation
        session_regenerate_id(true);
        
        $_SESSION["loggedin"] = true;
        $_SESSION["token"] = $token;
        // Regenerate CSRF token after login
        $_SESSION['csrf_token'] = bin2hex(random_bytes(32));
        header("Location: /");
        exit();
    } else {
        header("Location: /login/");
        exit();
    }
?>