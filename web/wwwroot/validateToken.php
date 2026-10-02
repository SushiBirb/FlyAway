<?php
/**
 * Token Validation Helper.
 *
 * Verifies issued tokens against the Dedicated Server over HTTPS.
 */
function validateToken($token, $sessionid) {
    $url = getenv('FLYAWAY_DS_URL') ?: "https://localhost:8000/";
    $certPath = dirname(__DIR__) . "/cert.pem";

    $data = json_encode([
        'ValidateToken' => [
            'token' => $token,
            'sessionID' => $sessionid
        ]
    ]);

    if (function_exists('curl_init')) {
        $ch = curl_init($url);
        curl_setopt($ch, CURLOPT_POSTFIELDS, $data);
        curl_setopt($ch, CURLOPT_HTTPHEADER, ['Content-Type: application/json', 'Accept: application/json']);
        curl_setopt($ch, CURLOPT_RETURNTRANSFER, true);
        curl_setopt($ch, CURLOPT_TIMEOUT, 10);
        curl_setopt($ch, CURLOPT_POST, true);

        if (file_exists($certPath)) {
            curl_setopt($ch, CURLOPT_SSL_VERIFYPEER, true);
            curl_setopt($ch, CURLOPT_SSL_VERIFYHOST, 2);
            curl_setopt($ch, CURLOPT_CAINFO, $certPath);
        } else {
            curl_setopt($ch, CURLOPT_SSL_VERIFYPEER, false);
            curl_setopt($ch, CURLOPT_SSL_VERIFYHOST, 0);
        }

        $result = curl_exec($ch);
        $decoded = json_decode($result, true);
        return isset($decoded['ValidationResponse']['result']) ? $decoded['ValidationResponse']['result'] : "500";
    }

    // Stream context fallback
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
            'content' => $data,
            'timeout' => 10,
            'ignore_errors' => true
        ],
        'ssl' => $sslOpts
    ]);

    $result = @file_get_contents($url, false, $context);
    if ($result === false) {
        return "500";
    }
    $decoded = json_decode($result, true);
    return isset($decoded['ValidationResponse']['result']) ? $decoded['ValidationResponse']['result'] : "500";
}
?>