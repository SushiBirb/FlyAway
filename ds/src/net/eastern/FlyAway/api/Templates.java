package net.eastern.FlyAway.api;

import net.eastern.FlyAway.auth.AuthToken;
import net.eastern.FlyAway.auth.TokenStatus;
import org.json.JSONObject;

import java.time.format.DateTimeFormatter;

public class Templates {

    static String generateAuthReturnJSON(String username, String ssid, AuthToken token) {
        JSONObject authResponse = new JSONObject();
        authResponse.put("username", username);
        authResponse.put("sessionID", ssid);
        if (token.getStatus() != TokenStatus.VALIDATED) {
            authResponse.put("result", "401");
        } else {
            authResponse.put("result", "200");
            JSONObject tokenObj = new JSONObject();
            tokenObj.put("code", token.getCode());
            tokenObj.put("exp", token.getExpirationDate().format(DateTimeFormatter.ISO_DATE));
            authResponse.put("token", tokenObj);
        }
        JSONObject root = new JSONObject();
        root.put("AuthResponse", authResponse);
        return root.toString();
    }

    static String generateMessage(String message) {
        JSONObject root = new JSONObject();
        root.put("Message", message);
        return root.toString();
    }

    static String generateValidationResponseJSON(boolean validated) {
        JSONObject validationResponse = new JSONObject();
        validationResponse.put("result", validated ? "200" : "401");
        JSONObject root = new JSONObject();
        root.put("ValidationResponse", validationResponse);
        return root.toString();
    }
}
