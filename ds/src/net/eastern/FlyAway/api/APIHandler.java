package net.eastern.FlyAway.api;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import net.eastern.FlyAway.auth.Authenticator;
import net.eastern.FlyAway.dbm.Dbm;
import net.eastern.FlyAway.util.DBAPI;
import net.eastern.FlyAway.util.Utils;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.ConcurrentHashMap;

/**
 * High-performance HTTPS API Handler.
 *
 * Implements JSON API endpoints for attendance badge scanning kiosks,
 * administrative dashboard analytics, user permission management, token issuance,
 * and high-throughput student roster synchronization.
 */
public class APIHandler implements HttpHandler {

    private static final int MAX_BODY_SIZE = 1048576; // 1 MB payload limit
    private static final int MIN_STUDENT_ID = 1;
    private static final int MAX_STUDENT_ID = 999_999_999;
    private static final int DEFAULT_RATE_LIMIT = 180; // Requests per minute
    private static final DateTimeFormatter ISO_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

    private static final ConcurrentHashMap<String, long[]> rateLimitMap = new ConcurrentHashMap<>();

    private int getRateLimitThreshold() {
        String envLimit = System.getenv("FLYAWAY_RATE_LIMIT");
        if (envLimit != null) {
            try {
                return Integer.parseInt(envLimit);
            } catch (NumberFormatException ignored) {}
        }
        return DEFAULT_RATE_LIMIT;
    }

    private boolean isRateLimited(String clientIp) {
        // Exempt localhost or loopback proxy
        if ("127.0.0.1".equals(clientIp) || "0:0:0:0:0:0:0:1".equals(clientIp) || "localhost".equals(clientIp)) {
            return false;
        }

        long now = System.currentTimeMillis();
        int threshold = getRateLimitThreshold();
        long[] window = rateLimitMap.compute(clientIp, (k, v) -> {
            if (v == null || now - v[0] > 60000) {
                return new long[]{now, 1};
            }
            v[1]++;
            return v;
        });

        // Periodically purge stale entries
        if (rateLimitMap.size() > 2000) {
            long cleanupThreshold = now - 120000;
            rateLimitMap.entrySet().removeIf(entry -> entry.getValue()[0] < cleanupThreshold);
        }
        return window[1] > threshold;
    }

    private void sendJsonResponse(HttpExchange exchange, int statusCode, String data) throws IOException {
        byte[] responseBytes = data.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=UTF-8");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
        exchange.getResponseHeaders().set("Access-Control-Allow-Headers", "Content-Type,Authorization");
        exchange.getResponseHeaders().set("Access-Control-Allow-Methods", "POST,OPTIONS");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.getResponseHeaders().set("X-Frame-Options", "DENY");
        exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");

        exchange.sendResponseHeaders(statusCode, responseBytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(responseBytes);
        }
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (exchange.getRequestMethod().equalsIgnoreCase("OPTIONS")) {
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
            exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type,Authorization");
            exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "POST,OPTIONS");
            exchange.sendResponseHeaders(204, -1);
            exchange.getResponseBody().close();
            return;
        }

        String clientIp = exchange.getRemoteAddress().getAddress().getHostAddress();
        if (isRateLimited(clientIp)) {
            sendJsonResponse(exchange, 429, "{\"message\":\"429 too many requests\"}");
            return;
        }

        String body;
        try (InputStream is = exchange.getRequestBody()) {
            StringBuilder sb = new StringBuilder();
            byte[] buf = new byte[8192];
            int totalRead = 0;
            int n;
            while ((n = is.read(buf)) != -1) {
                sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                totalRead += n;
                if (totalRead > MAX_BODY_SIZE) {
                    sendJsonResponse(exchange, 413, "{\"message\":\"413 Payload Too Large\"}");
                    return;
                }
            }
            body = sb.toString();
        }

