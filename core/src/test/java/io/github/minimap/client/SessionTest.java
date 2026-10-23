package io.github.minimap.client;

import io.github.minimap.protocol.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;

class SessionTest {
    @TempDir Path root;
    final List<Wire.Message> sent=new ArrayList<>();
    final List<TileData> displayed=new ArrayList<>();
    final List<TilePos> deleted=new ArrayList<>();
    final BlockingQueue<Runnable> callbacks=new LinkedBlockingQueue<>();
    final List<Exception> errors=new CopyOnWriteArrayList<>();
    MapSync sync(){return new MapSync(sent::add,callbacks::add,errors::add,new MapSync.Display(){public void tile(TilePos pos,TileData data){displayed.add(data);}public void missing(TilePos pos){deleted.add(pos);}},new TileCache(root,1000000));}
    Wire.Offer offer(String world){return new Wire.Offer(UUID.randomUUID(),"server",world,"1","minecraft:overworld");}
    void pump(BooleanSupplier condition)throws Exception {
        long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(3);
        while(!condition.getAsBoolean()&&System.nanoTime()<deadline){Runnable task=callbacks.poll(50,TimeUnit.MILLISECONDS);if(task!=null)task.run();}
        assertTrue(condition.getAsBoolean(),"async work did not complete");
    }
    Wire.Request request(){return sent.stream().filter(Wire.Request.class::isInstance).map(Wire.Request.class::cast).reduce((a,b)->b).orElseThrow();}
    TileCodec.Encoded encoded()throws Exception {int[] colors=new int[4096];Arrays.fill(colors,0xff125633);return TileCodec.encode(new TileData(colors,new int[4096],new byte[4096]));}
    Wire.Part part(Wire.Request request,TileCodec.Encoded data){return new Wire.Part(request.session(),request.requestId(),request.pos(),data.hash(),data.rawLength(),data.compressed(),data.body().length,0,1,data.body());}
    @Test void worldSwitchAndCloseRejectOutstandingResponses()throws Exception {
        try(var sync=sync()){
            var pos=new TilePos(0,0);sync.offer(offer("A"));sync.view(true,List.of(pos));sync.tick(System.currentTimeMillis());pump(()->sent.stream().anyMatch(Wire.Request.class::isInstance));
            Wire.Request old=request();sync.offer(offer("B"));sync.view(true,List.of(pos));sync.receive(part(old,encoded()),System.currentTimeMillis());
            assertTrue(displayed.isEmpty());assertTrue(deleted.isEmpty());
            sent.clear();sync.tick(System.currentTimeMillis());pump(()->sent.stream().anyMatch(Wire.Request.class::isInstance));var current=request();
            sync.view(false,List.of());sync.receive(part(current,encoded()),System.currentTimeMillis());assertTrue(displayed.isEmpty());
            assertTrue(errors.isEmpty());
        }
    }
    @Test void verifiedCacheGeneratesConditionalRequestAndMissingEvicts()throws Exception {
        var cache=new TileCache(root,1000000);var pos=new TilePos(0,0);var offer=offer("A");var data=encoded();
        cache.write(CacheIdentity.of(offer),pos,data);
        try(var sync=sync()){
            sync.offer(offer);sync.view(true,List.of(pos));sync.tick(System.currentTimeMillis());pump(()->sent.stream().anyMatch(Wire.Request.class::isInstance));
            var request=request();assertArrayEquals(data.hash(),request.knownHash());assertFalse(displayed.isEmpty());
            sync.receive(new Wire.Status(offer.session(),request.requestId(),pos,Wire.Result.MISSING),System.currentTimeMillis());assertEquals(List.of(pos),deleted);
            // The single worker preserves write/delete/read ordering across sessions.
            sync.view(false,List.of());sent.clear();sync.view(true,List.of(pos));sync.tick(System.currentTimeMillis()+1000);
            pump(()->sent.stream().anyMatch(Wire.Request.class::isInstance));assertEquals(0,request().knownHash().length);assertTrue(errors.isEmpty());
        }
    }
}
