<?php
function validateToken($token, $sessionid) {
    $url = "https://localhost:8000/";
    $data = json_encode([
        'ValidateToken' => [
            'token' => $token,
            'sessionID' => $sessionid
        ]
    ]);

    $options = array(
        'Content-Type:application/json',
    );

    $ch = curl_init($url);
    curl_setopt($ch, CURLOPT_POSTFIELDS, $data);
    curl_setopt($ch, CURLOPT_HTTPHEADER, $options);
    curl_setopt($ch, CURLOPT_RETURNTRANSFER, true);
    curl_setopt($ch, CURLOPT_SSL_VERIFYPEER, true);
    curl_setopt($ch, CURLOPT_SSL_VERIFYHOST, 2);
    curl_setopt($ch, CURLOPT_CAINFO, dirname(__DIR__) . "/cert.pem");
    curl_setopt($ch, CURLOPT_POST, true);

    $result = curl_exec($ch);
    curl_close($ch);

    $decoded = json_decode($result);
    $code = isset($decoded->ValidationResponse->result) ? $decoded->ValidationResponse->result : "500";
    return $code;
}
?>