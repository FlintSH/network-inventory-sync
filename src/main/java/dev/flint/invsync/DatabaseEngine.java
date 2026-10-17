package dev.flint.invsync;

import com.zaxxer.hikari.HikariConfig;
import java.util.Locale;

/** SQL and JDBC differences only; ownership and transaction rules live in Store. */
enum DatabaseEngine {
    POSTGRESQL, MARIADB;

    static DatabaseEngine parse(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "postgresql" -> POSTGRESQL;
            case "mariadb" -> MARIADB;
            default -> throw new IllegalArgumentException("database.type must be postgresql or mariadb");
        };
    }

    String jdbcUrl(Options options) {
        String host = options.host();
        if (host.contains(":") && !host.startsWith("[")) host = "[" + host + "]";
        return "jdbc:" + name().toLowerCase(Locale.ROOT) + "://" + host + ":" + options.port() + "/" + options.database();
    }

    void configure(HikariConfig c, Options options) {
        c.setJdbcUrl(jdbcUrl(options));
        c.setTransactionIsolation("TRANSACTION_READ_COMMITTED");
        if (this == POSTGRESQL) {
            c.setDriverClassName("org.postgresql.Driver");
            c.addDataSourceProperty("sslmode", options.ssl());
            if (!options.sslRootCert().isBlank()) c.addDataSourceProperty("sslrootcert", options.sslRootCert());
            c.addDataSourceProperty("connectTimeout", Integer.toString((options.connectionTimeout() + 999) / 1000));
            c.addDataSourceProperty("socketTimeout", Integer.toString(options.queryTimeout() + 2));
        } else {
            c.setDriverClassName("org.mariadb.jdbc.Driver");
            c.addDataSourceProperty("sslMode", options.ssl().equals("require") ? "trust" : options.ssl());
            if (!options.sslRootCert().isBlank()) c.addDataSourceProperty("serverSslCert", options.sslRootCert());
            c.addDataSourceProperty("fallbackToSystemTrustStore", "true");
            // Connector/J uses milliseconds; PostgreSQL uses seconds.
            c.addDataSourceProperty("connectTimeout", Integer.toString(options.connectionTimeout()));
            c.addDataSourceProperty("socketTimeout", Integer.toString((options.queryTimeout() + 2) * 1000));
            c.addDataSourceProperty("useServerPrepStmts", "true");
            // Heartbeats must count a matching owner even if its timestamp did not change.
            c.addDataSourceProperty("useAffectedRows", "false");
            c.setConnectionInitSql("SET time_zone = '+00:00'");
        }
    }

    String insertIfAbsent(String key) {
        return this == POSTGRESQL ? " ON CONFLICT DO NOTHING" : " ON DUPLICATE KEY UPDATE " + key + "=" + key;
    }
    String binaryType() { return this == POSTGRESQL ? "BYTEA" : "LONGBLOB"; }
    String timestampType() { return this == POSTGRESQL ? "TIMESTAMPTZ" : "DATETIME(6)"; }
    String now() { return this == POSTGRESQL ? "CURRENT_TIMESTAMP" : "CURRENT_TIMESTAMP(6)"; }
    String tableOptions() { return this == POSTGRESQL ? "" : " ENGINE=InnoDB DEFAULT CHARACTER SET ascii COLLATE ascii_bin"; }
    String stalePredicate() {
        return "last_seen < " + now() + (this == POSTGRESQL ? " - (? * INTERVAL '1 second')" : " - INTERVAL ? SECOND");
    }
    String ageSeconds() {
        return this == POSTGRESQL ? "COALESCE(EXTRACT(EPOCH FROM (CURRENT_TIMESTAMP-last_seen)),0)"
            : "COALESCE(TIMESTAMPDIFF(SECOND,last_seen,CURRENT_TIMESTAMP(6)),0)";
    }
}
