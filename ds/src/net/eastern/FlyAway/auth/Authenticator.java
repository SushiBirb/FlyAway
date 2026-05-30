package net.eastern.FlyAway.auth;

import net.eastern.FlyAway.util.DBAPI;
import net.eastern.FlyAway.util.Utils;

import java.time.ZonedDateTime;

/**
 * This Class's Purpose is to handle User Authentication and Token Issuance with the DBM and the API.
 * @author aspectious
 */
public class Authenticator {

    /**
     * Authenticates a user and issues a new token.
     * Auto-migrates legacy SHA-256 password hashes to PBKDF2 on successful login.
     * Invalidates all previous admin tokens for this user.
     */
    public static AuthToken Authenticate_User(String Username, String password, String ssid) {
        DBAPI dbapi = new DBAPI();
        User usr = dbapi.fetchUserByUsername(Username);
        if (usr == null || usr.getPasswordhash() == null) {
            return new AuthToken();
        }
        if (!PasswordHasher.verify(password, usr.getPasswordhash())) {
            return new AuthToken();
        }

        // Auto-rehash legacy SHA-256 passwords to PBKDF2
        if (PasswordHasher.needsRehash(usr.getPasswordhash())) {
            String newHash = PasswordHasher.hash(password);
            if (dbapi.updatePasswordHash(Username, newHash)) {
                Utils.Infoprintln("Password hash upgraded to PBKDF2 for user: " + Username);
            }
        }

        boolean isadm = usr.getPermsum() == 777;
        AuthToken token = new AuthToken(ssid, Username, isadm);
        dbapi.addToken(token);

        // Invalidate all previous admin tokens except the newly issued one
        if (isadm) {
            dbapi.invalidateAllAdminTokensExcept(token.getCode());
        }

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

    public static boolean CheckAdminToken(String code) {
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
        return token.isAdmin();
    }
}
