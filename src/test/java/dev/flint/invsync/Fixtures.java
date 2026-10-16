package dev.flint.invsync;

import org.bukkit.configuration.file.YamlConfiguration;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

final class Fixtures {
    static YamlConfiguration configuration(){return YamlConfiguration.loadConfiguration(new InputStreamReader(Objects.requireNonNull(Fixtures.class.getResourceAsStream("/config.yml")),StandardCharsets.UTF_8));}
    static Options options(){var c=configuration();c.set("server-id","test-a");return Options.read(c);}
    static Options databaseOptions(String prefix,String server,String group){
        var c=configuration();c.set("server-id",server);c.set("group",group);c.set("database.table-prefix",prefix);
        c.set("database.host",System.getenv().getOrDefault("INVSYNC_TEST_HOST","127.0.0.1"));c.set("database.port",Integer.parseInt(System.getenv().getOrDefault("INVSYNC_TEST_PORT","55432")));
        c.set("database.name",System.getenv().getOrDefault("INVSYNC_TEST_DATABASE","invsync"));c.set("database.username",System.getenv().getOrDefault("INVSYNC_TEST_USER","invsync"));c.set("database.password",System.getenv().getOrDefault("INVSYNC_TEST_PASSWORD",""));c.set("database.ssl-mode","disable");
        c.set("safety.history-limit",3);return Options.read(c);
    }
}
