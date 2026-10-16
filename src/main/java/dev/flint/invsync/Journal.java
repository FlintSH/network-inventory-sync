package dev.flint.invsync;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;

/** A failed save keeps the final payload on disk. Tokens/revisions prohibit blind replay. */
final class Journal {
    record Entry(UUID player,UUID token,long revision,byte[] payload) { }
    private final Path directory;
    private final int max;
    Journal(Path directory,int max)throws IOException{this.directory=directory;this.max=max;Files.createDirectories(directory);try{Files.setPosixFilePermissions(directory,java.nio.file.attribute.PosixFilePermissions.fromString("rwx------"));}catch(UnsupportedOperationException ignored){}}
    Path write(Entry entry)throws IOException {
        if(entry.payload().length>max)throw new IOException("Journal payload too large");
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(var out=new DataOutputStream(bytes)){
            out.writeInt(0x4e494a31);out.writeUTF(entry.player().toString());out.writeUTF(entry.token().toString());out.writeLong(entry.revision());out.writeUTF(Options.digest(entry.payload()));out.writeInt(entry.payload().length);out.write(entry.payload());
        }
        Path target=path(entry.player(),entry.token()),temp=Files.createTempFile(directory,".pending-",".tmp");
        try{
            try(var channel=FileChannel.open(temp,StandardOpenOption.WRITE)){ByteBuffer buffer=ByteBuffer.wrap(bytes.toByteArray());while(buffer.hasRemaining())channel.write(buffer);channel.force(true);}
            Files.move(temp,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
            try(var channel=FileChannel.open(directory,StandardOpenOption.READ)){channel.force(true);}
        }finally{Files.deleteIfExists(temp);}
        return target;
    }
    Entry read(UUID player,UUID token)throws IOException {
        Path file=path(player,token);if(Files.size(file)>max+512)throw new IOException("Journal file too large");
        try(var in=new DataInputStream(Files.newInputStream(file))){
            if(in.readInt()!=0x4e494a31||!in.readUTF().equals(player.toString())||!in.readUTF().equals(token.toString()))throw new IOException("Journal identity mismatch");
            long revision=in.readLong();String digest=in.readUTF();int length=in.readInt();if(length<1||length>max)throw new IOException("Journal length invalid");byte[] bytes=in.readNBytes(length);
            if(bytes.length!=length||in.read()!=-1||!Options.digest(bytes).equals(digest))throw new IOException("Journal checksum/length mismatch");return new Entry(player,token,revision,bytes);
        }
    }
    void delete(UUID player,UUID token)throws IOException{Files.deleteIfExists(path(player,token));}
    Path path(UUID player,UUID token){return directory.resolve(player+"."+token+".bin");}
}
