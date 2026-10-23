package io.github.minimap.protocol;

import org.junit.jupiter.api.Test;
import java.io.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class ProtocolTest {
    @Test void samplesSurviveCompressionIncludingNegativeHeights()throws Exception {
        int[] colors=new int[4096],heights=new int[4096];byte[] light=new byte[4096];
        for(int i=0;i<4096;i++){colors[i]=0xffa0b0c0;heights[i]=i%65536-2048;light[i]=(byte)(i%16);}
        var source=new TileData(colors,heights,light);var encoded=TileCodec.encode(source);var decoded=TileCodec.decode(encoded);
        assertTrue(encoded.compressed());assertTrue(encoded.body().length<encoded.rawLength()/2);
        for(int i=0;i<4096;i++){assertEquals(source.color(i),decoded.color(i));assertEquals(source.height(i),decoded.height(i));assertEquals(source.light(i),decoded.light(i));}
        encoded.body()[3]^=1;assertThrows(IOException.class,()->TileCodec.decode(encoded));
    }
    @Test void randomSamplesFitWireAndRawFallback()throws Exception {
        var random=new Random(1);int[] colors=new int[4096],heights=new int[4096];byte[] light=new byte[4096];
        for(int i=0;i<4096;i++){colors[i]=random.nextInt()|0xff000000;heights[i]=random.nextInt(65536)-32768;light[i]=(byte)random.nextInt(16);}
        var encoded=TileCodec.encode(new TileData(colors,heights,light));assertTrue(encoded.body().length<=TileCodec.MAX_RAW);
        var decoded=TileCodec.decode(encoded);for(int i=0;i<4096;i++)assertEquals(heights[i],decoded.height(i));
        assertThrows(IOException.class,()->TileCodec.decode(new TileCodec.Encoded(encoded.hash(),Integer.MAX_VALUE,true,new byte[0])));
        assertThrows(IOException.class,()->TileCodec.decode(new TileCodec.Encoded(encoded.hash(),encoded.rawLength(),encoded.compressed(),Arrays.copyOf(encoded.body(),encoded.body().length+1))));
    }
    @Test void transparentSamplesHaveCanonicalHash()throws Exception {
        int[] colors=new int[4096],heights=new int[4096];Arrays.fill(colors,0x112233);Arrays.fill(heights,40000);
        var a=TileCodec.encode(new TileData(colors,heights,new byte[4096]));var b=TileCodec.encode(new TileData(new int[4096],new int[4096],new byte[4096]));
        assertArrayEquals(a.hash(),b.hash());
    }
    @Test void messagesRejectTruncationTrailingFieldsAndInvalidPositions()throws Exception {
        var message=new Wire.Offer(UUID.randomUUID(),"server","world","2","minecraft:overworld");byte[] encoded=Wire.encode(message);
        assertEquals(message,Wire.decode(encoded));
        for(int i=0;i<encoded.length;i++){byte[] truncated=Arrays.copyOf(encoded,i);assertThrows(IOException.class,()->Wire.decode(truncated));}
        assertThrows(IOException.class,()->Wire.decode(Arrays.copyOf(encoded,encoded.length+1)));
        assertThrows(IOException.class,()->Wire.decode(new byte[Wire.MAX_FRAME+1]));
        assertThrows(IllegalArgumentException.class,()->new TilePos(Integer.MAX_VALUE,0));
    }
}
