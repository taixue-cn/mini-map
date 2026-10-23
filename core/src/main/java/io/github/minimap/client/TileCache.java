package io.github.minimap.client;

import io.github.minimap.protocol.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

/** A rebuildable cache with bounded reads and atomic replacement. Call on the storage worker. */
public final class TileCache {
    public record Entry(TileCodec.Encoded encoded,TileData tile){}
    private final Path root;
    private final long maxBytes;
    private int writes;
    public TileCache(Path root,long maxBytes){this.root=root;this.maxBytes=maxBytes;}
    private Path path(CacheIdentity identity,TilePos pos){return root.resolve(identity.key()).resolve(pos.x()+"_"+pos.z()+".tile");}
    public Optional<Entry> read(CacheIdentity identity,TilePos pos)throws IOException {
        Path path=path(identity,pos);if(!Files.isRegularFile(path))return Optional.empty();
        try{
            if(Files.size(path)>TileCodec.MAX_RAW+45)throw new IOException("Oversized cache entry");
            try(var in=new DataInputStream(Files.newInputStream(path))){
                if(in.readInt()!=0x4d4d4301)throw new IOException("Unknown cache format");
                byte[] hash=in.readNBytes(32);int raw=in.readInt();boolean compressed=in.readBoolean();int n=in.readInt();
                if(n<1||n>TileCodec.MAX_RAW)throw new IOException("Invalid cache size");
                byte[] body=in.readNBytes(n);if(body.length!=n||in.read()!=-1)throw new IOException("Truncated cache entry");
                var encoded=new TileCodec.Encoded(hash,raw,compressed,body);var tile=TileCodec.decode(encoded);
                Files.setLastModifiedTime(path,java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis()));
                return Optional.of(new Entry(encoded,tile));
            }
        }catch(IOException|RuntimeException corrupt){Files.deleteIfExists(path);return Optional.empty();}
    }
    public void write(CacheIdentity identity,TilePos pos,TileCodec.Encoded encoded)throws IOException {
        Path path=path(identity,pos);Files.createDirectories(path.getParent());Path temp=Files.createTempFile(path.getParent(),"tile-",".tmp");
        try{
            try(var out=new DataOutputStream(Files.newOutputStream(temp))){out.writeInt(0x4d4d4301);out.write(encoded.hash());out.writeInt(encoded.rawLength());out.writeBoolean(encoded.compressed());out.writeInt(encoded.body().length);out.write(encoded.body());}
            try{Files.move(temp,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}
            catch(AtomicMoveNotSupportedException e){Files.move(temp,path,StandardCopyOption.REPLACE_EXISTING);}
        }finally{Files.deleteIfExists(temp);}
        if(++writes%128==1)prune();
    }
    public void delete(CacheIdentity identity,TilePos pos)throws IOException{Files.deleteIfExists(path(identity,pos));}
    public void prune()throws IOException {
        if(!Files.isDirectory(root))return;
        record FileInfo(Path path,long size,long time){}
        var files=new ArrayList<FileInfo>();long bytes=0;
        try(var walk=Files.walk(root)){for(Path path:walk.filter(p->p.toString().endsWith(".tile")).toList()){
            var a=Files.readAttributes(path,java.nio.file.attribute.BasicFileAttributes.class);files.add(new FileInfo(path,a.size(),a.lastModifiedTime().toMillis()));bytes+=a.size();
        }}
        files.sort(Comparator.comparingLong(FileInfo::time));
        for(var file:files){if(bytes<=maxBytes)break;Files.deleteIfExists(file.path);bytes-=file.size;}
    }
}
