package io.github.minimap.paper;

import io.github.minimap.api.MapProvider;
import io.github.minimap.protocol.*;
import io.github.minimap.server.*;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.*;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;
import java.io.*;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

public final class MiniMapServer extends JavaPlugin implements Listener,PluginMessageListener {
    private record Binding(MapProvider provider,MapProvider.MapRef map,UUID world,String worldId,String epoch,String dimension){}
    private static final class Session {
        final UUID id=UUID.randomUUID(); final Binding binding;
        final TokenBucket bytes,requests;
        final Map<Integer,Long> pending=new HashMap<>();final ArrayDeque<Outbound> outbound=new ArrayDeque<>();
        boolean open;int generation;
        Session(Binding binding,double rate){this.binding=binding;long now=System.nanoTime();bytes=new TokenBucket(rate,Math.max(rate,Wire.MAX_FRAME),now);requests=new TokenBucket(8,8,now);}
    }
    private record Outbound(byte[] bytes,int generation){}
    private record Cached(long expires,Optional<TileCodec.Encoded> data){}
    private record CacheKey(MapProvider provider,MapProvider.MapRef map,TilePos pos){}
    private final Map<UUID,Session> sessions=new HashMap<>();
    private final Map<UUID,Long> helloAt=new HashMap<>();
    private final ConcurrentMap<CacheKey,CompletableFuture<Cached>> cache=new ConcurrentHashMap<>();
    private ThreadPoolExecutor workers;private BlueMapProvider blueMap;private TokenBucket total;
    private String serverId;private boolean stopping;private int ticks;

