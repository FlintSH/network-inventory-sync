package dev.flint.invsync;

import com.zaxxer.hikari.*;
import java.sql.*;
import java.util.*;

/** All updates are fenced by a random owner token AND a revision under a row lock. */
final class Store implements AutoCloseable {
    record Acquired(UUID token, long revision, byte[] payload) { }
    record Info(long revision, String server, String token, long staleSeconds, String checksum) { }
    record History(long revision, String checksum, String created) { }
    static final class Busy extends SQLException { Busy() { super("Inventory is locked by an existing session"); } }
    static final class Fenced extends SQLException { Fenced() { super("Inventory ownership or revision changed"); } }
    static final class Incompatible extends SQLException { Incompatible() { super("Sync group Minecraft version/settings do not match"); } }
    private final HikariDataSource pool;
    private final Options options;
    private final DatabaseEngine engine;
    private final Properties connectionProperties;
    private final String data, groups, history;
    Store(Options options) {
        this.options = options; engine = options.engine(); data = options.prefix()+"players"; groups = options.prefix()+"groups"; history = options.prefix()+"history";
        HikariConfig c = new HikariConfig();
        engine.configure(c, options);
        c.setUsername(options.username()); c.setPassword(options.password());
        connectionProperties = new Properties(); connectionProperties.putAll(c.getDataSourceProperties());
        connectionProperties.setProperty("user", options.username()); connectionProperties.setProperty("password", options.password());
        c.setConnectionTimeout(options.connectionTimeout()); c.setValidationTimeout(1000); c.setInitializationFailTimeout(-1);
        c.setMaximumPoolSize(options.poolSize()); c.setMinimumIdle(0); c.setPoolName("InventorySync"); pool = new HikariDataSource(c);
    }
    private PreparedStatement statement(Connection c, String sql) throws SQLException { var p = c.prepareStatement(sql); p.setQueryTimeout(options.queryTimeout()); return p; }
    void initialize(String minecraftVersion) throws SQLException {
        // MariaDB DDL implicitly commits. A dedicated physical connection holds its
        // named catalog lock across DDL/metadata transactions; closing it always
        // releases the lock, including after an ambiguous response or failed startup.
        try (Connection c = engine == DatabaseEngine.MARIADB
                ? new org.mariadb.jdbc.Driver().connect(engine.jdbcUrl(options), connectionProperties)
                : pool.getConnection()) {
            boolean maria = engine == DatabaseEngine.MARIADB;
            String lockName = "invsync:" + Options.digest((options.database()+":"+options.prefix()).getBytes(java.nio.charset.StandardCharsets.UTF_8)).substring(0,48);
            if (maria) try (var lock = statement(c, "SELECT GET_LOCK(?, ?)")) {
                lock.setString(1, lockName); lock.setInt(2, options.queryTimeout());
                try (var r = lock.executeQuery()) { if (!r.next() || r.getInt(1) != 1) throw new SQLException("Schema initialization lock unavailable"); }
            }
            c.setAutoCommit(false);
            try {
                // Serialize first-time PostgreSQL catalog creation across starting backends.
                if (!maria) try (var lock = statement(c,"SELECT pg_advisory_xact_lock(1313428307, hashtext(?))")) {
                    lock.setString(1,options.prefix());lock.execute();
                }
                try (Statement s = c.createStatement()) {
                    s.setQueryTimeout(options.queryTimeout());
                    s.execute("CREATE TABLE IF NOT EXISTS "+groups+" (group_id VARCHAR(64) PRIMARY KEY, minecraft VARCHAR(32) NOT NULL, profile CHAR(64) NOT NULL)"+engine.tableOptions());
                    s.execute("CREATE TABLE IF NOT EXISTS "+data+" (group_id VARCHAR(64) NOT NULL, player_id VARCHAR(36) NOT NULL, revision BIGINT NOT NULL DEFAULT 0, payload "+engine.binaryType()+", checksum CHAR(64), owner_token VARCHAR(36), server_id VARCHAR(64), last_seen "+engine.timestampType()+", PRIMARY KEY(group_id,player_id))"+engine.tableOptions());
                    s.execute("CREATE TABLE IF NOT EXISTS "+history+" (group_id VARCHAR(64) NOT NULL, player_id VARCHAR(36) NOT NULL, revision BIGINT NOT NULL, payload "+engine.binaryType()+" NOT NULL, checksum CHAR(64) NOT NULL, created_at "+engine.timestampType()+" NOT NULL DEFAULT "+engine.now()+", PRIMARY KEY(group_id,player_id,revision))"+engine.tableOptions());
                }
                if (maria) verifyMariaTables(c);
                try (var p = statement(c,"INSERT INTO "+groups+" VALUES (?,?,?)"+engine.insertIfAbsent("group_id"))) { p.setString(1,options.group());p.setString(2,minecraftVersion);p.setString(3,options.profile());p.executeUpdate(); }
                try (var p = statement(c,"SELECT minecraft,profile FROM "+groups+" WHERE group_id=?")) {
                    p.setString(1,options.group()); try (var r=p.executeQuery()) { if (!r.next() || !r.getString(1).equals(minecraftVersion) || !r.getString(2).equals(options.profile())) throw new Incompatible(); }
                }
                c.commit();
            } catch(SQLException e) { c.rollback();throw e; }
        }
    }
    private void verifyMariaTables(Connection c) throws SQLException {
        // CREATE IF NOT EXISTS cannot repair a manually created nontransactional table.
        // Reject it before allowing gameplay instead of silently losing rollback/fencing.
        try (var p=statement(c,"SELECT t.ENGINE,c.COLLATION_NAME FROM information_schema.TABLES t JOIN information_schema.COLUMNS c ON t.TABLE_SCHEMA=c.TABLE_SCHEMA AND t.TABLE_NAME=c.TABLE_NAME WHERE t.TABLE_SCHEMA=DATABASE() AND t.TABLE_NAME IN (?,?,?) AND c.COLUMN_NAME='group_id'")) {
            p.setString(1,groups);p.setString(2,data);p.setString(3,history);int count=0;
            try(var r=p.executeQuery()){while(r.next()){count++;if(!"InnoDB".equalsIgnoreCase(r.getString(1))||!"ascii_bin".equals(r.getString(2)))throw new SQLException("MariaDB sync tables require InnoDB and ascii_bin group IDs");}}
            if(count!=3)throw new SQLException("Incomplete MariaDB sync schema");
        }
    }
    Acquired acquire(UUID player) throws SQLException {
        UUID token = UUID.randomUUID();
        try (Connection c=pool.getConnection()) {
            c.setAutoCommit(false);
            try {
                try (var p=statement(c,"INSERT INTO "+data+" (group_id,player_id) VALUES (?,?)"+engine.insertIfAbsent("player_id"))) { identity(p,player);p.executeUpdate(); }
                long revision;byte[] payload;
                try (var p=statement(c,"SELECT revision,payload,checksum,owner_token FROM "+data+" WHERE group_id=? AND player_id=? FOR UPDATE")) {
                    identity(p,player);try(var r=p.executeQuery()) { if(!r.next())throw new SQLException("Missing player row");if(r.getString(4)!=null)throw new Busy();revision=r.getLong(1);payload=r.getBytes(2);validate(payload,r.getString(3)); }
                }
                try(var p=statement(c,"UPDATE "+data+" SET owner_token=?,server_id=?,last_seen="+engine.now()+" WHERE group_id=? AND player_id=?")) {
                    p.setString(1,token.toString());p.setString(2,options.server());p.setString(3,options.group());p.setString(4,player.toString());p.executeUpdate();
                }
                c.commit();return new Acquired(token,revision,payload);
            } catch(Exception e) { c.rollback();throw e; }
        }
    }
    long save(UUID player, UUID token, long revision, byte[] payload, boolean release) throws SQLException {
        return save(player,token,revision,payload,release,false);
    }
    long replay(UUID player,UUID token,long revision,byte[] payload)throws SQLException{return save(player,token,revision,payload,true,true);}
    private long save(UUID player,UUID token,long revision,byte[] payload,boolean release,boolean stale)throws SQLException{
        if (payload == null || payload.length>options.maxPayload()) throw new SQLException("Invalid snapshot size");
        String checksum=Options.digest(payload);long next=revision+1;
        try(Connection c=pool.getConnection()) {
            c.setAutoCommit(false);
            try {
                try(var p=statement(c,"UPDATE "+data+" SET revision=?,payload=?,checksum=?,owner_token=CASE WHEN ? THEN NULL ELSE owner_token END,server_id=CASE WHEN ? THEN NULL ELSE server_id END,last_seen="+engine.now()+" WHERE group_id=? AND player_id=? AND owner_token=? AND revision=?"+(stale?" AND "+engine.stalePredicate():""))) {
                    p.setLong(1,next);p.setBytes(2,payload);p.setString(3,checksum);p.setBoolean(4,release);p.setBoolean(5,release);p.setString(6,options.group());p.setString(7,player.toString());p.setString(8,token.toString());p.setLong(9,revision);if(stale)p.setInt(10,options.staleSeconds());
                    if(p.executeUpdate()!=1)throw new Fenced();
                }
                try(var p=statement(c,"INSERT INTO "+history+" (group_id,player_id,revision,payload,checksum) VALUES (?,?,?,?,?)")) { identity(p,player);p.setLong(3,next);p.setBytes(4,payload);p.setString(5,checksum);p.executeUpdate(); }
                try(var p=statement(c,"DELETE FROM "+history+" WHERE group_id=? AND player_id=? AND revision<=?")) { identity(p,player);p.setLong(3,next-options.historyLimit());p.executeUpdate(); }
                c.commit();return next;
            }catch(Exception e){c.rollback();throw e;}
        }
    }
    void heartbeat(UUID player, UUID token) throws SQLException {
        try(Connection c=pool.getConnection();var p=statement(c,"UPDATE "+data+" SET last_seen="+engine.now()+" WHERE group_id=? AND player_id=? AND owner_token=?")) {
            identity(p,player);p.setString(3,token.toString());if(p.executeUpdate()!=1)throw new Fenced();
        }
    }
    void abandon(UUID player, UUID token) throws SQLException {
        try(Connection c=pool.getConnection();var p=statement(c,"UPDATE "+data+" SET owner_token=NULL,server_id=NULL WHERE group_id=? AND player_id=? AND owner_token=?")) { identity(p,player);p.setString(3,token.toString());if(p.executeUpdate()!=1)throw new Fenced(); }
    }
    Info inspect(UUID player) throws SQLException {
        try(Connection c=pool.getConnection();var p=statement(c,"SELECT revision,server_id,owner_token,"+engine.ageSeconds()+",checksum FROM "+data+" WHERE group_id=? AND player_id=?")) {
            identity(p,player);try(var r=p.executeQuery()){return r.next()?new Info(r.getLong(1),r.getString(2),r.getString(3),r.getLong(4),r.getString(5)):null;}
        }
    }
    void recover(UUID player, long expectedRevision) throws SQLException {
        try(Connection c=pool.getConnection();var p=statement(c,"UPDATE "+data+" SET owner_token=NULL,server_id=NULL WHERE group_id=? AND player_id=? AND revision=? AND owner_token IS NOT NULL AND "+engine.stalePredicate())) {
            identity(p,player);p.setLong(3,expectedRevision);p.setInt(4,options.staleSeconds());
            if(p.executeUpdate()!=1){
                try(var check=statement(c,"SELECT revision FROM "+data+" WHERE group_id=? AND player_id=? AND revision=? AND owner_token IS NULL")){
                    identity(check,player);check.setLong(3,expectedRevision);try(var r=check.executeQuery()){if(!r.next())throw new Fenced();}
                }
            }
        }
    }
    List<History> history(UUID player) throws SQLException {
        try(Connection c=pool.getConnection();var p=statement(c,"SELECT revision,checksum,created_at FROM "+history+" WHERE group_id=? AND player_id=? ORDER BY revision DESC")) {
            identity(p,player);List<History> result=new ArrayList<>();try(var r=p.executeQuery()){while(r.next())result.add(new History(r.getLong(1),r.getString(2),r.getString(3)));}return List.copyOf(result);
        }
    }
    byte[] historyPayload(UUID player,long revision) throws SQLException {
        try(Connection c=pool.getConnection();var p=statement(c,"SELECT payload,checksum FROM "+history+" WHERE group_id=? AND player_id=? AND revision=?")) {
            identity(p,player);p.setLong(3,revision);try(var r=p.executeQuery()){if(!r.next())throw new SQLException("Snapshot not found");byte[] bytes=r.getBytes(1);validate(bytes,r.getString(2));return bytes;}
        }
    }
    private void validate(byte[] payload,String checksum)throws SQLException {
        if(payload!=null&&(payload.length>options.maxPayload()||!Options.digest(payload).equals(checksum)))throw new SQLException("Snapshot checksum/size mismatch");
    }
    private void identity(PreparedStatement p,UUID player)throws SQLException{p.setString(1,options.group());p.setString(2,player.toString());}
    @Override public void close(){pool.close();}
}
