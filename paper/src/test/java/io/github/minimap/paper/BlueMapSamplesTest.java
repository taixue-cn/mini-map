package io.github.minimap.paper;

import io.github.minimap.api.MapProvider;
import org.junit.jupiter.api.Test;
import java.awt.image.BufferedImage;
import java.io.*;
import java.util.*;
import javax.imageio.ImageIO;
import static org.junit.jupiter.api.Assertions.*;

class BlueMapSamplesTest {
    // BlueMap's standard storage stream exposes decompression independently of its backend.
    public static final class Blob extends ByteArrayInputStream {
        Blob(byte[] bytes){super(bytes);}
        public InputStream decompress(){return this;}
    }
    public static final class Storage {
        final Map<Long,byte[]> images=new HashMap<>();
        public Storage lowresTiles(int level){assertEquals(1,level);return this;}
        public Blob read(int x,int z){var bytes=images.get(((long)x<<32)|(z&0xffffffffL));return bytes==null?null:new Blob(bytes);}
        void tile(int x,int z,int height,int color)throws IOException {
            var image=new BufferedImage(501,1002,BufferedImage.TYPE_INT_ARGB);
            for(int bz=0;bz<500;bz++)for(int bx=0;bx<500;bx++){image.setRGB(bx,bz,color);image.setRGB(bx,bz+501,0xff0c0000|(height&65535));}
            var buffer=new ByteArrayOutputStream();ImageIO.write(image,"png",buffer);images.put(((long)x<<32)|(z&0xffffffffL),buffer.toByteArray());
        }
    }
    @SuppressWarnings("unchecked")
    BlueMapProvider provider(Storage storage)throws Exception {
        var provider=new BlueMapProvider(null);var field=BlueMapProvider.class.getDeclaredField("bindings");field.setAccessible(true);
        var binding=Class.forName(BlueMapProvider.class.getName()+"$Binding").getDeclaredConstructors()[0];binding.setAccessible(true);
        ((Map<String,Object>)field.get(provider)).put("map",binding.newInstance(storage,500,500));return provider;
    }
    @Test void readsAcrossLowresBoundariesWithoutChangingHeightOrLight()throws Exception {
        var storage=new Storage();storage.tile(0,0,-64,0xff112233);storage.tile(1,0,200,0xff445566);
        var data=provider(storage).read(new MapProvider.MapRef("map","1"),7,0).orElseThrow();
        assertEquals(-64,data.height()[51]);assertEquals(200,data.height()[52]);assertEquals(12,data.blockLight()[51]);
        assertEquals(0xff112233,data.argb()[51]);assertEquals(0xff445566,data.argb()[52]);
    }
    @Test void negativeCoordinatesAndAbsentStorageStayIsolated()throws Exception {
        var storage=new Storage();storage.tile(-1,-1,-63,0xff123456);var provider=provider(storage);
        var data=provider.read(new MapProvider.MapRef("map","1"),-1,-1).orElseThrow();assertEquals(-63,data.height()[0]);assertEquals(0xff123456,data.argb()[4095]);
        assertTrue(provider.read(new MapProvider.MapRef("map","1"),0,0).isEmpty());
    }
}
