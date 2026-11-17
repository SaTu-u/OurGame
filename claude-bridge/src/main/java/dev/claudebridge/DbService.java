package dev.claudebridge;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import dev.claudebridge.ApiServer.ApiException;
import dev.claudebridge.ApiServer.Request;
import org.bukkit.configuration.ConfigurationSection;
import org.sqlite.SQLiteConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

/** Read-only access to SQLite files and configured MySQL/MariaDB databases. */
final class DbService {

    private static final Set<String> READ_ONLY_KEYWORDS =
            Set.of("SELECT", "WITH", "PRAGMA", "EXPLAIN", "SHOW", "DESCRIBE", "DESC", "VALUES");

    private final ClaudeBridgePlugin plugin;
    private final FileService files;

    DbService(ClaudeBridgePlugin plugin, FileService files) {
        this.plugin = plugin;
        this.files = files;
    }

    private void checkEnabled() {
        if (!plugin.feature("databases")) throw new ApiException(403, "databases feature is disabled in config.yml");
    }

    Object connections() throws IOException {
        checkEnabled();
        List<Map<String, Object>> named = new ArrayList<>();
        ConfigurationSection sec = plugin.getConfig().getConfigurationSection("databases.connections");
        if (sec != null) {
            for (String key : sec.getKeys(false)) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("connection", key);
                m.put("url", sec.getString(key + ".url"));
                m.put("user", sec.getString(key + ".user"));
                named.add(m);
            }
        }
        List<Map<String, Object>> sqlite = new ArrayList<>();
        Path root = files.root();
        try (Stream<Path> s = Files.walk(root, 7)) {
            s.filter(Files::isRegularFile)
                    .filter(p -> {
                        String n = p.getFileName().toString().toLowerCase(Locale.ROOT);
                        return n.endsWith(".db") || n.endsWith(".sqlite") || n.endsWith(".sqlite3");
                    })
                    .limit(300)
                    .forEach(p -> {
                        Map<String, Object> m = new LinkedHashMap<>();
                        m.put("sqlite", files.rel(p));
                        try {
                            m.put("size", Files.size(p));
                        } catch (IOException e) {
                            m.put("size", null);
                        }
                        sqlite.add(m);
                    });
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("configured", named);
        out.put("sqliteFiles", sqlite);
        out.put("note", "H2 databases (*.mv.db, e.g. LuckPerms default storage) are not supported: the server has no H2 driver.");
        return out;
    }

    private Connection open(Request r) throws SQLException {
        String sqlitePath = r.str("sqlite", null);
        String name = r.str("connection", null);
        if (sqlitePath != null && !sqlitePath.isBlank()) {
            Path p = files.resolve(sqlitePath);
            if (!Files.isRegularFile(p)) throw new ApiException(404, "sqlite file not found: " + sqlitePath);
            try {
                SQLiteConfig cfg = new SQLiteConfig();
                cfg.setReadOnly(true);
                cfg.setBusyTimeout(3000);
                return cfg.createConnection("jdbc:sqlite:" + p);
            } catch (NoClassDefFoundError e) {
                throw new ApiException(500, "SQLite driver is not available on this server");
            }
        }
        if (name != null && !name.isBlank()) {
            ConfigurationSection sec = plugin.getConfig().getConfigurationSection("databases.connections." + name);
            if (sec == null) throw new ApiException(404, "unknown connection: " + name);
            String url = sec.getString("url");
            if (url == null) throw new ApiException(500, "connection " + name + " has no url");
            try {
                if (url.startsWith("jdbc:mysql:") || url.startsWith("jdbc:mariadb:")) Class.forName("com.mysql.cj.jdbc.Driver");
            } catch (ClassNotFoundException ignored) {
                // DriverManager will report a clear error below.
            }
            Connection c = DriverManager.getConnection(url, sec.getString("user"), sec.getString("password"));
            c.setReadOnly(true);
            c.setAutoCommit(false);
            return c;
        }
        throw new ApiException(400, "pass either 'sqlite' (file path) or 'connection' (name from config.yml)");
    }

    Object tables(Request r) {
        try {
            return tablesUnchecked(r);
        } catch (SQLException e) {
            throw sqlError(e);
        }
    }

    Object query(Request r) {
        try {
            return queryUnchecked(r);
        } catch (SQLException e) {
            throw sqlError(e);
        }
    }

    private static ApiException sqlError(SQLException e) {
        String msg = String.valueOf(e.getMessage());
        String lower = msg.toLowerCase(Locale.ROOT);
        if (lower.contains("readonly") || lower.contains("read-only") || lower.contains("read only")) {
            return new ApiException(403, "write blocked: the database connection is read-only");
        }
        return new ApiException(400, "SQL error: " + msg);
    }

