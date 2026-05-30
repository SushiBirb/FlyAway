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

public class DBAPI {
    public User fetchUserByUsername(String username) {
        Dbm dbm = new Dbm();
        Connection conn = null;
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        try {
            conn = dbm.getConnection();
            pstmt = conn.prepareStatement("SELECT * FROM accts WHERE un = ?");
            pstmt.setString(1, username);
            rs = pstmt.executeQuery();

            if (!rs.next()) return null;

            String[] userinfo = new String[rs.getMetaData().getColumnCount()];
            for (int i = 0; i < userinfo.length; i++) {
                userinfo[i] = rs.getString(i + 1);
            }

            User usr;
            if (userinfo[5] == null || Objects.equals(userinfo[5], "null")) {
                usr = new User(userinfo[1], userinfo[2], Integer.parseInt(userinfo[3]), LocalDateTime.parse(userinfo[4].replace(" ", "T")));
            } else {
                usr = new User(userinfo[1], userinfo[2], Integer.parseInt(userinfo[3]), LocalDateTime.parse(userinfo[4].replace(" ", "T")), LocalDateTime.parse(userinfo[5].replace(" ","T")));
            }
            return usr;
        } catch (SQLException ex) {
            Utils.Errprintln(ex.getMessage());
            return null;
        } finally {
            try { if (rs != null) rs.close(); } catch (SQLException e) { /* ignored */ }
            try { if (pstmt != null) pstmt.close(); } catch (SQLException e) { /* ignored */ }
            try { if (conn != null) conn.close(); } catch (SQLException e) { /* ignored */ }
        }
    }

    public void addToken(AuthToken token) {
        String status = token.getStatus() == TokenStatus.VALIDATED ? "VALIDATED" : "INVALIDATED";
        int isAdmin = token.isAdmin() ? 1 : 0;

        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        String createdate = token.getCreationDate().format(formatter);
        String expdate = token.getExpirationDate().format(formatter);

        Dbm dbm = new Dbm();
        Connection conn = null;
        PreparedStatement pstmt = null;
        try {
            conn = dbm.getConnection();
            pstmt = conn.prepareStatement("INSERT INTO tokens (token, status, admin, sessionid, creationdate, expdate, owner) VALUES (?, ?, ?, ?, ?, ?, ?)");
            pstmt.setString(1, token.getCode());
            pstmt.setString(2, status);
            pstmt.setInt(3, isAdmin);
            pstmt.setString(4, token.getSsid());
            pstmt.setString(5, createdate);
            pstmt.setString(6, expdate);
            pstmt.setString(7, token.getOwner());
            pstmt.executeUpdate();
        } catch (SQLException ex) {
            Utils.Errprintln(ex.getMessage());
        } finally {
            try { if (pstmt != null) pstmt.close(); } catch (SQLException e) { /* ignored */ }
            try { if (conn != null) conn.close(); } catch (SQLException e) { /* ignored */ }
        }
    }

    public AuthToken fetchToken(String tokencode) {
        Dbm dbm = new Dbm();
        Connection conn = null;
        PreparedStatement pstmt = null;
        ResultSet rs = null;
        try {
            conn = dbm.getConnection();
            pstmt = conn.prepareStatement("SELECT * FROM tokens WHERE token = ?");
            pstmt.setString(1, tokencode);
            rs = pstmt.executeQuery();

            if (!rs.next()) return null;

            String code = rs.getString(2);
            TokenStatus status = rs.getString(3).equals("VALIDATED") ? TokenStatus.VALIDATED : TokenStatus.INVALIDATED;
            boolean isAdmin = rs.getString(4).equals("1");
            String sessionid = rs.getString(5);

            ZonedDateTime cdt = LocalDateTime.parse(rs.getString(6).replace(" ", "T")).atOffset(ZoneOffset.UTC).atZoneSameInstant(ZoneId.systemDefault());
            String owner = rs.getString("owner");
            if (owner == null) owner = "SYSTEM";
            AuthToken token;
            String expStr = rs.getString(7);
            if (expStr == null || expStr.equals("null")) {
                token = new AuthToken(sessionid, code, owner, status, isAdmin, cdt);
            } else {
                ZonedDateTime edt = LocalDateTime.parse(expStr.replace(" ", "T")).atOffset(ZoneOffset.UTC).atZoneSameInstant(ZoneId.systemDefault());
                token = new AuthToken(sessionid, code, owner, status, isAdmin, cdt, edt);
            }
            return token;
        } catch (SQLException e) {
            return null;
        } finally {
            try { if (rs != null) rs.close(); } catch (SQLException e) { /* ignored */ }
            try { if (pstmt != null) pstmt.close(); } catch (SQLException e) { /* ignored */ }
            try { if (conn != null) conn.close(); } catch (SQLException e) { /* ignored */ }
        }
    }

