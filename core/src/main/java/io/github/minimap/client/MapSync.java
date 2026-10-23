package io.github.minimap.client;

import io.github.minimap.protocol.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;

/** All session state runs on the client thread; storage and decompression run on one bounded worker. */
public final class MapSync implements AutoCloseable {
    public interface Display { void tile(TilePos pos,TileData data); void missing(TilePos pos); }
    private static final class Pending {
        final TilePos pos;final long start;final TileAssembly assembly=new TileAssembly();boolean decoding;
        Pending(TilePos pos,long start){this.pos=pos;this.start=start;}
    }
    private final Consumer<Wire.Message> sender;
    private final Consumer<Runnable> clientThread;
    private final Consumer<Exception> errors;
    private final Display display;
    private final TileCache cache;
    private final ThreadPoolExecutor worker;
    private final Map<Integer,Pending> pending=new HashMap<>();
    private final Map<TilePos,TileData> loaded=new LinkedHashMap<>();
    private final Map<TilePos,Long> checked=new HashMap<>();
    private List<TilePos> visible=List.of();
    private Wire.Offer offer;
    private boolean open;
    private int sequence,renderCursor;
    private long nextRequest;
    public MapSync(Consumer<Wire.Message> sender,Consumer<Runnable> clientThread,Consumer<Exception> errors,Display display,TileCache cache){
        this.sender=sender;this.clientThread=clientThread;this.errors=errors;this.display=display;this.cache=cache;
        worker=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(16),r->{Thread t=new Thread(r,"minimap-cache");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    }
    public Wire.Offer offer(){return offer;}
    public void offer(Wire.Offer value){reset();CacheIdentity.of(value).key();offer=value;}
    public void reset(){offer=null;open=false;visible=List.of();pending.clear();loaded.clear();checked.clear();nextRequest=0;}
    public void view(boolean isOpen,List<TilePos> positions){
        if(offer==null)return;
        if(open!=isOpen){open=isOpen;sender.accept(new Wire.Open(offer.session(),open));pending.clear();checked.clear();}
        if(!open){visible=List.of();loaded.clear();return;}
        visible=List.copyOf(positions.subList(0,Math.min(1024,positions.size())));
        var keep=new HashSet<>(visible);loaded.keySet().retainAll(keep);checked.keySet().retainAll(keep);
    }
    public void tick(long now){
        if(!open||offer==null)return;
        var expired=pending.entrySet().stream().filter(e->now-e.getValue().start>20000).map(Map.Entry::getKey).toList();
        for(int id:expired){sender.accept(new Wire.Ack(offer.session(),id));pending.remove(id);}
        if(!loaded.isEmpty()){
            var entries=new ArrayList<>(loaded.entrySet());var entry=entries.get(Math.floorMod(renderCursor++,entries.size()));display.tile(entry.getKey(),entry.getValue());
        }
        if(now<nextRequest||pending.size()>=4)return;
        for(TilePos pos:visible){
            if(now-checked.getOrDefault(pos,Long.MIN_VALUE/2)<10000||pending.values().stream().anyMatch(p->p.pos.equals(pos)))continue;
            int id=++sequence;Wire.Offer session=offer;var request=new Pending(pos,now);pending.put(id,request);nextRequest=now+150;
            submit(()->{
                Optional<TileCache.Entry> entry=cache.read(CacheIdentity.of(session),pos);
                clientThread.accept(()->{
                    if(!active(session,id,request))return;
                    entry.ifPresent(e->{loaded.put(pos,e.tile());display.tile(pos,e.tile());});
                    sender.accept(new Wire.Request(session.session(),id,pos,entry.map(e->e.encoded().hash()).orElseGet(()->new byte[0])));
                });
            },()->{if(active(session,id,request))sender.accept(new Wire.Request(session.session(),id,pos,new byte[0]));});
            break;
        }
    }
    private boolean active(Wire.Offer session,int id,Pending request){return open&&offer==session&&pending.get(id)==request;}
    public void receive(Wire.Message message,long now){
        if(offer==null)return;
        if(message instanceof Wire.Revoke revoke&&revoke.session().equals(offer.session())){reset();return;}
        if(message instanceof Wire.Status status&&status.session().equals(offer.session())){
            Pending request=pending.get(status.requestId());if(request==null||!request.pos.equals(status.pos()))return;
            Wire.Offer session=offer;pending.remove(status.requestId());sender.accept(new Wire.Ack(session.session(),status.requestId()));
            checked.put(status.pos(),status.result()==Wire.Result.RETRY?now-9000:now);
            if(status.result()==Wire.Result.MISSING){loaded.remove(status.pos());display.missing(status.pos());submit(()->cache.delete(CacheIdentity.of(session),status.pos()),()->{});}
        }else if(message instanceof Wire.Part part&&part.session().equals(offer.session())){
            Pending request=pending.get(part.requestId());if(request==null||request.decoding||!request.pos.equals(part.pos()))return;
            try{
                var completed=request.assembly.add(part);if(completed.isEmpty())return;request.decoding=true;
                Wire.Offer session=offer;var encoded=completed.get();
                submit(()->{
                    TileData data=TileCodec.decode(encoded);
                    try{cache.write(CacheIdentity.of(session),part.pos(),encoded);}catch(IOException e){errors.accept(e);}
                    clientThread.accept(()->{
                        if(!active(session,part.requestId(),request))return;
                        pending.remove(part.requestId());sender.accept(new Wire.Ack(session.session(),part.requestId()));checked.put(part.pos(),System.currentTimeMillis());
                        if(visible.contains(part.pos())){loaded.put(part.pos(),data);display.tile(part.pos(),data);}
                    });
                },()->fail(part.requestId(),request,session));
            }catch(IOException e){errors.accept(e);fail(part.requestId(),request,offer);}
        }
    }
    private void fail(int id,Pending request,Wire.Offer session){if(active(session,id,request)){pending.remove(id);sender.accept(new Wire.Ack(session.session(),id));}}
    @FunctionalInterface private interface IOAction {void run()throws IOException;}
    private void submit(IOAction action,Runnable failed){
        try{worker.execute(()->{try{action.run();}catch(Exception e){errors.accept(e);clientThread.accept(failed);}});}
        catch(RejectedExecutionException e){errors.accept(e);failed.run();}
    }
    @Override public void close(){reset();worker.shutdownNow();}
}
