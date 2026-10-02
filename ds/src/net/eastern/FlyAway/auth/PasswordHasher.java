package net.eastern.FlyAway.auth;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.security.spec.InvalidKeySpecException;
import java.util.HexFormat;

public class PasswordHasher {

    private static final int ITERATIONS = 65536;
    private static final int KEY_LENGTH = 256;
    private static final int SALT_LENGTH = 16;
    private static final String ALGORITHM = "PBKDF2WithHmacSHA256";
    private static final String PREFIX = "PBKDF2$";

    /**
     * Hashes a password (or client-side SHA-256 hash) using PBKDF2WithHmacSHA256.
     * Output format: PBKDF2$<iterations>$<saltHex>$<hashHex>
     */
    public static String hash(String password) {
        if (password == null) {
            throw new IllegalArgumentException("Password cannot be null");
        }
        SecureRandom random = new SecureRandom();
        byte[] salt = new byte[SALT_LENGTH];
        random.nextBytes(salt);

        byte[] hash = pbkdf2(password.toCharArray(), salt, ITERATIONS, KEY_LENGTH);

        HexFormat hex = HexFormat.of();
        return PREFIX + ITERATIONS + "$" + hex.formatHex(salt) + "$" + hex.formatHex(hash);
    }

    /**
     * Verifies a password against a stored hash.
     * Supports modern PBKDF2 hashes and legacy SHA-256 hex strings.
     */
    public static boolean verify(String password, String storedHash) {
        if (password == null || storedHash == null) {
            return false;
        }

        if (storedHash.startsWith(PREFIX)) {
            String[] parts = storedHash.split("\\$");
            if (parts.length != 4) {
                return false;
            }
            try {
                int iterations = Integer.parseInt(parts[1]);
                HexFormat hex = HexFormat.of();
                byte[] salt = hex.parseHex(parts[2]);
                byte[] expectedHash = hex.parseHex(parts[3]);

                byte[] actualHash = pbkdf2(password.toCharArray(), salt, iterations, expectedHash.length * 8);
                return MessageDigest.isEqual(expectedHash, actualHash);
            } catch (Exception e) {
                return false;
            }
        }

        // Legacy SHA-256 fallback:
        // Client sends SHA-256 hex string. Compare directly or via SHA-256 digest.
        if (MessageDigest.isEqual(storedHash.toLowerCase().getBytes(StandardCharsets.UTF_8),
                                  password.toLowerCase().getBytes(StandardCharsets.UTF_8))) {
            return true;
        }

        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(password.getBytes(StandardCharsets.UTF_8));
            String hexDigest = HexFormat.of().formatHex(digest);
            return MessageDigest.isEqual(storedHash.toLowerCase().getBytes(StandardCharsets.UTF_8),
                                      hexDigest.toLowerCase().getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            return false;
        }
    }

    /**
     * Checks if the stored hash is not in PBKDF2 format and needs upgrade.
     */
    public static boolean needsRehash(String storedHash) {
        return storedHash == null || !storedHash.startsWith(PREFIX);
    }

    private static byte[] pbkdf2(char[] password, byte[] salt, int iterations, int keyLength) {
        try {
            PBEKeySpec spec = new PBEKeySpec(password, salt, iterations, keyLength);
            SecretKeyFactory skf = SecretKeyFactory.getInstance(ALGORITHM);
            return skf.generateSecret(spec).getEncoded();
        } catch (NoSuchAlgorithmException | InvalidKeySpecException e) {
            throw new RuntimeException("PBKDF2 hashing failed", e);
        }
    }
}
