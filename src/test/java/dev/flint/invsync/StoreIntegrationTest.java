package dev.flint.invsync;

import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="INVSYNC_TEST_DATABASE",matches=".+")
class StoreIntegrationTest {
    String prefix;Options options;Store a,b;
    @BeforeEach void setup()throws Exception{prefix="t"+UUID.randomUUID().toString().replace("-","").substring(0,12)+"_";options=Fixtures.databaseOptions(prefix,"server-a","survival");a=new Store(options);b=new Store(Fixtures.databaseOptions(prefix,"server-b","survival"));a.initialize("26.1.2");b.initialize("26.1.2");}
    Connection connection()throws SQLException{return DriverManager.getConnection("jdbc:postgresql://"+options.host()+":"+options.port()+"/"+options.database(),options.username(),options.password());}
    @AfterEach void close()throws Exception{a.close();b.close();try(var c=connection();var s=c.createStatement()){s.execute("DROP TABLE "+prefix+"history,"+prefix+"players,"+prefix+"groups");}}
    byte[] payload()throws Exception{return Snapshot.empty().encode(options,"26.1.2");}
    void stale(UUID player)throws Exception{try(var c=connection();var p=c.prepareStatement("UPDATE "+prefix+"players SET last_seen=CURRENT_TIMESTAMP-INTERVAL '1 hour' WHERE player_id=?")){p.setString(1,player.toString());p.executeUpdate();}}
    @Test void cleanHandoffIsAtomicAndOldTokenCannotOverwriteNextServer()throws Exception{
        UUID player=UUID.randomUUID();var first=a.acquire(player);assertNull(first.payload());assertThrows(Store.Busy.class,()->b.acquire(player));
        assertEquals(1,a.save(player,first.token(),0,payload(),true));var second=b.acquire(player);assertEquals(1,second.revision());assertArrayEquals(payload(),second.payload());
        assertThrows(Store.Fenced.class,()->a.save(player,first.token(),1,payload(),true));b.abandon(player,second.token());
    }
    @Test void exactlyOneConcurrentAcquirerWins()throws Exception{
        UUID player=UUID.randomUUID();var pool=Executors.newFixedThreadPool(2);var gate=new CountDownLatch(1);
        try{Callable<Boolean> acquire=()->{gate.await();try{a.acquire(player);return true;}catch(Store.Busy e){return false;}};Future<Boolean> x=pool.submit(acquire),y=pool.submit(acquire);gate.countDown();assertNotEquals(x.get(),y.get());}finally{pool.shutdownNow();}
    }
    @Test void staleOwnerIsNeverAutomaticallyTakenOverAndRecoveryIsRevisionFenced()throws Exception{
        UUID player=UUID.randomUUID();var first=a.acquire(player);a.save(player,first.token(),0,payload(),false);
        assertThrows(Store.Fenced.class,()->b.recover(player,1));stale(player);assertThrows(Store.Busy.class,()->b.acquire(player));assertThrows(Store.Fenced.class,()->b.recover(player,0));
        b.recover(player,1);var next=b.acquire(player);assertThrows(Store.Fenced.class,()->a.heartbeat(player,first.token()));assertThrows(Store.Fenced.class,()->a.save(player,first.token(),1,payload(),true));b.abandon(player,next.token());
    }
    @Test void failedRevisionDoesNotChangeSnapshotOrHistory()throws Exception{
        UUID player=UUID.randomUUID();var first=a.acquire(player);a.save(player,first.token(),0,payload(),false);assertThrows(Store.Fenced.class,()->a.save(player,first.token(),0,new byte[]{9},true));assertEquals(1,a.inspect(player).revision());assertEquals(1,a.history(player).size());
    }
    @Test void historyIsBoundedAndReplayRequiresStaleMatchingOwner()throws Exception{
        UUID player=UUID.randomUUID();var first=a.acquire(player);for(int i=0;i<5;i++)a.save(player,first.token(),i,payload(),false);assertEquals(3,a.history(player).size());assertEquals(5,a.history(player).getFirst().revision());
        assertThrows(Store.Fenced.class,()->a.replay(player,first.token(),5,payload()));stale(player);assertEquals(6,a.replay(player,first.token(),5,payload()));assertNull(a.inspect(player).token());
    }
    @Test void versionAndProfileMismatchRefuseInitializationAndGroupsAreIsolated()throws Exception{
        assertThrows(Store.Incompatible.class,()->b.initialize("26.2"));
        var c=Fixtures.configuration();c.set("server-id","third");c.set("database.host",options.host());c.set("database.port",options.port());c.set("database.name",options.database());c.set("database.username",options.username());c.set("database.password",options.password());c.set("database.ssl-mode","disable");c.set("database.table-prefix",prefix);c.set("sync.health",true);
        try(Store mismatch=new Store(Options.read(c))){assertThrows(Store.Incompatible.class,()->mismatch.initialize("26.1.2"));}
        try(Store other=new Store(Fixtures.databaseOptions(prefix,"third","creative"))){other.initialize("26.1.2");UUID player=UUID.randomUUID();a.acquire(player);assertNotNull(other.acquire(player));}
    }
    @Test void corruptSnapshotIsRejectedAndWrongTokenCannotReleaseSession()throws Exception{
        UUID player=UUID.randomUUID();var first=a.acquire(player);assertThrows(Store.Fenced.class,()->a.abandon(player,UUID.randomUUID()));a.save(player,first.token(),0,payload(),true);
        try(var c=connection();var p=c.prepareStatement("UPDATE "+prefix+"players SET payload=? WHERE player_id=?")){p.setBytes(1,new byte[]{1,2});p.setString(2,player.toString());p.executeUpdate();}
        assertThrows(SQLException.class,()->b.acquire(player));assertNull(a.inspect(player).token());
    }
    @Test void historyInsertFailureRollsBackSnapshotRevisionAndOwnerRelease()throws Exception{
        UUID player=UUID.randomUUID();var first=a.acquire(player);a.save(player,first.token(),0,payload(),false);
        try(var c=connection();var p=c.prepareStatement("INSERT INTO "+prefix+"history (group_id,player_id,revision,payload,checksum) VALUES (?,?,?,?,?)")){
            p.setString(1,options.group());p.setString(2,player.toString());p.setLong(3,2);p.setBytes(4,payload());p.setString(5,Options.digest(payload()));p.executeUpdate();
        }
        assertThrows(SQLException.class,()->a.save(player,first.token(),1,payload(),true));
        assertEquals(1,a.inspect(player).revision());assertEquals(first.token().toString(),a.inspect(player).token());assertThrows(Store.Busy.class,()->b.acquire(player));
    }
    @Test void recoveryCanAcknowledgeAlreadyUnlockedCommitButStillRequiresCurrentRevision()throws Exception{
        UUID player=UUID.randomUUID();var first=a.acquire(player);a.save(player,first.token(),0,payload(),true);
        assertThrows(Store.Fenced.class,()->a.recover(player,0));a.recover(player,1);assertEquals(1,a.inspect(player).revision());
        var next=b.acquire(player);assertThrows(Store.Fenced.class,()->a.recover(player,1));b.abandon(player,next.token());
    }
    @Test void twoServersCanInitializeOneBrandNewSchemaConcurrently()throws Exception{
        String fresh=prefix+"boot_";
        try(Store first=new Store(Fixtures.databaseOptions(fresh,"first","survival"));Store second=new Store(Fixtures.databaseOptions(fresh,"second","survival"))){
            var executor=Executors.newFixedThreadPool(2);var gate=new CountDownLatch(1);
            try{
                Future<?> x=executor.submit(()->{try{gate.await();first.initialize("26.1.2");}catch(Exception e){throw new RuntimeException(e);}});
                Future<?> y=executor.submit(()->{try{gate.await();second.initialize("26.1.2");}catch(Exception e){throw new RuntimeException(e);}});
                gate.countDown();x.get(10,TimeUnit.SECONDS);y.get(10,TimeUnit.SECONDS);
            }finally{executor.shutdownNow();}
        }finally{try(var c=connection();var statement=c.createStatement()){statement.execute("DROP TABLE IF EXISTS "+fresh+"history,"+fresh+"players,"+fresh+"groups");}}
    }
}