    public boolean checkBadge(int idnum) {
        Dbm dbm = new Dbm();
        Connection conn = null;
        try {
            conn = dbm.getConnection();
            LocalDateTime dt = LocalDateTime.now();

            PreparedStatement checkUser = conn.prepareStatement("SELECT studentid FROM users WHERE studentid = ?");
            checkUser.setInt(1, idnum);
            ResultSet rs = checkUser.executeQuery();
            if (!rs.next()) {
                System.out.println("User does not exist, adding to database");
                PreparedStatement insertUser = conn.prepareStatement("INSERT INTO users (studentid, exitallowed) VALUES (?, ?)");
                insertUser.setInt(1, idnum);
                insertUser.setBoolean(2, false);
                insertUser.executeUpdate();
                insertUser.close();
            }
            rs.close();
            checkUser.close();

            PreparedStatement checkExit = conn.prepareStatement("SELECT exitallowed FROM users WHERE studentid = ?");
            checkExit.setInt(1, idnum);
            ResultSet exitRs = checkExit.executeQuery();
            exitRs.next();
            boolean exitallowed = exitRs.getBoolean(1);
            exitRs.close();
            checkExit.close();

            String earlyexit = exitallowed ? "APPROVED" : "REJECTED";

            PreparedStatement insertRecord = conn.prepareStatement("INSERT INTO RECORDS (sid, timestamp, result) VALUES (?, ?, ?)");
            insertRecord.setInt(1, idnum);
            insertRecord.setString(2, dt.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")));
            insertRecord.setString(3, earlyexit);
            insertRecord.executeUpdate();
            insertRecord.close();

            return exitallowed;
        } catch (SQLException e) {
            System.err.println(e.getMessage());
            return false;
        } finally {
            try { if (conn != null) conn.close(); } catch (SQLException e) { /* ignored */ }
        }
    }

    public boolean updatePasswordHash(String username, String newHash) {
        Dbm dbm = new Dbm();
        Connection conn = null;
        PreparedStatement pstmt = null;
        try {
            conn = dbm.getConnection();
            pstmt = conn.prepareStatement("UPDATE accts SET password = ? WHERE un = ?");
            pstmt.setString(1, newHash);
            pstmt.setString(2, username);
            int rows = pstmt.executeUpdate();
            return rows > 0;
        } catch (SQLException ex) {
            Utils.Errprintln(ex.getMessage());
            return false;
        } finally {
            try { if (pstmt != null) pstmt.close(); } catch (SQLException e) { /* ignored */ }
            try { if (conn != null) conn.close(); } catch (SQLException e) { /* ignored */ }
        }
    }

    public void invalidateAllAdminTokensExcept(String keepTokenCode) {
        Dbm dbm = new Dbm();
        Connection conn = null;
        PreparedStatement pstmt = null;
        try {
            conn = dbm.getConnection();
            pstmt = conn.prepareStatement("UPDATE tokens SET status = 'INVALIDATED' WHERE admin = 1 AND status = 'VALIDATED' AND token != ?");
            pstmt.setString(1, keepTokenCode);
            pstmt.executeUpdate();
        } catch (SQLException ex) {
            Utils.Errprintln(ex.getMessage());
        } finally {
            try { if (pstmt != null) pstmt.close(); } catch (SQLException e) { /* ignored */ }
            try { if (conn != null) conn.close(); } catch (SQLException e) { /* ignored */ }
        }
    }
}