        try {
            JSONObject obj = new JSONObject(body);
            Dbm dbm = new Dbm();

            // 1. sendrecord - Edge Scanner Kiosk Verification
            if (obj.has("sendrecord")) {
                String authToken = obj.optString("token", null);
                if (authToken == null || !Authenticator.CheckToken(authToken)) {
                    sendJsonResponse(exchange, 401, "{\"message\":\"401 unauthorized\"}");
                    return;
                }

                String record = obj.getString("sendrecord");
                try {
                    int id = Integer.parseInt(record.trim());
                    if (id < MIN_STUDENT_ID || id > MAX_STUDENT_ID) {
                        sendJsonResponse(exchange, 400, "{\"message\":\"400 invalid record id\"}");
                        return;
                    }
                    boolean allowed = new DBAPI().checkBadge(id);
                    JSONObject resp = new JSONObject();
                    resp.put("message", "200 ok");
                    resp.put("status", allowed ? "APPROVED" : "REJECTED");
                    resp.put("allowed", allowed);
                    sendJsonResponse(exchange, 200, resp.toString());
                } catch (NumberFormatException e) {
                    sendJsonResponse(exchange, 400, "{\"message\":\"400 invalid record id\"}");
                }
                return;
            }

            // 2. LoginRequest - Administrator Authentication
            if (obj.has("LoginRequest")) {
                JSONObject loginReq = obj.getJSONObject("LoginRequest");
                String username = loginReq.getString("username");
                String password = loginReq.getString("password");
                String sessionID = loginReq.optString("sessionID", "WEB-" + java.util.UUID.randomUUID());

                net.eastern.FlyAway.auth.AuthToken token = Authenticator.Authenticate_User(username, password, sessionID);
                String responseJson = Templates.generateAuthReturnJSON(username, sessionID, token);
                sendJsonResponse(exchange, 200, responseJson);
                return;
            }

            // 3. ValidateToken - Session Verification
            if (obj.has("ValidateToken")) {
                JSONObject valReq = obj.getJSONObject("ValidateToken");
                String token = valReq.getString("token");
                boolean valid = Authenticator.CheckToken(token);
                String responseJson = Templates.generateValidationResponseJSON(valid);
                sendJsonResponse(exchange, 200, responseJson);
                return;
            }

            // 4. GetDashboardData - High-Performance Overview & Aggregated Statistics
            if (obj.has("GetDashboardData")) {
                JSONObject req = obj.getJSONObject("GetDashboardData");
                String token = req.optString("token", null);
                if (token == null || !Authenticator.CheckAdminToken(token)) {
                    sendJsonResponse(exchange, 401, "{\"message\":\"401 unauthorized\"}");
                    return;
                }

                int page = Math.max(1, req.optInt("page", 1));
                int pageSize = Math.min(200, Math.max(10, req.optInt("pageSize", 50)));
                String search = req.optString("search", "").trim();
                String filter = req.optString("filter", "ALL").toUpperCase();

                JSONObject res = new JSONObject();
                JSONObject stats = new JSONObject();

                try (Connection conn = dbm.getConnection()) {
                    // Aggregate student statistics via SQL
                    try (PreparedStatement statStmt = conn.prepareStatement(
                            "SELECT COUNT(*) AS total, COALESCE(SUM(CASE WHEN exitallowed = 1 THEN 1 ELSE 0 END), 0) AS allowed FROM users");
                         ResultSet rsStat = statStmt.executeQuery()) {
                        if (rsStat.next()) {
                            int total = rsStat.getInt("total");
                            int allowed = rsStat.getInt("allowed");
                            stats.put("totalStudents", total);
                            stats.put("allowedStudents", allowed);
                            stats.put("blockedStudents", total - allowed);
                        }
                    }

                    // Aggregate record scan statistics via SQL
                    try (PreparedStatement recStatStmt = conn.prepareStatement(
                            "SELECT COUNT(*) AS total, " +
                            "COALESCE(SUM(CASE WHEN result = 'APPROVED' THEN 1 ELSE 0 END), 0) AS approved, " +
                            "COALESCE(SUM(CASE WHEN result = 'REJECTED' THEN 1 ELSE 0 END), 0) AS rejected FROM RECORDS");
                         ResultSet rsRecStat = recStatStmt.executeQuery()) {
                        if (rsRecStat.next()) {
                            stats.put("totalRecords", rsRecStat.getInt("total"));
                            stats.put("approvedScans", rsRecStat.getInt("approved"));
                            stats.put("rejectedScans", rsRecStat.getInt("rejected"));
                        }
                    }
                    res.put("stats", stats);

                    // Fetch 50 most recent scan records
                    JSONArray recArr = new JSONArray();
                    try (PreparedStatement recStmt = conn.prepareStatement(
                            "SELECT sid, timestamp, result FROM RECORDS ORDER BY id DESC LIMIT 50");
                         ResultSet rsRec = recStmt.executeQuery()) {
                        while (rsRec.next()) {
                            JSONObject r = new JSONObject();
                            r.put("sid", rsRec.getInt("sid"));
                            r.put("timestamp", rsRec.getString("timestamp"));
                            r.put("result", rsRec.getString("result"));
                            recArr.put(r);
                        }
                    }
                    res.put("records", recArr);

                    // Fetch Paginated User Roster
                    StringBuilder countSql = new StringBuilder("SELECT COUNT(*) FROM users WHERE 1=1");
                    StringBuilder querySql = new StringBuilder("SELECT studentid, exitallowed FROM users WHERE 1=1");

                    boolean hasSearch = !search.isEmpty();
                    if (hasSearch) {
                        countSql.append(" AND CAST(studentid AS CHAR) LIKE ?");
                        querySql.append(" AND CAST(studentid AS CHAR) LIKE ?");
                    }
                    if ("ALLOWED".equals(filter)) {
                        countSql.append(" AND exitallowed = 1");
                        querySql.append(" AND exitallowed = 1");
                    } else if ("BLOCKED".equals(filter)) {
                        countSql.append(" AND exitallowed = 0");
                        querySql.append(" AND exitallowed = 0");
                    }

                    querySql.append(" ORDER BY studentid ASC LIMIT ? OFFSET ?");

                    int totalFilteredUsers = 0;
                    try (PreparedStatement countStmt = conn.prepareStatement(countSql.toString())) {
                        if (hasSearch) {
                            countStmt.setString(1, "%" + search + "%");
                        }
                        try (ResultSet rsCount = countStmt.executeQuery()) {
                            if (rsCount.next()) {
                                totalFilteredUsers = rsCount.getInt(1);
                            }
                        }
                    }

                    JSONArray usersArr = new JSONArray();
                    int offset = (page - 1) * pageSize;
                    try (PreparedStatement queryStmt = conn.prepareStatement(querySql.toString())) {
                        int paramIdx = 1;
                        if (hasSearch) {
                            queryStmt.setString(paramIdx++, "%" + search + "%");
                        }
                        queryStmt.setInt(paramIdx++, pageSize);
                        queryStmt.setInt(paramIdx, offset);

                        try (ResultSet rsUsers = queryStmt.executeQuery()) {
                            while (rsUsers.next()) {
                                JSONObject u = new JSONObject();
                                u.put("studentid", rsUsers.getInt("studentid"));
                                u.put("exitallowed", rsUsers.getBoolean("exitallowed"));
                                usersArr.put(u);
                            }
                        }
                    }

                    res.put("users", usersArr);
                    res.put("totalUsers", totalFilteredUsers);
                    res.put("page", page);
                    res.put("pageSize", pageSize);
                    res.put("totalPages", (int) Math.ceil((double) totalFilteredUsers / pageSize));

                    sendJsonResponse(exchange, 200, res.toString());
                } catch (SQLException ex) {
                    Utils.Errprintln("GetDashboardData error: " + ex.getMessage());
                    sendJsonResponse(exchange, 500, "{\"message\":\"Database query error\"}");
                }
                return;
            }

            // 5. GetStudents - Dedicated Paginated Search Endpoint
            if (obj.has("GetStudents")) {
                JSONObject req = obj.getJSONObject("GetStudents");
                String token = req.optString("token", null);
                if (token == null || !Authenticator.CheckAdminToken(token)) {
                    sendJsonResponse(exchange, 401, "{\"message\":\"401 unauthorized\"}");
                    return;
                }

                int page = Math.max(1, req.optInt("page", 1));
                int pageSize = Math.min(200, Math.max(10, req.optInt("pageSize", 50)));
                String search = req.optString("search", "").trim();
                String filter = req.optString("filter", "ALL").toUpperCase();

                try (Connection conn = dbm.getConnection()) {
                    StringBuilder countSql = new StringBuilder("SELECT COUNT(*) FROM users WHERE 1=1");
                    StringBuilder querySql = new StringBuilder("SELECT studentid, exitallowed FROM users WHERE 1=1");

                    boolean hasSearch = !search.isEmpty();
                    if (hasSearch) {
                        countSql.append(" AND CAST(studentid AS CHAR) LIKE ?");
                        querySql.append(" AND CAST(studentid AS CHAR) LIKE ?");
                    }
                    if ("ALLOWED".equals(filter)) {
                        countSql.append(" AND exitallowed = 1");
                        querySql.append(" AND exitallowed = 1");
                    } else if ("BLOCKED".equals(filter)) {
                        countSql.append(" AND exitallowed = 0");
                        querySql.append(" AND exitallowed = 0");
                    }

                    querySql.append(" ORDER BY studentid ASC LIMIT ? OFFSET ?");

                    int totalCount = 0;
                    try (PreparedStatement countStmt = conn.prepareStatement(countSql.toString())) {
                        if (hasSearch) {
                            countStmt.setString(1, "%" + search + "%");
                        }
                        try (ResultSet rsCount = countStmt.executeQuery()) {
                            if (rsCount.next()) {
                                totalCount = rsCount.getInt(1);
                            }
                        }
                    }

                    JSONArray usersArr = new JSONArray();
                    int offset = (page - 1) * pageSize;
                    try (PreparedStatement queryStmt = conn.prepareStatement(querySql.toString())) {
                        int paramIdx = 1;
                        if (hasSearch) {
                            queryStmt.setString(paramIdx++, "%" + search + "%");
                        }
                        queryStmt.setInt(paramIdx++, pageSize);
                        queryStmt.setInt(paramIdx, offset);

                        try (ResultSet rsUsers = queryStmt.executeQuery()) {
                            while (rsUsers.next()) {
                                JSONObject u = new JSONObject();
                                u.put("studentid", rsUsers.getInt("studentid"));
                                u.put("exitallowed", rsUsers.getBoolean("exitallowed"));
                                usersArr.put(u);
                            }
                        }
                    }

                    JSONObject res = new JSONObject();
                    res.put("users", usersArr);
                    res.put("total", totalCount);
                    res.put("page", page);
                    res.put("pageSize", pageSize);
                    res.put("totalPages", (int) Math.ceil((double) totalCount / pageSize));
                    sendJsonResponse(exchange, 200, res.toString());
                } catch (SQLException ex) {
                    Utils.Errprintln("GetStudents error: " + ex.getMessage());
                    sendJsonResponse(exchange, 500, "{\"message\":\"Database query error\"}");
                }
                return;
            }

            // 6. SetPermission - Toggle Student Permission
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

                try (Connection conn = dbm.getConnection()) {
                    try (PreparedStatement checkUser = conn.prepareStatement("SELECT studentid FROM users WHERE studentid = ?")) {
                        checkUser.setInt(1, studentid);
                        try (ResultSet rs = checkUser.executeQuery()) {
                            if (!rs.next()) {
                                try (PreparedStatement insertUser = conn.prepareStatement("INSERT INTO users (studentid, exitallowed) VALUES (?, ?)")) {
                                    insertUser.setInt(1, studentid);
                                    insertUser.setBoolean(2, allow);
                                    insertUser.executeUpdate();
                                }
                            } else {
                                try (PreparedStatement updateUser = conn.prepareStatement("UPDATE users SET exitallowed = ? WHERE studentid = ?")) {
                                    updateUser.setBoolean(1, allow);
                                    updateUser.setInt(2, studentid);
                                    updateUser.executeUpdate();
                                }
                            }
                        }
                    }
                    sendJsonResponse(exchange, 200, "{\"success\":true}");
                } catch (SQLException ex) {
                    Utils.Errprintln("SetPermission error: " + ex.getMessage());
                    sendJsonResponse(exchange, 500, "{\"message\":\"Database error\"}");
                }
                return;
            }

