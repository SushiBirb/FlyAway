package net.eastern.FlyAway.util;

import net.eastern.FlyAway.auth.AuthToken;
import net.eastern.FlyAway.auth.TokenStatus;
import net.eastern.FlyAway.auth.User;
import net.eastern.FlyAway.dbm.Dbm;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Objects;

/**
 * Database API Helper.
 *
 * Encapsulates high-level domain operations against the FlyAway persistence layer,
 * including user accounts, authorization tokens, student badge permissions, and scan audit records.
 */
public class DBAPI {
    private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    public User fetchUserByUsername(String username) {
        Dbm dbm = new Dbm();
        try (Connection conn = dbm.getConnection();
             PreparedStatement pstmt = conn.prepareStatement("SELECT un, password, permsum, creationdate, lastlogin FROM accts WHERE un = ?")) {
            pstmt.setString(1, username);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }

                String un = rs.getString("un");
                String pwd = rs.getString("password");
                int permsum = rs.getInt("permsum");
                String creationDateStr = rs.getString("creationdate");
                String lastLoginStr = rs.getString("lastlogin");

                LocalDateTime creationDate = LocalDateTime.parse(creationDateStr.replace(" ", "T"));
                if (lastLoginStr == null || Objects.equals(lastLoginStr, "null")) {
                    return new User(un, pwd, permsum, creationDate);
                } else {
                    LocalDateTime lastLogin = LocalDateTime.parse(lastLoginStr.replace(" ", "T"));
                    return new User(un, pwd, permsum, creationDate, lastLogin);
                }
            }
        } catch (SQLException ex) {
            Utils.Errprintln("fetchUserByUsername error: " + ex.getMessage());
            return null;
        }
    }

    public void addToken(AuthToken token) {
        String status = token.getStatus() == TokenStatus.VALIDATED ? "VALIDATED" : "INVALIDATED";
        int isAdmin = token.isAdmin() ? 1 : 0;

        String createdate = token.getCreationDate().format(ISO_FORMATTER);
        String expdate = token.getExpirationDate() != null ? token.getExpirationDate().format(ISO_FORMATTER) : null;

        Dbm dbm = new Dbm();
        try (Connection conn = dbm.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(
                     "INSERT INTO tokens (token, status, admin, sessionid, creationdate, expdate, owner) VALUES (?, ?, ?, ?, ?, ?, ?)")) {
            pstmt.setString(1, token.getCode());
            pstmt.setString(2, status);
            pstmt.setInt(3, isAdmin);
            pstmt.setString(4, token.getSsid());
            pstmt.setString(5, createdate);
            pstmt.setString(6, expdate);
            pstmt.setString(7, token.getOwner());
            pstmt.executeUpdate();
        } catch (SQLException ex) {
            Utils.Errprintln("addToken error: " + ex.getMessage());
        }
    }

    public AuthToken fetchToken(String tokencode) {
        Dbm dbm = new Dbm();
        try (Connection conn = dbm.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(
                     "SELECT id, token, status, admin, sessionid, creationdate, expdate, owner FROM tokens WHERE token = ?")) {
            pstmt.setString(1, tokencode);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }

                String code = rs.getString("token");
                TokenStatus status = "VALIDATED".equals(rs.getString("status")) ? TokenStatus.VALIDATED : TokenStatus.INVALIDATED;
                boolean isAdmin = rs.getInt("admin") == 1;
                String sessionid = rs.getString("sessionid");
                String creationDateStr = rs.getString("creationdate");
                String expStr = rs.getString("expdate");
                String owner = rs.getString("owner");
                if (owner == null) {
                    owner = "SYSTEM";
                }

                ZonedDateTime cdt = LocalDateTime.parse(creationDateStr.replace(" ", "T"))
                        .atOffset(ZoneOffset.UTC)
                        .atZoneSameInstant(ZoneId.systemDefault());

                if (expStr == null || expStr.equals("null")) {
                    return new AuthToken(sessionid, code, owner, status, isAdmin, cdt);
                } else {
                    ZonedDateTime edt = LocalDateTime.parse(expStr.replace(" ", "T"))
                            .atOffset(ZoneOffset.UTC)
                            .atZoneSameInstant(ZoneId.systemDefault());
                    return new AuthToken(sessionid, code, owner, status, isAdmin, cdt, edt);
                }
            }
        } catch (SQLException e) {
            Utils.Errprintln("fetchToken error: " + e.getMessage());
            return null;
        }
    }

    /**
     * Verifies student badge early dismissal permission in a single optimized query,
     * auto-enrolling unknown student IDs with default blocked permission and logging the scan audit record.
     *
     * @param idnum The numeric student badge identifier.
     * @return true if early dismissal is permitted; false otherwise.
     */
    public boolean checkBadge(int idnum) {
        Dbm dbm = new Dbm();
        try (Connection conn = dbm.getConnection()) {
            boolean exitAllowed;

            // Check if student exists in database
            try (PreparedStatement checkStmt = conn.prepareStatement("SELECT exitallowed FROM users WHERE studentid = ?")) {
                checkStmt.setInt(1, idnum);
                try (ResultSet rs = checkStmt.executeQuery()) {
                    if (rs.next()) {
                        exitAllowed = rs.getBoolean("exitallowed");
                    } else {
                        // Auto-enroll new student with blocked status
                        exitAllowed = false;
                        try (PreparedStatement insertStmt = conn.prepareStatement("INSERT INTO users (studentid, exitallowed) VALUES (?, FALSE)")) {
                            insertStmt.setInt(1, idnum);
                            insertStmt.executeUpdate();
                        }
                    }
                }
            }

            // Record scan audit log
            String status = exitAllowed ? "APPROVED" : "REJECTED";
            String nowStr = LocalDateTime.now().format(ISO_FORMATTER);
            try (PreparedStatement insertRecord = conn.prepareStatement("INSERT INTO RECORDS (sid, timestamp, result) VALUES (?, ?, ?)")) {
                insertRecord.setInt(1, idnum);
                insertRecord.setString(2, nowStr);
                insertRecord.setString(3, status);
                insertRecord.executeUpdate();
            }

            return exitAllowed;
        } catch (SQLException e) {
            Utils.Errprintln("checkBadge error for #" + idnum + ": " + e.getMessage());
            return false;
        }
    }

    public boolean updatePasswordHash(String username, String newHash) {
        Dbm dbm = new Dbm();
        try (Connection conn = dbm.getConnection();
             PreparedStatement pstmt = conn.prepareStatement("UPDATE accts SET password = ? WHERE un = ?")) {
            pstmt.setString(1, newHash);
            pstmt.setString(2, username);
            int rows = pstmt.executeUpdate();
            return rows > 0;
        } catch (SQLException ex) {
            Utils.Errprintln("updatePasswordHash error: " + ex.getMessage());
            return false;
        }
    }

    public void invalidateAllAdminTokensExcept(String keepTokenCode) {
        Dbm dbm = new Dbm();
        try (Connection conn = dbm.getConnection();
             PreparedStatement pstmt = conn.prepareStatement(
                     "UPDATE tokens SET status = 'INVALIDATED' WHERE admin = 1 AND status = 'VALIDATED' AND token != ?")) {
            pstmt.setString(1, keepTokenCode);
            pstmt.executeUpdate();
        } catch (SQLException ex) {
            Utils.Errprintln("invalidateAllAdminTokensExcept error: " + ex.getMessage());
        }
    }
}
