package dev.flint.invsync;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.io.IOException;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
class JournalTest {
    @TempDir Path directory;
    @Test void durableRoundtripAndAtomicReplacement()throws Exception{
        Journal j=new Journal(directory,1024);UUID player=UUID.randomUUID(),token=UUID.randomUUID();j.write(new Journal.Entry(player,token,7,new byte[]{1,2,3}));assertEquals(7,j.read(player,token).revision());
        j.write(new Journal.Entry(player,token,8,new byte[]{4,5}));assertArrayEquals(new byte[]{4,5},j.read(player,token).payload());try(var files=Files.list(directory)){assertEquals(1,files.count());}j.delete(player,token);assertFalse(Files.exists(j.path(player,token)));
    }
    @Test void rejectsCorruptionTruncationAndOversize()throws Exception{
        Journal j=new Journal(directory,1024);UUID player=UUID.randomUUID(),token=UUID.randomUUID();j.write(new Journal.Entry(player,token,0,new byte[]{1,2}));byte[] b=Files.readAllBytes(j.path(player,token));b[b.length-1]^=1;Files.write(j.path(player,token),b);assertThrows(IOException.class,()->j.read(player,token));
        Files.write(j.path(player,token),new byte[2000]);assertThrows(IOException.class,()->j.read(player,token));assertThrows(IOException.class,()->j.write(new Journal.Entry(player,token,0,new byte[1025])));
    }
}