    private Object tablesUnchecked(Request r) throws SQLException {
        checkEnabled();
        boolean withColumns = r.bool("columns", false);
        try (Connection c = open(r)) {
            DatabaseMetaData md = c.getMetaData();
            List<Map<String, Object>> out = new ArrayList<>();
            try (ResultSet rs = md.getTables(c.getCatalog(), null, "%", new String[]{"TABLE", "VIEW"})) {
                while (rs.next()) {
                    Map<String, Object> t = new LinkedHashMap<>();
                    String table = rs.getString("TABLE_NAME");
                    t.put("name", table);
                    t.put("type", rs.getString("TABLE_TYPE"));
                    if (withColumns) {
                        List<String> cols = new ArrayList<>();
                        try (ResultSet cr = md.getColumns(c.getCatalog(), null, table, "%")) {
                            while (cr.next()) cols.add(cr.getString("COLUMN_NAME") + " " + cr.getString("TYPE_NAME"));
                        }
                        t.put("columns", cols);
                    }
                    out.add(t);
                }
            }
            rollbackQuietly(c);
            return Map.of("tables", out);
        }
    }

    private Object queryUnchecked(Request r) throws SQLException {
        checkEnabled();
        String sql = validate(r.require("sql"));
        int configMax = plugin.getConfig().getInt("databases.max-rows", 500);
        int maxRows = Math.max(1, Math.min(r.integer("max_rows", configMax), 5000));
        try (Connection c = open(r); PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setMaxRows(maxRows + 1);
            try {
                ps.setQueryTimeout(15);
            } catch (SQLException ignored) {
                // Not supported by every driver.
            }
            JsonArray params = r.array("params");
            if (params != null) {
                for (int i = 0; i < params.size(); i++) {
                    JsonElement e = params.get(i);
                    if (e == null || e.isJsonNull()) ps.setObject(i + 1, null);
                    else if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isNumber()) ps.setObject(i + 1, e.getAsBigDecimal());
                    else if (e.isJsonPrimitive() && e.getAsJsonPrimitive().isBoolean()) ps.setBoolean(i + 1, e.getAsBoolean());
                    else ps.setString(i + 1, e.isJsonPrimitive() ? e.getAsString() : e.toString());
                }
            }
            Map<String, Object> out = new LinkedHashMap<>();
            if (ps.execute()) {
                try (ResultSet rs = ps.getResultSet()) {
                    readResult(rs, maxRows, out);
                }
            } else {
                out.put("updateCount", ps.getUpdateCount());
            }
            rollbackQuietly(c);
            return out;
        }
    }

    private static void readResult(ResultSet rs, int maxRows, Map<String, Object> out) throws SQLException {
        ResultSetMetaData md = rs.getMetaData();
        int n = md.getColumnCount();
        List<String> columns = new ArrayList<>();
        for (int i = 1; i <= n; i++) columns.add(md.getColumnLabel(i));
        List<List<Object>> rows = new ArrayList<>();
        boolean truncated = false;
        while (rs.next()) {
            if (rows.size() >= maxRows) {
                truncated = true;
                break;
            }
            List<Object> row = new ArrayList<>(n);
            for (int i = 1; i <= n; i++) row.add(convert(rs.getObject(i)));
            rows.add(row);
        }
        out.put("columns", columns);
        out.put("rows", rows);
        out.put("rowCount", rows.size());
        out.put("truncated", truncated);
    }

    private static Object convert(Object v) {
        if (v == null) return null;
        if (v instanceof Number || v instanceof Boolean) return v;
        if (v instanceof byte[] b) {
            byte[] head = b.length > 1024 ? java.util.Arrays.copyOf(b, 1024) : b;
            return "base64:" + Base64.getEncoder().encodeToString(head) + (b.length > 1024 ? "...(" + b.length + " bytes)" : "");
        }
        String s = String.valueOf(v);
        return s.length() > 4000 ? s.substring(0, 4000) + "..." : s;
    }

    /** Allows exactly one read-only statement. The connection itself is read-only as well. */
    static String validate(String sql) {
        String s = sql.replaceAll("(?s)/\\*.*?\\*/", " ").replaceAll("(?m)--.*$", " ").trim();
        while (s.endsWith(";")) s = s.substring(0, s.length() - 1).trim();
        if (s.isEmpty()) throw new ApiException(400, "empty query");
        if (s.contains(";")) throw new ApiException(400, "only one statement per request (use params instead of ';' in values)");
        String first = s.split("\\s+", 2)[0].toUpperCase(Locale.ROOT);
        if (!READ_ONLY_KEYWORDS.contains(first)) {
            throw new ApiException(403, "only read-only queries are allowed (SELECT, WITH, PRAGMA, EXPLAIN, SHOW, DESCRIBE)");
        }
        return s;
    }

    private static void rollbackQuietly(Connection c) {
        try {
            if (!c.getAutoCommit()) c.rollback();
        } catch (SQLException ignored) {
        }
    }
}
