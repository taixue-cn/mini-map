package io.github.minimap.client;

import io.github.minimap.protocol.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class CacheTest {
    @TempDir Path root;
    @Test void cacheReusesIdentityAndRejectsCorruption()throws Exception {
        var cache=new TileCache(root,100000);var identity=new CacheIdentity("../server","a/b","1");var pos=new TilePos(-1,3);
        int[] colors=new int[4096];Arrays.fill(colors,0xff33ee11);var encoded=TileCodec.encode(new TileData(colors,new int[4096],new byte[4096]));
        cache.write(identity,pos,encoded);assertArrayEquals(encoded.hash(),cache.read(new CacheIdentity("../server","a/b","1"),pos).orElseThrow().encoded().hash());
        assertTrue(cache.read(new CacheIdentity("../server","a/b","2"),pos).isEmpty());
        try(var files=Files.walk(root)){Path file=files.filter(p->p.toString().endsWith(".tile")).findFirst().orElseThrow();Files.write(file,new byte[]{0,1});}
        assertTrue(cache.read(identity,pos).isEmpty());
        assertNotEquals(new CacheIdentity("a","bc","d").key(),new CacheIdentity("ab","c","d").key());
    }
    @Test void cacheEvictsOldestEntries()throws Exception {
        var cache=new TileCache(root,1);var id=new CacheIdentity("s","w","e");
        cache.write(id,new TilePos(0,0),TileCodec.encode(new TileData(new int[4096],new int[4096],new byte[4096])));
        assertTrue(cache.read(id,new TilePos(0,0)).isEmpty());
    }
    @Test void assemblerAcceptsReorderingAndRejectsMixedMetadata()throws Exception {
        UUID session=UUID.randomUUID();var pos=new TilePos(0,0);byte[] body=new byte[20000];new Random(5).nextBytes(body);
        var last=new Wire.Part(session,1,pos,new byte[32],30000,true,20000,1,2,Arrays.copyOfRange(body,16384,20000));
        var first=new Wire.Part(session,1,pos,new byte[32],30000,true,20000,0,2,Arrays.copyOfRange(body,0,16384));
        var assembler=new TileAssembly();assertTrue(assembler.add(last).isEmpty());assertTrue(assembler.add(last).isEmpty());assertArrayEquals(body,assembler.add(first).orElseThrow().body());
        var bad=new TileAssembly();bad.add(last);
        assertThrows(IOException.class,()->bad.add(new Wire.Part(session,1,pos,new byte[32],30001,true,20000,0,2,first.bytes())));
        assertThrows(IOException.class,()->new TileAssembly().add(new Wire.Part(session,1,pos,new byte[32],30000,true,Integer.MAX_VALUE,0,2,first.bytes())));
    }
}
