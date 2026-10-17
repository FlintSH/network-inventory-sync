package dev.flint.invsync;

import org.junit.jupiter.api.Test;
import java.net.URLClassLoader;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

/** Verify the delivered artifact, including versioned classes Maven Shade must relocate. */
class PackagedDriverIT {
    @Test void shadedDriversAndModernAuthenticationClassesLoadFromReleaseJar() throws Exception {
        try (var loader = new URLClassLoader(new java.net.URL[]{Path.of(System.getProperty("pluginJar")).toUri().toURL()},getClass().getClassLoader())) {
            assertTrue(java.sql.Driver.class.isAssignableFrom(loader.loadClass("dev.flint.invsync.lib.postgresql.Driver")));
            assertTrue(java.sql.Driver.class.isAssignableFrom(loader.loadClass("dev.flint.invsync.lib.mariadb.Driver")));
            var auth = loader.loadClass("dev.flint.invsync.lib.mariadb.plugin.authentication.standard.ParsecPasswordPluginTool");
            assertTrue(auth.getResource("ParsecPasswordPluginTool.class").toString().contains("META-INF/versions/15/"));
            // The Java-8 fallback needs BouncyCastle. The Java-15 implementation must
            // use the JDK's Ed25519 implementation and work without that extra library.
            assertEquals(32, ((byte[])auth.getMethod("process",byte[].class).invoke(null,new byte[32])).length);
            var cleaner = loader.loadClass("dev.flint.invsync.lib.postgresql.util.LazyCleanerImpl");
            assertTrue(cleaner.getResource("LazyCleanerImpl.class").toString().contains("META-INF/versions/11/"));
        }
    }
}