    @Override public void onEnable(){
        saveDefaultConfig();serverId=getConfig().getString("server-id","");
        if(serverId.isBlank()){serverId=UUID.randomUUID().toString();getConfig().set("server-id",serverId);saveConfig();}
        if(serverId.getBytes(java.nio.charset.StandardCharsets.UTF_8).length>256)throw new IllegalArgumentException("server-id too long");
        int threads=Math.clamp(getConfig().getInt("worker-threads",2),1,8);
        workers=new ThreadPoolExecutor(threads,threads,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(256),r->{Thread t=new Thread(r,"minimap-storage");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
        blueMap=new BlueMapProvider(this);
        double rate=Math.max(Wire.MAX_FRAME,getConfig().getDouble("total-bytes-per-second",4194304));total=new TokenBucket(rate,rate,System.nanoTime());
        getServer().getMessenger().registerIncomingPluginChannel(this,Wire.CHANNEL,this);
        getServer().getMessenger().registerOutgoingPluginChannel(this,Wire.CHANNEL);
        getServer().getPluginManager().registerEvents(this,this);
        getServer().getScheduler().runTaskTimer(this,this::tick,1,1);
        getLogger().info("MiniMap Server enabled; world-scoped optional protocol v"+Wire.VERSION);
    }
    @Override public void onDisable(){stopping=true;sessions.clear();helloAt.clear();cache.clear();if(workers!=null)workers.shutdownNow();}
    @EventHandler public void leave(PlayerQuitEvent e){sessions.remove(e.getPlayer().getUniqueId());helloAt.remove(e.getPlayer().getUniqueId());}
    @EventHandler public void change(PlayerChangedWorldEvent e){revoke(e.getPlayer());}
    private void revoke(Player player){Session previous=sessions.remove(player.getUniqueId());if(previous!=null)send(player,new Wire.Revoke(previous.id));}

    private boolean permitted(Player player){String p="worlds."+player.getWorld().getName();String permission=getConfig().getString(p+".permission","");return player.hasPermission("minimap.use")&&getConfig().getBoolean(p+".enabled",true)&&(permission.isBlank()||player.hasPermission(permission));}
    private Optional<Binding> resolve(Player player){
        if(!permitted(player))return Optional.empty();World world=player.getWorld();
        var reference=new MapProvider.WorldRef(world.getUID(),world.getName(),world.getKey().toString(),world.getWorldFolder().toPath());
        var providers=new ArrayList<>(getServer().getServicesManager().getRegistrations(MapProvider.class));
        providers.sort(Comparator.<RegisteredServiceProvider<MapProvider>,Integer>comparing(p->p.getPriority().ordinal()).reversed());
        for(var registration:providers){var found=bind(registration.getProvider(),reference);if(found.isPresent())return found;}
        return bind(blueMap,reference);
    }
    private Optional<Binding> bind(MapProvider provider,MapProvider.WorldRef world){
        try{return provider.resolve(world).map(map->{String path="worlds."+world.name();return new Binding(provider,map,world.uid(),getConfig().getString(path+".id",world.uid().toString()),getConfig().getString(path+".epoch",map.epoch()),world.dimension());});}
        catch(RuntimeException e){getLogger().warning("MapProvider failed to resolve current world: "+e);return Optional.empty();}
    }
    private boolean current(Player player,Session s){return sessions.get(player.getUniqueId())==s&&player.isOnline()&&s.binding.world.equals(player.getWorld().getUID())&&permitted(player);}
    @Override public void onPluginMessageReceived(String channel,Player player,byte[] data){
        if(!Wire.CHANNEL.equals(channel))return;
        try{
            Wire.Message message=Wire.decode(data);
            if(message instanceof Wire.Hello hello){
                long now=System.nanoTime(),last=helloAt.getOrDefault(player.getUniqueId(),0L);if(now-last<1_000_000_000L)return;
                helloAt.put(player.getUniqueId(),now);
                if(hello.version()!=Wire.VERSION)return;
                revoke(player);var binding=resolve(player);if(binding.isEmpty())return;
                var session=new Session(binding.get(),Math.max(Wire.MAX_FRAME,getConfig().getDouble("player-bytes-per-second",131072)));
                sessions.put(player.getUniqueId(),session);var b=binding.get();
                send(player,new Wire.Offer(session.id,serverId,b.worldId,b.epoch,b.dimension));return;
            }
            Session s=sessions.get(player.getUniqueId());if(s==null)return;
            if(!current(player,s)){revoke(player);return;}
            if(message instanceof Wire.Open open&&open.session().equals(s.id)){
                if(s.open!=open.open()){s.open=open.open();s.generation++;s.pending.clear();s.outbound.clear();}return;
            }
            if(message instanceof Wire.Ack ack&&ack.session().equals(s.id)){s.pending.remove(ack.requestId());return;}
            if(message instanceof Wire.Request request&&request.session().equals(s.id)&&s.open){
                if(request.knownHash().length!=0&&request.knownHash().length!=32)return;
                if(s.pending.size()>=4||s.pending.containsKey(request.requestId())||!s.requests.take(1,System.nanoTime()))return;
                s.pending.put(request.requestId(),System.nanoTime());int generation=s.generation;
                CacheKey key=new CacheKey(s.binding.provider,s.binding.map,request.pos());
                CompletableFuture<Cached> future=load(key);
                future.whenComplete((result,error)->{
                    if(stopping)return;
                    getServer().getScheduler().runTask(this,()->{
                        if(!current(player,s)||!s.open||s.generation!=generation||!s.pending.containsKey(request.requestId()))return;
                        if(error!=null){enqueue(s,new Wire.Status(s.id,request.requestId(),request.pos(),Wire.Result.RETRY));return;}
                        if(result.data.isEmpty()){enqueue(s,new Wire.Status(s.id,request.requestId(),request.pos(),Wire.Result.MISSING));return;}
                        var tile=result.data.get();
                        if(MessageDigest.isEqual(tile.hash(),request.knownHash()))enqueue(s,new Wire.Status(s.id,request.requestId(),request.pos(),Wire.Result.UNCHANGED));
                        else for(var part:TileParts.split(s.id,request.requestId(),request.pos(),tile))enqueue(s,part);
                    });
                });
            }
        }catch(IOException|IllegalArgumentException e){getLogger().fine("Rejected malformed MiniMap message: "+e.getMessage());}
    }
    private CompletableFuture<Cached> load(CacheKey key){
        long now=System.nanoTime();
        return cache.compute(key,(k,old)->{
            if(old!=null&&!old.isCompletedExceptionally()&&(!old.isDone()||old.getNow(null).expires>now))return old;
            try{return CompletableFuture.supplyAsync(()->{
                try{
                    var data=k.provider.read(k.map,k.pos.x(),k.pos.z());
                    Optional<TileCodec.Encoded> encoded=Optional.empty();
                    if(data.isPresent()){var t=data.get();var tile=new TileData(t.argb(),t.height(),t.blockLight());if(!tile.empty())encoded=Optional.of(TileCodec.encode(tile));}
                    return new Cached(System.nanoTime()+2_000_000_000L,encoded);
                }catch(IOException e){throw new CompletionException(e);}
            },workers);}catch(RejectedExecutionException e){return CompletableFuture.failedFuture(e);}
        });
    }
    private void enqueue(Session session,Wire.Message message){try{if(session.outbound.size()<32)session.outbound.add(new Outbound(Wire.encode(message),session.generation));}catch(IOException e){getLogger().warning("Cannot encode MiniMap response: "+e);}}
    private void send(Player player,Wire.Message message){try{player.sendPluginMessage(this,Wire.CHANNEL,Wire.encode(message));}catch(IOException|IllegalArgumentException e){getLogger().fine("MiniMap send failed: "+e);}}
    private void tick(){
        long now=System.nanoTime();
        // Rotate the starting player so a busy sender cannot starve the remaining players.
        var players=new ArrayList<>(getServer().getOnlinePlayers());ticks++;if(!players.isEmpty())Collections.rotate(players,ticks%players.size());
        for(Player player:players){
            Session s=sessions.get(player.getUniqueId());if(s==null)continue;
            if(!current(player,s)){revoke(player);continue;}
            s.pending.entrySet().removeIf(e->now-e.getValue()>15_000_000_000L);
            for(int sent=0;sent<4&&!s.outbound.isEmpty();sent++){
                var next=s.outbound.peek();if(next.generation!=s.generation){s.outbound.remove();continue;}
                int size=next.bytes.length;if(!s.bytes.available(size,now)||!total.available(size,now))break;s.bytes.take(size,now);total.take(size,now);
                s.outbound.remove();player.sendPluginMessage(this,Wire.CHANNEL,next.bytes);
            }
        }
        if(ticks%200==0&&cache.size()>128)cache.entrySet().removeIf(e->e.getValue().isDone()&&(e.getValue().isCompletedExceptionally()||e.getValue().getNow(null).expires<now));
    }
}
