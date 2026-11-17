package dev.claudebridge;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;

import javax.net.ssl.SSLContext;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;

/** Small JSON-over-HTTP server with bearer-token authentication. */
final class ApiServer {

    /** Random per start; the tunnel self-check recognises its own server by it. */
    static final String INSTANCE_ID = Long.toHexString(new java.security.SecureRandom().nextLong());


    static final Gson GSON = new GsonBuilder()
            .disableHtmlEscaping()
            .serializeNulls()
            .serializeSpecialFloatingPointValues()
            .create();

    private static final int MAX_BODY = 1024 * 1024;

    @FunctionalInterface
    interface Handler {
        Object handle(Request request) throws Exception;
    }

    record Route(String method, String path, String description, Handler handler) {
    }

    /** Error with an HTTP status that is returned to the client as {"error": message}. */
    static final class ApiException extends RuntimeException {
        final int status;

        ApiException(int status, String message) {
            super(message);
            this.status = status;
        }
    }

    /** Parsed request: parameters are looked up in the JSON body first, then in the query string. */
    static final class Request {
        final String method;
        final String path;
        final String remote;
        final Map<String, String> query;
        final JsonObject body;

        Request(String method, String path, String remote, Map<String, String> query, JsonObject body) {
            this.method = method;
            this.path = path;
            this.remote = remote;
            this.query = query;
            this.body = body;
        }

        String str(String key) {
            return str(key, null);
        }

        String str(String key, String def) {
            if (body != null && body.has(key) && !body.get(key).isJsonNull()) {
                JsonElement e = body.get(key);
                return e.isJsonPrimitive() ? e.getAsString() : e.toString();
            }
            String q = query.get(key);
            return q != null ? q : def;
        }

        String require(String key) {
            String v = str(key);
            if (v == null || v.isBlank()) throw new ApiException(400, "missing parameter: " + key);
            return v;
        }

        boolean has(String key) {
            String v = str(key);
            return v != null && !v.isBlank();
        }

        int integer(String key, int def) {
            String v = str(key);
            if (v == null || v.isBlank()) return def;
            try {
                return (int) Math.floor(Double.parseDouble(v.trim()));
            } catch (NumberFormatException e) {
                throw new ApiException(400, "parameter " + key + " must be a number");
            }
        }

        long lng(String key, long def) {
            String v = str(key);
            if (v == null || v.isBlank()) return def;
            try {
                return Long.parseLong(v.trim());
            } catch (NumberFormatException e) {
                throw new ApiException(400, "parameter " + key + " must be an integer");
            }
        }

        double dbl(String key, double def) {
            String v = str(key);
            if (v == null || v.isBlank()) return def;
            try {
                return Double.parseDouble(v.trim());
            } catch (NumberFormatException e) {
                throw new ApiException(400, "parameter " + key + " must be a number");
            }
        }

        double requireDbl(String key) {
            if (!has(key)) throw new ApiException(400, "missing parameter: " + key);
            return dbl(key, 0);
        }

        boolean bool(String key, boolean def) {
            String v = str(key);
            if (v == null || v.isBlank()) return def;
            return v.equalsIgnoreCase("true") || v.equals("1") || v.equalsIgnoreCase("yes");
        }

        JsonArray array(String key) {
            if (body != null && body.has(key) && body.get(key).isJsonArray()) return body.getAsJsonArray(key);
            return null;
        }
    }

    private final ClaudeBridgePlugin plugin;
    private final Map<String, Route> routes = new LinkedHashMap<>();
    private HttpServer server;
    private ExecutorService pool;
    private IpAllowList allowList;

    ApiServer(ClaudeBridgePlugin plugin) {
        this.plugin = plugin;
    }

    void route(String method, String path, String description, Handler handler) {
        routes.put(method + " " + path, new Route(method, path, description, handler));
    }

