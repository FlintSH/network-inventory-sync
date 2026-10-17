# Third-party code

The plugin code is MIT licensed. The shaded runtime JAR also includes:

| Library | Version | License |
| --- | --- | --- |
| HikariCP | 7.1.0 | Apache 2.0 |
| PostgreSQL JDBC | 42.7.14 | BSD 2-clause |
| MariaDB Connector/J | 3.5.10 | LGPL 2.1 or later |
| Checker Framework annotations | 3.49.2 | MIT |

Full notices are in `META-INF/invsync-licenses/` inside the plugin JAR. MariaDB Connector/J's existing embedded notices are retained. Paper, Adventure and SLF4J are server-provided, not bundled.

Every release includes `mariadb-java-client-3.5.10-sources.jar`, the corresponding unmodified library source from [Maven Central](https://repo.maven.apache.org/maven2/org/mariadb/jdbc/mariadb-java-client/3.5.10/). The [upstream source and build files](https://github.com/mariadb-corporation/mariadb-connector-j/tree/3.5.10) are also available. The only transformation applied by this project is Maven Shade package/service relocation from `org.mariadb.jdbc` to `dev.flint.invsync.lib.mariadb`, specified in `pom.xml`. Its Java multi-release entries are preserved.

You may modify the library and rebuild/relink the plugin with it. Build your modified MariaDB source and install its artifact into your local Maven repository, then run `mvn -Dmariadb.version=<your-local-version> clean verify` in this plugin's corresponding source checkout using Java 25. The public source and POM include the code and build recipe needed to regenerate the combined JAR. No signature, license key, or other installation restriction prevents use of that modified JAR. Reverse engineering for debugging modifications to the LGPL library is permitted. Keep the library's license and corresponding source available when redistributing a modified build.
