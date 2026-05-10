package net.eastern.FlyAway.auth;

import net.eastern.FlyAway.util.DBAPI;

import java.time.ZonedDateTime;

/**
 * This Class's Purpose is to handle User Authentication and Token Issuance with the DBM and the API.
 * @author aspectious
 */
public class Authenticator {

    /**
     * Haha User Auth go brrr
     * @param Username
     * @param password
     * @param ssid
     * @return
     */
    public static AuthToken Authenticate_User(String Username, String password, String ssid) {
        User usr = new DBAPI().fetchUserByUsername(Username);
        if (usr == null || usr.getPasswordhash() == null) {
            return new AuthToken();
        }
        if (!PasswordHasher.verify(password, usr.getPasswordhash())) {
            return new AuthToken();
        }
        boolean isadm = usr.getPermsum() == 777;
        AuthToken token = new AuthToken(ssid, Username, isadm);
        new DBAPI().addToken(token);
        return token;
    }

    public static boolean CheckToken(String code) {
        AuthToken token = new DBAPI().fetchToken(code);
        if (token == null) {
            return false;
        }
        if (token.getStatus() != TokenStatus.VALIDATED) {
            return false;
        }
        if (token.getExpirationDate() != null && token.getExpirationDate().isBefore(ZonedDateTime.now())) {
            return false;
        }
        return true;
    }
}