            // 7. ImportStudents - Batch Roster Import
            if (obj.has("ImportStudents")) {
                JSONObject importObj = obj.getJSONObject("ImportStudents");
                String token = importObj.optString("token", null);
                if (token == null || !Authenticator.CheckAdminToken(token)) {
                    sendJsonResponse(exchange, 401, "{\"message\":\"401 unauthorized\"}");
                    return;
                }

                JSONArray studentList = importObj.getJSONArray("students");
                int count = 0;
                try (Connection conn = dbm.getConnection()) {
                    conn.setAutoCommit(false);
                    String insertSql = "INSERT INTO users (studentid, exitallowed) VALUES (?, ?) " +
                            "ON DUPLICATE KEY UPDATE exitallowed = VALUES(exitallowed)";
                    // Fallback syntax for SQLite
                    if (!dbm.getClass().getName().contains("MySql") && System.getenv("DB_HOST") == null) {
                        insertSql = "INSERT INTO users (studentid, exitallowed) VALUES (?, ?) " +
                                "ON CONFLICT(studentid) DO UPDATE SET exitallowed = excluded.exitallowed";
                    }

                    try (PreparedStatement ps = conn.prepareStatement(insertSql)) {
                        for (int i = 0; i < studentList.length(); i++) {
                            JSONObject item = studentList.getJSONObject(i);
                            int sid = item.getInt("studentid");
                            boolean allow = item.optBoolean("allow", false);
                            if (sid >= MIN_STUDENT_ID && sid <= MAX_STUDENT_ID) {
                                ps.setInt(1, sid);
                                ps.setBoolean(2, allow);
                                ps.addBatch();
                                count++;
                            }
                        }
                        ps.executeBatch();
                        conn.commit();
                    } catch (SQLException e) {
                        conn.rollback();
                        throw e;
                    } finally {
                        conn.setAutoCommit(true);
                    }

                    JSONObject res = new JSONObject();
                    res.put("success", true);
                    res.put("importedCount", count);
                    sendJsonResponse(exchange, 200, res.toString());
                } catch (SQLException ex) {
                    Utils.Errprintln("ImportStudents error: " + ex.getMessage());
                    sendJsonResponse(exchange, 500, "{\"message\":\"Batch import failed\"}");
                }
                return;
            }

