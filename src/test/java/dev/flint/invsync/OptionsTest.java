package dev.flint.invsync;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class OptionsTest {
    @Test void rejectsDefaultServerIdAndUnsafeOrInvalidOptions(){
        assertThrows(IllegalArgumentException.class,()->Options.read(Fixtures.configuration()));
        for(var entry:java.util.Map.<String,Object>of("database.table-prefix","x; DROP TABLE", "database.ssl-mode","prefer","sync.storage","yes","safety.verification-timeout-seconds",5,"sync.first-join","guess","unknown.setting",true).entrySet()){
            var c=Fixtures.configuration();c.set("server-id","test");c.set(entry.getKey(),entry.getValue());assertThrows(IllegalArgumentException.class,()->Options.read(c),entry.getKey());
        }
    }
    @Test void profileDependsOnSyncedFieldsButNotServerIdentityOrSecrets(){
        var c=Fixtures.configuration();c.set("server-id","first");Options first=Options.read(c);c.set("server-id","second");c.set("database.password","secret");Options second=Options.read(c);
        assertEquals(first.profile(),second.profile());assertFalse(second.toString().contains("secret"));c.set("sync.armour",false);assertNotEquals(first.profile(),Options.read(c).profile());
    }
    @Test void digestIsStableAndChangesWithPayload(){assertEquals(64,Options.digest(new byte[]{1}).length());assertNotEquals(Options.digest(new byte[]{1}),Options.digest(new byte[]{2}));}
}
