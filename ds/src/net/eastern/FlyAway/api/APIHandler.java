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
        return window[1] > RATE_LIMIT_PER_MINUTE;
    }

    private void sendJsonResponse(HttpExchange exchange, int statusCode, String data) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "localhost");
        byte[] responseBytes = data.getBytes();
        exchange.sendResponseHeaders(statusCode, responseBytes.length);
        OutputStream os = exchange.getResponseBody();
        os.write(responseBytes);
        os.close();
    }

    @Override
    public void handle(HttpExchange exchange) throws IOException {
        if (exchange.getRequestMethod().equalsIgnoreCase("OPTIONS")) {
            exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "localhost");
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

        Utils.Infoprintln("Request Body: " + body);

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

            sendJsonResponse(exchange, 200, "{\"message\":\"200 OK\"}");
        } catch (Exception e) {
            Utils.Errprintln("Request handling error: " + e.getMessage());
            sendJsonResponse(exchange, 400, "{\"message\":\"400 MALFORMED REQUEST\"}");
        }
    }
}