            // 8. DeleteStudent - Remove Student
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

                try (Connection conn = dbm.getConnection();
                     PreparedStatement deleteUser = conn.prepareStatement("DELETE FROM users WHERE studentid = ?")) {
                    deleteUser.setInt(1, studentid);
                    deleteUser.executeUpdate();
                    sendJsonResponse(exchange, 200, "{\"success\":true}");
                } catch (SQLException ex) {
                    Utils.Errprintln("DeleteStudent error: " + ex.getMessage());
                    sendJsonResponse(exchange, 500, "{\"message\":\"Database error\"}");
                }
                return;
            }

            // 9. GetTokensData - Token Management
            if (obj.has("GetTokensData")) {
                String token = obj.getJSONObject("GetTokensData").optString("token", null);
                if (token == null || !Authenticator.CheckAdminToken(token)) {
                    sendJsonResponse(exchange, 401, "{\"message\":\"401 unauthorized\"}");
                    return;
                }

                try (Connection conn = dbm.getConnection();
                     PreparedStatement stmt = conn.prepareStatement("SELECT * FROM tokens ORDER BY id DESC");
                     ResultSet rs = stmt.executeQuery()) {
                    JSONArray tokensArr = new JSONArray();
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
                    sendJsonResponse(exchange, 200, tokensArr.toString());
                } catch (SQLException ex) {
                    Utils.Errprintln("GetTokensData error: " + ex.getMessage());
                    sendJsonResponse(exchange, 500, "{\"message\":\"Database error\"}");
                }
                return;
            }

            // 10. GenerateToken - Create Hardware/API Token
            if (obj.has("GenerateToken")) {
                JSONObject genObj = obj.getJSONObject("GenerateToken");
                String token = genObj.optString("token", null);
                if (token == null || !Authenticator.CheckAdminToken(token)) {
                    sendJsonResponse(exchange, 401, "{\"message\":\"401 unauthorized\"}");
                    return;
                }
                String ssid = genObj.optString("sessionid", "MANUAL-GEN");
                net.eastern.FlyAway.auth.AuthToken newToken = new net.eastern.FlyAway.auth.AuthToken(ssid, "ADMIN-GEN", false);
                new DBAPI().addToken(newToken);

                JSONObject res = new JSONObject();
                res.put("success", true);
                res.put("token", newToken.getCode());
                sendJsonResponse(exchange, 200, res.toString());
                return;
            }

            // 11. RevokeToken - Invalidate Token
            if (obj.has("RevokeToken")) {
                JSONObject revObj = obj.getJSONObject("RevokeToken");
                String token = revObj.optString("token", null);
                if (token == null || !Authenticator.CheckAdminToken(token)) {
                    sendJsonResponse(exchange, 401, "{\"message\":\"401 unauthorized\"}");
                    return;
                }

                try (Connection conn = dbm.getConnection()) {
                    if (revObj.has("tokenId")) {
                        try (PreparedStatement stmt = conn.prepareStatement("UPDATE tokens SET status = 'INVALIDATED' WHERE id = ?")) {
                            stmt.setInt(1, revObj.getInt("tokenId"));
                            stmt.executeUpdate();
                        }
                    } else {
                        try (PreparedStatement stmt = conn.prepareStatement("UPDATE tokens SET status = 'INVALIDATED' WHERE token = ?")) {
                            stmt.setString(1, revObj.getString("tokenToRevoke"));
                            stmt.executeUpdate();
                        }
                    }
                    sendJsonResponse(exchange, 200, "{\"success\":true}");
                } catch (SQLException ex) {
                    Utils.Errprintln("RevokeToken error: " + ex.getMessage());
                    sendJsonResponse(exchange, 500, "{\"message\":\"Database error\"}");
                }
                return;
            }

            // 12. GetAdminAccounts - List Admin Users
            if (obj.has("GetAdminAccounts")) {
                String token = obj.getJSONObject("GetAdminAccounts").optString("token", null);
                if (token == null || !Authenticator.CheckAdminToken(token)) {
                    sendJsonResponse(exchange, 401, "{\"message\":\"401 unauthorized\"}");
                    return;
                }

                try (Connection conn = dbm.getConnection();
                     PreparedStatement stmt = conn.prepareStatement("SELECT id, un, permsum, creationdate, lastlogin FROM accts ORDER BY id ASC");
                     ResultSet rs = stmt.executeQuery()) {
                    JSONArray acctsArr = new JSONArray();
                    while (rs.next()) {
                        JSONObject a = new JSONObject();
                        a.put("id", rs.getInt("id"));
                        a.put("username", rs.getString("un"));
                        a.put("permsum", rs.getInt("permsum"));
                        a.put("creationdate", rs.getString("creationdate"));
                        a.put("lastlogin", rs.getString("lastlogin"));
                        acctsArr.put(a);
                    }
                    sendJsonResponse(exchange, 200, acctsArr.toString());
                } catch (SQLException ex) {
                    Utils.Errprintln("GetAdminAccounts error: " + ex.getMessage());
                    sendJsonResponse(exchange, 500, "{\"message\":\"Database error\"}");
                }
                return;
            }

            // 13. CreateAdminAccount - Register Admin
            if (obj.has("CreateAdminAccount")) {
                JSONObject createObj = obj.getJSONObject("CreateAdminAccount");
                String token = createObj.optString("token", null);
                if (token == null || !Authenticator.CheckAdminToken(token)) {
                    sendJsonResponse(exchange, 401, "{\"message\":\"401 unauthorized\"}");
                    return;
                }
                String newUsername = createObj.getString("username").trim();
                if (newUsername.isEmpty() || newUsername.length() > 64) {
                    sendJsonResponse(exchange, 400, "{\"message\":\"Invalid username\"}");
                    return;
                }

                String clientHash = createObj.getString("password");
                String finalHash = net.eastern.FlyAway.auth.PasswordHasher.hash(clientHash);

                try (Connection conn = dbm.getConnection()) {
                    try (PreparedStatement checkStmt = conn.prepareStatement("SELECT id FROM accts WHERE un = ?")) {
                        checkStmt.setString(1, newUsername);
                        try (ResultSet rs = checkStmt.executeQuery()) {
                            if (rs.next()) {
                                sendJsonResponse(exchange, 400, "{\"message\":\"Username already exists\"}");
                                return;
                            }
                        }
                    }

                    String nowStr = LocalDateTime.now().format(ISO_FORMATTER);
                    try (PreparedStatement insertStmt = conn.prepareStatement(
                            "INSERT INTO accts (un, password, permsum, creationdate, lastlogin) VALUES (?, ?, 777, ?, NULL)")) {
                        insertStmt.setString(1, newUsername);
                        insertStmt.setString(2, finalHash);
                        insertStmt.setString(3, nowStr);
                        insertStmt.executeUpdate();
                    }
                    sendJsonResponse(exchange, 200, "{\"success\":true}");
                } catch (SQLException ex) {
                    Utils.Errprintln("CreateAdminAccount error: " + ex.getMessage());
                    sendJsonResponse(exchange, 500, "{\"message\":\"Database error\"}");
                }
                return;
            }

            // 14. DeleteAdminAccount - Remove Admin Account
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
                    try (Connection conn = dbm.getConnection();
                         PreparedStatement selfCheckStmt = conn.prepareStatement("SELECT un FROM accts WHERE id = ?")) {
                        selfCheckStmt.setInt(1, adminId);
                        try (ResultSet selfCheckRs = selfCheckStmt.executeQuery()) {
                            if (selfCheckRs.next() && selfCheckRs.getString("un").equals(callerToken.getOwner())) {
                                sendJsonResponse(exchange, 403, "{\"message\":\"Cannot delete your own account\"}");
                                return;
                            }
                        }
                    } catch (SQLException ex) {
                        Utils.Errprintln("Self-check error: " + ex.getMessage());
                    }
                }

                try (Connection conn = dbm.getConnection();
                     PreparedStatement stmt = conn.prepareStatement("DELETE FROM accts WHERE id = ?")) {
                    stmt.setInt(1, adminId);
                    stmt.executeUpdate();
                    sendJsonResponse(exchange, 200, "{\"success\":true}");
                } catch (SQLException ex) {
                    Utils.Errprintln("DeleteAdminAccount error: " + ex.getMessage());
                    sendJsonResponse(exchange, 500, "{\"message\":\"Database error\"}");
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
