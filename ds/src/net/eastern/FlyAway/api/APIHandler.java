package net.eastern.FlyAway.api;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import net.eastern.FlyAway.auth.Authenticator;
import net.eastern.FlyAway.util.DBAPI;
import net.eastern.FlyAway.util.Utils;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class APIHandler implements HttpHandler {

    private static final int MAX_BODY_SIZE = 65536;
    private static final int MIN_STUDENT_ID = 1;
    private static final int MAX_STUDENT_ID = 999_999_999;
    private static final int RATE_LIMIT_PER_MINUTE = 30;

    private static final ConcurrentHashMap<String, long[]> rateLimitMap = new ConcurrentHashMap<>();

    private boolean isRateLimited(String clientIp) {
        long now = System.currentTimeMillis();
        long[] window = rateLimitMap.compute(clientIp, (k, v) -> {
            if (v == null || now - v[0] > 60000) {
                return new long[]{now, 1};
            }
            v[1]++;
            return v;
        });
        // Periodically clean up stale rate limit entries
        if (rateLimitMap.size() > 1000) {
            long cleanupThreshold = now - 120000;
            rateLimitMap.entrySet().removeIf(entry -> entry.getValue()[0] < cleanupThreshold);
        }
        return window[1] > RATE_LIMIT_PER_MINUTE;
    }

    private void sendJsonResponse(HttpExchange exchange, int statusCode, String data) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "http://localhost:3000");
        exchange.getResponseHeaders().set("Vary", "Origin");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("X-Frame-Options", "DENY");
        exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
        byte[] responseBytes = data.getBytes();
        exchange.sendResponseHeaders(statusCode, responseBytes.length);
        OutputStream os = exchange.getResponseBody();
        os.write(responseBytes);
        os.close();
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (exchange.getRequestMethod().equalsIgnoreCase("OPTIONS")) {
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "http://localhost:3000");
            exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type,Authorization");
            exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "POST");
            exchange.sendResponseHeaders(204, -1);
            exchange.getResponseBody().close();
            return;
        }

        String clientIp = exchange.getRemoteAddress().getAddress().getHostAddress();
        if (isRateLimited(clientIp)) {
            sendJsonResponse(exchange, 429, "{\"message\":\"429 too many requests\"}");
            return;
        }

        Utils.Infoprintln("New Request From " + exchange.getRemoteAddress() + " On Path " + exchange.getRequestURI());

        String body;
        try (InputStream is = exchange.getRequestBody()) {
            StringBuilder sb = new StringBuilder();
            byte[] buf = new byte[4096];
            int totalRead = 0;
            int n;
            while ((n = is.read(buf)) != -1) {
                sb.append(new String(buf, 0, n));
                totalRead += n;
                if (totalRead > MAX_BODY_SIZE) {
                    sendJsonResponse(exchange, 413, "{\"message\":\"413 Payload Too Large\"}");
                    return;
                }
            }
            body = sb.toString();
        }

        Utils.Infoprintln("Request received (" + body.length() + " bytes)");

        try {
            JSONObject obj = new JSONObject(body);

            if (obj.has("sendrecord")) {
                String authToken = obj.optString("token", null);
                if (authToken == null || !Authenticator.CheckToken(authToken)) {
                    sendJsonResponse(exchange, 401, "{\"message\":\"401 unauthorized\"}");
                    return;
                }

                String record = obj.getString("sendrecord");
                Utils.Infoprintln("SENDRECORD FROM [" + exchange.getRemoteAddress() + "]: " + record);
                try {
                    int id = Integer.parseInt(record);
                    if (id < MIN_STUDENT_ID || id > MAX_STUDENT_ID) {
                        sendJsonResponse(exchange, 400, "{\"message\":\"400 invalid record id\"}");
                        return;
                    }
                    new DBAPI().checkBadge(id);
                    sendJsonResponse(exchange, 200, "{\"message\":\"200 ok\"}");
                } catch (NumberFormatException e) {
                    sendJsonResponse(exchange, 400, "{\"message\":\"400 invalid record id\"}");
                }
                return;
            }

            if (obj.has("LoginRequest")) {
                JSONObject loginReq = obj.getJSONObject("LoginRequest");
                String username = loginReq.getString("username");
                String password = loginReq.getString("password");
                String sessionID = loginReq.getString("sessionID");
                
                net.eastern.FlyAway.auth.AuthToken token = Authenticator.Authenticate_User(username, password, sessionID);
                String responseJson = Templates.generateAuthReturnJSON(username, sessionID, token);
                sendJsonResponse(exchange, 200, responseJson);
                return;
            }
            
            if (obj.has("ValidateToken")) {
                JSONObject valReq = obj.getJSONObject("ValidateToken");
                String token = valReq.getString("token");
                String sessionID = valReq.optString("sessionID", "");
                
                boolean valid = Authenticator.CheckToken(token);
                String responseJson = Templates.generateValidationResponseJSON(valid);
                sendJsonResponse(exchange, 200, responseJson);
                return;
            }

            if (obj.has("GetDashboardData")) {
                String token = obj.getJSONObject("GetDashboardData").optString("token", null);
                if (token == null || !Authenticator.CheckAdminToken(token)) {
                    sendJsonResponse(exchange, 401, "{\"message\":\"401 unauthorized\"}");
                    return;
                }
                
                net.eastern.FlyAway.dbm.Dbm dbm = new net.eastern.FlyAway.dbm.Dbm();
                java.sql.Connection conn = dbm.getConnection();
                try {
                    java.sql.PreparedStatement st1 = conn.prepareStatement("SELECT * FROM users");
                    java.sql.ResultSet rs1 = st1.executeQuery();
                    org.json.JSONArray usersArr = new org.json.JSONArray();
                    while(rs1.next()) {
                        JSONObject u = new JSONObject();
                        u.put("studentid", rs1.getInt("studentid"));
                        u.put("exitallowed", rs1.getBoolean("exitallowed"));
                        usersArr.put(u);
                    }
                    rs1.close();
                    st1.close();
                    
                    java.sql.PreparedStatement st2 = conn.prepareStatement("SELECT * FROM RECORDS ORDER BY timestamp DESC LIMIT 50");
                    java.sql.ResultSet rs2 = st2.executeQuery();
                    org.json.JSONArray recArr = new org.json.JSONArray();
                    while(rs2.next()) {
                        JSONObject r = new JSONObject();
                        r.put("sid", rs2.getInt("sid"));
                        r.put("timestamp", rs2.getString("timestamp"));
                        r.put("result", rs2.getString("result"));
                        recArr.put(r);
                    }
                    rs2.close();
                    st2.close();
                    
                    JSONObject res = new JSONObject();
                    res.put("users", usersArr);
                    res.put("records", recArr);
                    sendJsonResponse(exchange, 200, res.toString());
                } finally {
                    conn.close();
                }
                return;
            }

            if (obj.has("SetPermission")) {
                JSONObject permObj = obj.getJSONObject("SetPermission");
                String token = permObj.optString("token", null);
                if (token == null || !Authenticator.CheckAdminToken(token)) {
                    sendJsonResponse(exchange, 401, "{\"message\":\"401 unauthorized\"}");
                    return;
                }
                
                int studentid = permObj.getInt("studentid");
                if (studentid < MIN_STUDENT_ID || studentid > MAX_STUDENT_ID) {
                    sendJsonResponse(exchange, 400, "{\"message\":\"400 invalid student id\"}");
                    return;
                }
                boolean allow = permObj.getBoolean("allow");
                net.eastern.FlyAway.dbm.Dbm dbm = new net.eastern.FlyAway.dbm.Dbm();
                java.sql.Connection conn = dbm.getConnection();
                try {
                    java.sql.PreparedStatement checkUser = conn.prepareStatement("SELECT studentid FROM users WHERE studentid = ?");
                    checkUser.setInt(1, studentid);
                    java.sql.ResultSet rs = checkUser.executeQuery();
                    if (!rs.next()) {
                        java.sql.PreparedStatement insertUser = conn.prepareStatement("INSERT INTO users (studentid, exitallowed) VALUES (?, ?)");
                        insertUser.setInt(1, studentid);
                        insertUser.setBoolean(2, allow);
                        insertUser.executeUpdate();
                        insertUser.close();
                    } else {
                        java.sql.PreparedStatement updateUser = conn.prepareStatement("UPDATE users SET exitallowed = ? WHERE studentid = ?");
                        updateUser.setBoolean(1, allow);
                        updateUser.setInt(2, studentid);
                        updateUser.executeUpdate();
                        updateUser.close();
                    }
                    rs.close();
                    checkUser.close();
                    sendJsonResponse(exchange, 200, "{\"success\":true}");
                } finally {
                    conn.close();
                }
                return;
            }

            if (obj.has("DeleteStudent")) {
                JSONObject delObj = obj.getJSONObject("DeleteStudent");
                String token = delObj.optString("token", null);
                if (token == null || !Authenticator.CheckAdminToken(token)) {
                    sendJsonResponse(exchange, 401, "{\"message\":\"401 unauthorized\"}");
                    return;
                }
                int studentid = delObj.getInt("studentid");
                if (studentid < MIN_STUDENT_ID || studentid > MAX_STUDENT_ID) {
                    sendJsonResponse(exchange, 400, "{\"message\":\"400 invalid student id\"}");
                    return;
                }
                net.eastern.FlyAway.dbm.Dbm dbm = new net.eastern.FlyAway.dbm.Dbm();
                java.sql.Connection conn = dbm.getConnection();
                try {
                    java.sql.PreparedStatement deleteUser = conn.prepareStatement("DELETE FROM users WHERE studentid = ?");
                    deleteUser.setInt(1, studentid);
                    deleteUser.executeUpdate();
                    deleteUser.close();
                    sendJsonResponse(exchange, 200, "{\"success\":true}");
                } finally {
                    conn.close();
                }
                return;
            }

            if (obj.has("GetTokensData")) {
                String token = obj.getJSONObject("GetTokensData").optString("token", null);
                if (token == null || !Authenticator.CheckAdminToken(token)) {
                    sendJsonResponse(exchange, 401, "{\"message\":\"401 unauthorized\"}");
                    return;
                }
                net.eastern.FlyAway.dbm.Dbm dbm = new net.eastern.FlyAway.dbm.Dbm();
                java.sql.Connection conn = dbm.getConnection();
                try {
                    java.sql.PreparedStatement stmt = conn.prepareStatement("SELECT * FROM tokens ORDER BY id DESC");
                    java.sql.ResultSet rs = stmt.executeQuery();
                    org.json.JSONArray tokensArr = new org.json.JSONArray();
                    while (rs.next()) {
                        JSONObject t = new JSONObject();
                        t.put("id", rs.getInt("id"));
                        String fullToken = rs.getString("token");
                        String maskedToken = fullToken.length() > 4 ? "****-****-****-" + fullToken.substring(fullToken.length() - 4) : "****";
                        t.put("token", maskedToken);
                        t.put("status", rs.getString("status"));
                        t.put("admin", rs.getInt("admin"));
                        t.put("sessionid", rs.getString("sessionid"));
                        t.put("creationdate", rs.getString("creationdate"));
                        t.put("expdate", rs.getString("expdate"));
                        tokensArr.put(t);
                    }
                    rs.close();
                    stmt.close();
                    sendJsonResponse(exchange, 200, tokensArr.toString());
                } finally {
                    conn.close();
                }
                return;
            }

            if (obj.has("GenerateToken")) {
                JSONObject genObj = obj.getJSONObject("GenerateToken");
                String token = genObj.optString("token", null);
                if (token == null || !Authenticator.CheckAdminToken(token)) {
                    sendJsonResponse(exchange, 401, "{\"message\":\"401 unauthorized\"}");
                    return;
                }
                boolean makeAdmin = false; // Admin tokens can only be created via login
                String ssid = genObj.optString("sessionid", "MANUAL-GEN");
                
                net.eastern.FlyAway.auth.AuthToken newToken = new net.eastern.FlyAway.auth.AuthToken(ssid, "ADMIN-GEN", makeAdmin);
                new DBAPI().addToken(newToken);
                
                JSONObject res = new JSONObject();
                res.put("success", true);
                res.put("token", newToken.getCode());
                sendJsonResponse(exchange, 200, res.toString());
                return;
            }

            if (obj.has("RevokeToken")) {
                JSONObject revObj = obj.getJSONObject("RevokeToken");
                String token = revObj.optString("token", null);
                if (token == null || !Authenticator.CheckAdminToken(token)) {
                    sendJsonResponse(exchange, 401, "{\"message\":\"401 unauthorized\"}");
                    return;
                }
                // Support revocation by token ID (integer) or token code (string)
                net.eastern.FlyAway.dbm.Dbm dbm = new net.eastern.FlyAway.dbm.Dbm();
                java.sql.Connection conn = dbm.getConnection();
                try {
                    java.sql.PreparedStatement stmt;
                    if (revObj.has("tokenId")) {
                        int tokenId = revObj.getInt("tokenId");
                        stmt = conn.prepareStatement("UPDATE tokens SET status = 'INVALIDATED' WHERE id = ?");
                        stmt.setInt(1, tokenId);
                    } else {
                        String tokenToRevoke = revObj.getString("tokenToRevoke");
                        stmt = conn.prepareStatement("UPDATE tokens SET status = 'INVALIDATED' WHERE token = ?");
                        stmt.setString(1, tokenToRevoke);
                    }
                    stmt.executeUpdate();
                    stmt.close();
                    sendJsonResponse(exchange, 200, "{\"success\":true}");
                } finally {
                    conn.close();
                }
                return;
            }

            if (obj.has("GetAdminAccounts")) {
                String token = obj.getJSONObject("GetAdminAccounts").optString("token", null);
                if (token == null || !Authenticator.CheckAdminToken(token)) {
                    sendJsonResponse(exchange, 401, "{\"message\":\"401 unauthorized\"}");
                    return;
                }
                net.eastern.FlyAway.dbm.Dbm dbm = new net.eastern.FlyAway.dbm.Dbm();
                java.sql.Connection conn = dbm.getConnection();
                try {
                    java.sql.PreparedStatement stmt = conn.prepareStatement("SELECT id, un, permsum, creationdate, lastlogin FROM accts ORDER BY id ASC");
                    java.sql.ResultSet rs = stmt.executeQuery();
                    org.json.JSONArray acctsArr = new org.json.JSONArray();
                    while (rs.next()) {
                        JSONObject a = new JSONObject();
                        a.put("id", rs.getInt("id"));
                        a.put("username", rs.getString("un"));
                        a.put("permsum", rs.getInt("permsum"));
                        a.put("creationdate", rs.getString("creationdate"));
                        a.put("lastlogin", rs.getString("lastlogin"));
                        acctsArr.put(a);
                    }
                    rs.close();
                    stmt.close();
                    sendJsonResponse(exchange, 200, acctsArr.toString());
                } finally {
                    conn.close();
                }
                return;
            }

            if (obj.has("CreateAdminAccount")) {
                JSONObject createObj = obj.getJSONObject("CreateAdminAccount");
                String token = createObj.optString("token", null);
                if (token == null || !Authenticator.CheckAdminToken(token)) {
                    sendJsonResponse(exchange, 401, "{\"message\":\"401 unauthorized\"}");
                    return;
                }
                String newUsername = createObj.getString("username");
                String clientHash = createObj.getString("password");
                int permsum = 777; // Fixed server-side, not client-controlled
                
                String finalHash = net.eastern.FlyAway.auth.PasswordHasher.hash(clientHash);
                net.eastern.FlyAway.dbm.Dbm dbm = new net.eastern.FlyAway.dbm.Dbm();
                java.sql.Connection conn = dbm.getConnection();
                try {
                    java.sql.PreparedStatement checkStmt = conn.prepareStatement("SELECT id FROM accts WHERE un = ?");
                    checkStmt.setString(1, newUsername);
                    java.sql.ResultSet rs = checkStmt.executeQuery();
                    if (rs.next()) {
                        sendJsonResponse(exchange, 400, "{\"message\":\"Username already exists\"}");
                        rs.close();
                        checkStmt.close();
                        return;
                    }
                    rs.close();
                    checkStmt.close();
                    
                    java.sql.PreparedStatement insertStmt = conn.prepareStatement("INSERT INTO accts (un, password, permsum, creationdate, lastlogin) VALUES (?, ?, ?, datetime('now'), NULL)");
                    insertStmt.setString(1, newUsername);
                    insertStmt.setString(2, finalHash);
                    insertStmt.setInt(3, permsum);
                    insertStmt.executeUpdate();
                    insertStmt.close();
                    sendJsonResponse(exchange, 200, "{\"success\":true}");
                } finally {
                    conn.close();
                }
                return;
            }

            if (obj.has("DeleteAdminAccount")) {
                JSONObject delObj = obj.getJSONObject("DeleteAdminAccount");
                String token = delObj.optString("token", null);
                if (token == null || !Authenticator.CheckAdminToken(token)) {
                    sendJsonResponse(exchange, 401, "{\"message\":\"401 unauthorized\"}");
                    return;
                }
                int adminId = delObj.getInt("id");

                // Prevent self-deletion
                net.eastern.FlyAway.auth.AuthToken callerToken = new DBAPI().fetchToken(token);
                if (callerToken != null) {
                    net.eastern.FlyAway.dbm.Dbm selfCheckDbm = new net.eastern.FlyAway.dbm.Dbm();
                    java.sql.Connection selfCheckConn = selfCheckDbm.getConnection();
                    try {
                        java.sql.PreparedStatement selfCheckStmt = selfCheckConn.prepareStatement("SELECT un FROM accts WHERE id = ?");
                        selfCheckStmt.setInt(1, adminId);
                        java.sql.ResultSet selfCheckRs = selfCheckStmt.executeQuery();
                        if (selfCheckRs.next() && selfCheckRs.getString("un").equals(callerToken.getOwner())) {
                            sendJsonResponse(exchange, 403, "{\"message\":\"Cannot delete your own account\"}");
                            selfCheckRs.close();
                            selfCheckStmt.close();
                            selfCheckConn.close();
                            return;
                        }
                        selfCheckRs.close();
                        selfCheckStmt.close();
                    } finally {
                        selfCheckConn.close();
                    }
                }

                net.eastern.FlyAway.dbm.Dbm dbm = new net.eastern.FlyAway.dbm.Dbm();
                java.sql.Connection conn = dbm.getConnection();
                try {
                    java.sql.PreparedStatement stmt = conn.prepareStatement("DELETE FROM accts WHERE id = ?");
                    stmt.setInt(1, adminId);
                    stmt.executeUpdate();
                    stmt.close();
                    sendJsonResponse(exchange, 200, "{\"success\":true}");
                } finally {
                    conn.close();
                }
                return;
            }

            sendJsonResponse(exchange, 400, "{\"message\":\"400 UNKNOWN REQUEST\"}");
        } catch (Exception e) {
            Utils.Errprintln("Request handling error: " + e.getMessage());
            sendJsonResponse(exchange, 400, "{\"message\":\"400 MALFORMED REQUEST\"}");
        }
    }
}
