package dev.flint.invsync;
import org.junit.jupiter.api.Test;
import java.io.*;
import java.util.Arrays;
import static org.junit.jupiter.api.Assertions.*;
class SnapshotTest {
    @Test void codecRoundTripsSlotsAndVitalsWithoutJavaDeserialization()throws Exception{
        Snapshot empty=Snapshot.empty();empty.storage()[8]=new byte[]{1,2,3};empty.armour()[2]=new byte[]{9};empty.ender()[26]=new byte[]{4};
        Snapshot s=new Snapshot(empty.storage(),empty.armour(),empty.offhand(),empty.ender(),empty.cursor(),42,2345,.5f,8,18.5,17,3.5f,2);
        Snapshot decoded=Snapshot.decode(s.encode(Fixtures.options(),"26.1.2"),Fixtures.options(),"26.1.2");
        assertArrayEquals(new byte[]{1,2,3},decoded.storage()[8]);assertArrayEquals(new byte[]{9},decoded.armour()[2]);assertEquals(42,decoded.level());assertEquals(.5f,decoded.experience());assertEquals(18.5,decoded.health());assertEquals(17,decoded.food());
    }
    @Test void rejectsWrongVersionProfileMagicTruncationAndTrailingData()throws Exception{
        Options o=Fixtures.options();byte[] bytes=Snapshot.empty().encode(o,"26.1.2");
        assertThrows(IOException.class,()->Snapshot.decode(bytes,o,"26.2"));var c=Fixtures.configuration();c.set("server-id","test");c.set("sync.health",true);assertThrows(IOException.class,()->Snapshot.decode(bytes,Options.read(c),"26.1.2"));
        byte[] magic=bytes.clone();magic[0]=0;assertThrows(IOException.class,()->Snapshot.decode(magic,o,"26.1.2"));
        assertThrows(IOException.class,()->Snapshot.decode(Arrays.copyOf(bytes,bytes.length-1),o,"26.1.2"));assertThrows(IOException.class,()->Snapshot.decode(Arrays.copyOf(bytes,bytes.length+1),o,"26.1.2"));
    }
    @Test void rejectsNonFiniteVitalsBeforeWriting() {
        Snapshot e=Snapshot.empty();Snapshot invalid=new Snapshot(e.storage(),e.armour(),e.offhand(),e.ender(),e.cursor(),0,0,Float.NaN,0,20,20,5,0);
        assertThrows(IOException.class,()->invalid.encode(Fixtures.options(),"26.1.2"));
    }
    @Test void rejectsOversizedAndMalformedItemLengths()throws Exception{
        Options o=Fixtures.options();Snapshot e=Snapshot.empty();e.storage()[0]=new byte[o.maxPayload()];assertThrows(IOException.class,()->e.encode(o,"26.1.2"));
        var bytes=new ByteArrayOutputStream();var out=new DataOutputStream(bytes);out.writeInt(0x4e495331);out.writeUTF("26.1.2");out.writeUTF(o.profile());out.writeInt(36);out.writeInt(Integer.MAX_VALUE);
        assertThrows(IOException.class,()->Snapshot.decode(bytes.toByteArray(),o,"26.1.2"));
    }
}
