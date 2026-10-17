package dev.flint.invsync;

import org.bukkit.configuration.ConfigurationSection;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

record Options(String server, String group, DatabaseEngine engine, String host, int port, String database, String username, String password,
               String ssl, String sslRootCert, String prefix, int poolSize, int connectionTimeout, int queryTimeout,
               boolean storage, boolean armour, boolean offhand, boolean ender, boolean experience, boolean selected, boolean health, boolean hunger,
               boolean importFirst, int loginWait, int loginPoll, int pendingSeconds, int heartbeatSeconds, int verificationSeconds,
               int staleSeconds, int maxPayload, int historyLimit, int shutdownSeconds, int threads, int queueCapacity, int autosaveSeconds, int autosaveBatch) {
    static Options read(ConfigurationSection c) {
        rejectUnknown(c);
        if (integer(c, "config-version", 1, 1) != 1) throw new IllegalArgumentException("Unsupported config-version");
        String server = identifier(c, "server-id"), group = identifier(c, "group");
        if (server.equals("change-me")) throw new IllegalArgumentException("Set a unique server-id before starting");
        String prefix = required(c, "database.table-prefix");
        if (!prefix.matches("[a-z][a-z0-9_]{0,24}")) throw new IllegalArgumentException("Invalid database.table-prefix");
        String host = required(c, "database.host");
        if (!host.matches("[A-Za-z0-9.:\\[\\]_-]+")) throw new IllegalArgumentException("Invalid database.host");
        String db = required(c, "database.name");
        if (!db.matches("[A-Za-z0-9_-]+")) throw new IllegalArgumentException("Invalid database.name");
        String ssl = required(c, "database.ssl-mode");
        if (!Set.of("disable", "require", "verify-ca", "verify-full").contains(ssl)) throw new IllegalArgumentException("Invalid SSL mode");
        String password = c.getString("database.password", ""), env = c.getString("database.password-env", "");
        if (!env.isBlank()) { password = System.getenv(env); if (password == null) throw new IllegalArgumentException("Missing configured password environment variable"); }
        String first = required(c, "sync.first-join");
        if (!Set.of("import", "empty").contains(first)) throw new IllegalArgumentException("sync.first-join must be import or empty");
        int heartbeat = integer(c, "safety.heartbeat-seconds", 1, 60), verify = integer(c, "safety.verification-timeout-seconds", 5, 300);
        if (verify < heartbeat * 3) throw new IllegalArgumentException("verification-timeout-seconds must be at least 3 heartbeats");
        int pending = integer(c, "safety.pending-login-seconds", 15, 300), stale = integer(c, "safety.recovery-stale-seconds", 30, 86400);
        if (stale <= pending || stale <= verify * 2) throw new IllegalArgumentException("Recovery staleness must exceed pending-login and twice verification timeout");
        return new Options(server, group, DatabaseEngine.parse(c.getString("database.type", "postgresql")), host, integer(c,"database.port",1,65535), db, required(c,"database.username"), password, ssl, c.getString("database.ssl-root-cert",""), prefix,
            integer(c,"database.pool-size",2,64), integer(c,"database.connection-timeout-ms",1000,30000), integer(c,"database.query-timeout-seconds",1,30),
            bool(c,"sync.storage"),bool(c,"sync.armour"),bool(c,"sync.offhand"),bool(c,"sync.ender-chest"),bool(c,"sync.experience"),bool(c,"sync.selected-slot"),bool(c,"sync.health"),bool(c,"sync.hunger"),first.equals("import"),
            integer(c,"safety.login-wait-ms",1000,30000), integer(c,"safety.login-poll-ms",50,1000), pending,heartbeat,verify,stale,
            integer(c,"safety.max-payload-bytes",65536,16777216),integer(c,"safety.history-limit",2,200),integer(c,"safety.shutdown-wait-seconds",5,60),
            integer(c,"performance.io-threads",1,32),integer(c,"performance.io-queue-capacity",32,10000),integer(c,"performance.autosave-seconds",5,3600),integer(c,"performance.autosave-batch-size",1,100));
    }
    String profile() {
        String flags = "schema=1;storage="+storage+";armour="+armour+";offhand="+offhand+";ender="+ender+";xp="+experience+";slot="+selected+";health="+health+";hunger="+hunger+";first-join="+importFirst;
        return digest(flags.getBytes(StandardCharsets.UTF_8));
    }
    static String digest(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }
    private static String identifier(ConfigurationSection c, String key) {
        String value = required(c,key); if (!value.matches("[a-zA-Z0-9_-]{1,64}")) throw new IllegalArgumentException("Invalid " + key); return value;
    }
    private static String required(ConfigurationSection c, String key) {
        String value = c.getString(key); if (value == null || value.isBlank()) throw new IllegalArgumentException("Missing " + key); return value;
    }
    private static boolean bool(ConfigurationSection c, String key) {
        if (!c.isBoolean(key)) throw new IllegalArgumentException("Expected boolean: " + key); return c.getBoolean(key);
    }
    private static int integer(ConfigurationSection c, String key, int min, int max) {
        if (!c.isInt(key) || c.getInt(key) < min || c.getInt(key) > max) throw new IllegalArgumentException("Invalid integer: " + key + " ("+min+"–"+max+")"); return c.getInt(key);
    }
    private static void rejectUnknown(ConfigurationSection c) {
        try (var stream = Options.class.getResourceAsStream("/config.yml")) {
            var defaults = org.bukkit.configuration.file.YamlConfiguration.loadConfiguration(new java.io.InputStreamReader(Objects.requireNonNull(stream), StandardCharsets.UTF_8));
            for (String key : c.getKeys(true)) if (!defaults.contains(key)) throw new IllegalArgumentException("Unknown setting: " + key);
        } catch (java.io.IOException e) { throw new IllegalStateException(e); }
    }
    @Override public String toString() { return "Options[server="+server+", group="+group+", credentials=redacted]"; }
}