    List<Map<String, Object>> describeRoutes() {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Route r : routes.values()) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("method", r.method());
            m.put("path", r.path());
            m.put("description", r.description());
            out.add(m);
        }
        return out;
    }

    void start(String bind, int port, SSLContext tls, HttpsConfigurator configurator, IpAllowList allowList) throws IOException {
        this.allowList = allowList;
        if (tls != null) {
            HttpsServer https = HttpsServer.create(new InetSocketAddress(bind, port), 64);
            https.setHttpsConfigurator(configurator != null ? configurator : new HttpsConfigurator(tls));
            server = https;
        } else {
            server = HttpServer.create(new InetSocketAddress(bind, port), 64);
        }
        pool = Executors.newFixedThreadPool(8, runnable -> {
            Thread t = new Thread(runnable, "ClaudeBridge-HTTP");
            t.setDaemon(true);
            return t;
        });
        server.setExecutor(pool);
        server.createContext("/", this::handle);
        server.start();
    }

    void stop() {
        if (server != null) {
            server.stop(0);
            server = null;
        }
        if (pool != null) {
            pool.shutdownNow();
            pool = null;
        }
    }

    private void handle(HttpExchange ex) {
        String method = ex.getRequestMethod().toUpperCase();
        String rawPath = ex.getRequestURI().getPath();
        final String path = rawPath.length() > 1 && rawPath.endsWith("/") ? rawPath.substring(0, rawPath.length() - 1) : rawPath;
        String remote = remoteOf(ex);
        try {
            if (allowList != null && ex.getRemoteAddress() != null && !allowList.allows(ex.getRemoteAddress().getAddress())) {
                plugin.audit().log(remote, method, path, "BLOCKED: address is not in http.allowed-ips");
                send(ex, 403, error("forbidden"));
                return;
            }
            if (path.equals("/health") && (method.equals("GET") || method.equals("HEAD"))) {
                // Unauthenticated liveness answer for the tunnel self-check; tells nothing about the server.
                send(ex, 200, Map.of("service", "claudebridge", "instance", INSTANCE_ID));
                return;
            }
            if (!plugin.isApiEnabled()) {
                send(ex, 503, error("API is disabled on the server (/claudebridge on)"));
                return;
            }
            if (!authorized(ex)) {
                plugin.audit().log(remote, method, path, "DENIED");
                try {
                    Thread.sleep(300);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
                send(ex, 401, error("unauthorized: send header 'Authorization: Bearer <token>'"));
                return;
            }
            Route route = routes.get(method + " " + path);
            if (route == null) {
                boolean otherMethod = routes.values().stream().anyMatch(r -> r.path().equals(path));
                send(ex, otherMethod ? 405 : 404, error((otherMethod ? "method not allowed" : "not found")
                        + "; GET /api lists all endpoints"));
                return;
            }
            Request request = new Request(method, path, remote, parseQuery(ex.getRequestURI().getRawQuery()), readBody(ex));
            plugin.audit().log(remote, method, path, auditDetail(request));
            Object result = route.handler().handle(request);
            send(ex, 200, result == null ? Map.of("ok", true) : result);
        } catch (ApiException e) {
            sendQuietly(ex, e.status, error(e.getMessage()));
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "ClaudeBridge request failed: " + method + " " + path, e);
            sendQuietly(ex, 500, error(e.getClass().getSimpleName() + ": " + e.getMessage()));
        } finally {
            ex.close();
        }
    }

    private boolean authorized(HttpExchange ex) {
        String expected = plugin.token();
        if (expected == null || expected.isEmpty()) return false;
        String header = ex.getRequestHeaders().getFirst("Authorization");
        if (header == null || !header.regionMatches(true, 0, "Bearer ", 0, 7)) return false;
        byte[] given = header.substring(7).trim().getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), given);
    }

    private static String remoteOf(HttpExchange ex) {
        String forwarded = ex.getRequestHeaders().getFirst("X-Forwarded-For");
        String direct = ex.getRemoteAddress() == null ? "?" : ex.getRemoteAddress().getAddress().getHostAddress();
        if (forwarded != null && !forwarded.isBlank()) return forwarded.split(",")[0].trim() + "(via " + direct + ")";
        return direct;
    }

    private static String auditDetail(Request r) {
        if (r.body == null) return r.query.isEmpty() ? "" : r.query.toString();
        JsonObject copy = r.body.deepCopy();
        if (copy.has("password")) copy.addProperty("password", "***");
        return copy.toString();
    }

    private static Map<String, String> parseQuery(String raw) {
        Map<String, String> out = new HashMap<>();
        if (raw == null || raw.isEmpty()) return out;
        for (String part : raw.split("&")) {
            if (part.isEmpty()) continue;
            int eq = part.indexOf('=');
            String k = eq < 0 ? part : part.substring(0, eq);
            String v = eq < 0 ? "" : part.substring(eq + 1);
            out.put(URLDecoder.decode(k, StandardCharsets.UTF_8), URLDecoder.decode(v, StandardCharsets.UTF_8));
        }
        return out;
    }

    private static JsonObject readBody(HttpExchange ex) throws IOException {
        if (!"POST".equals(ex.getRequestMethod().toUpperCase()) && !"PUT".equals(ex.getRequestMethod().toUpperCase())) {
            return null;
        }
        byte[] data;
        try (InputStream in = ex.getRequestBody()) {
            data = in.readNBytes(MAX_BODY + 1);
        }
        if (data.length > MAX_BODY) throw new ApiException(413, "request body is larger than 1 MB");
        String text = new String(data, StandardCharsets.UTF_8).trim();
        if (text.isEmpty()) return new JsonObject();
        try {
            JsonElement e = JsonParser.parseString(text);
            if (!e.isJsonObject()) throw new ApiException(400, "request body must be a JSON object");
            return e.getAsJsonObject();
        } catch (JsonParseException e) {
            throw new ApiException(400, "invalid JSON: " + e.getMessage());
        }
    }

    private static Map<String, Object> error(String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("error", message);
        return m;
    }

    private static void send(HttpExchange ex, int status, Object payload) throws IOException {
        byte[] bytes = GSON.toJson(payload).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-store");
        ex.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = ex.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static void sendQuietly(HttpExchange ex, int status, Object payload) {
        try {
            send(ex, status, payload);
        } catch (IOException | IllegalStateException ignored) {
            // Headers were already sent or the client went away.
        }
    }
}
