package dev.flint.invsync;

import com.zaxxer.hikari.HikariConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.sql.*;
import java.util.Properties;
import static org.junit.jupiter.api.Assertions.*;

/** Optional real TLS checks: point at an isolated MariaDB with a supplied test CA. */
@EnabledIfEnvironmentVariable(named="INVSYNC_TEST_TYPE", matches="mariadb")
@EnabledIfEnvironmentVariable(named="INVSYNC_TEST_TLS_CA", matches=".+")
class MariaTlsIntegrationTest {
    Connection connect(String mode, String host, String certificate) throws SQLException {
        Options base=Fixtures.databaseOptions("tls_","tls-test","tls");
        var c=Fixtures.configuration();c.set("server-id","tls-test");c.set("database.type","mariadb");
        c.set("database.host",host);c.set("database.port",base.port());c.set("database.name",base.database());
        c.set("database.username",base.username());c.set("database.password",base.password());
        c.set("database.ssl-mode",mode);c.set("database.ssl-root-cert",certificate);
        var options=Options.read(c);var hikari=new HikariConfig();options.engine().configure(hikari,options);
        Properties props=new Properties();props.putAll(hikari.getDataSourceProperties());props.setProperty("user",options.username());props.setProperty("password",options.password());
        return new org.mariadb.jdbc.Driver().connect(hikari.getJdbcUrl(),props);
    }
    String host(){return System.getenv().getOrDefault("INVSYNC_TEST_HOST","127.0.0.1");}
    String ca(){return System.getenv("INVSYNC_TEST_TLS_CA");}
    void encrypted(Connection connection)throws SQLException{
        try(connection;var s=connection.createStatement();var r=s.executeQuery("SHOW SESSION STATUS LIKE 'Ssl_cipher'")){
            assertTrue(r.next());assertFalse(r.getString(2).isBlank(),"TLS was not negotiated");
        }
    }
    @Test void requireAndVerifiedModesActuallyEncrypt()throws Exception{
        encrypted(connect("require",host(),""));encrypted(connect("verify-ca",host(),ca()));encrypted(connect("verify-full",host(),ca()));
    }
    @Test @EnabledIfEnvironmentVariable(named="INVSYNC_TEST_TLS_WRONG_HOST",matches=".+")
    void verifyFullRejectsHostnameMismatchWhileVerifyCaAcceptsTrustedCertificate()throws Exception{
        String wrong=System.getenv("INVSYNC_TEST_TLS_WRONG_HOST");
        encrypted(connect("verify-ca",wrong,ca()));assertThrows(SQLException.class,()->connect("verify-full",wrong,ca()));
    }
    @Test @EnabledIfEnvironmentVariable(named="INVSYNC_TEST_TLS_UNTRUSTED_CA",matches=".+")
    void verifiedModesRejectUntrustedCertificates(){
        String untrusted=System.getenv("INVSYNC_TEST_TLS_UNTRUSTED_CA");
        assertThrows(SQLException.class,()->connect("verify-ca",host(),untrusted));assertThrows(SQLException.class,()->connect("verify-full",host(),untrusted));
    }
}
