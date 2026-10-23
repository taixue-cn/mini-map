package io.github.minimap.server;

import io.github.minimap.protocol.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class TransportTest {
    @Test void fragmentationStaysBelowPaperLimit()throws Exception {
        var data=new TileCodec.Encoded(new byte[32],65536,false,new byte[65536]);
        var parts=TileParts.split(UUID.randomUUID(),1,new TilePos(-1,5),data);assertEquals(4,parts.size());
        for(var part:parts){assertTrue(Wire.encode(part).length<Wire.MAX_FRAME);assertInstanceOf(Wire.Part.class,Wire.decode(Wire.encode(part)));}
    }
    @Test void bandwidthIsBoundedAndRefillsByElapsedTime(){
        var bucket=new TokenBucket(100,100,0);assertTrue(bucket.take(100,0));assertFalse(bucket.take(1,0));assertTrue(bucket.take(50,500_000_000));assertFalse(bucket.take(1,500_000_000));assertTrue(bucket.take(100,10_000_000_000L));assertFalse(bucket.take(-1,10_000_000_000L));
        assertThrows(IllegalArgumentException.class,()->new TokenBucket(Double.NaN,100,0));
    }
}
