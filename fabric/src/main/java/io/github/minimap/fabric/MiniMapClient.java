package io.github.minimap.fabric;

import io.github.minimap.client.*;
import io.github.minimap.protocol.*;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.*;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import org.slf4j.*;
import java.io.IOException;
import java.util.*;

public final class MiniMapClient implements ClientModInitializer {
    private static final Logger LOG=LoggerFactory.getLogger("mini-map-client");
    private static volatile CacheIdentity identity;
    private static volatile ResourceKey<Level> dimension;
    private ClientLevel world;
    private MapSync sync;
    private Bridge bridge;
    private long helloAt,errorAt;
    public interface Bridge extends MapSync.Display {
        boolean open();List<TilePos> visible();
    }
    public static CacheIdentity identity(){return identity;}
    public static boolean isCurrentDimension(ResourceKey<Level> key){return identity!=null&&key==dimension;}
    @Override public void onInitializeClient(){
        PayloadTypeRegistry.serverboundPlay().register(MapFrame.TYPE,MapFrame.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(MapFrame.TYPE,MapFrame.CODEC);
        if(!XaeroMixinPlugin.supported()){
            LOG.info("MiniMap sync inactive: supported Xaero World Map 1.47.1 is not installed.");return;
        }
        bridge=new XaeroBridge();
        sync=new MapSync(this::send,Minecraft.getInstance()::execute,this::error,bridge,
            new TileCache(FabricLoader.getInstance().getGameDir().resolve("mini-map-cache"),512L*1024*1024));
        ClientPlayNetworking.registerGlobalReceiver(MapFrame.TYPE,(frame,context)->{
            try{Wire.Message message=Wire.decode(frame.bytes());context.client().execute(()->receive(message));}catch(IOException e){error(e);}
        });
        ClientPlayConnectionEvents.JOIN.register((handler,sender,client)->reset(client));
        ClientPlayConnectionEvents.DISCONNECT.register((handler,client)->reset(client));
        ClientTickEvents.END_CLIENT_TICK.register(client->{
            if(client.level!=world)reset(client);
            if(client.level==null||client.getConnection()==null)return;
            long now=System.currentTimeMillis();
            if(sync.offer()==null){
                if(now-helloAt>=3000&&ClientPlayNetworking.canSend(MapFrame.TYPE)){helloAt=now;send(new Wire.Hello(Wire.VERSION));}return;
            }
            boolean open=bridge.open();sync.view(open,open?bridge.visible():List.of());sync.tick(now);
        });
    }
    private void reset(Minecraft client){identity=null;dimension=null;sync.reset();world=client.level;helloAt=0;}
    private void receive(Wire.Message message){
        Minecraft client=Minecraft.getInstance();if(client.level!=world)reset(client);
        if(message instanceof Wire.Offer offer){
            if(client.level==null||!offer.dimension().equals(client.level.dimension().identifier().toString()))return;
            try{CacheIdentity key=CacheIdentity.of(offer);key.key();sync.offer(offer);dimension=client.level.dimension();identity=key;
                LOG.info("MiniMap session established for current world.");
            }catch(IllegalArgumentException e){error(e);}
        }else{
            sync.receive(message,System.currentTimeMillis());
            if(sync.offer()==null){identity=null;dimension=null;}
        }
    }
    private void send(Wire.Message message){
        if(!ClientPlayNetworking.canSend(MapFrame.TYPE))return;
        try{ClientPlayNetworking.send(new MapFrame(Wire.encode(message)));}catch(IOException|IllegalArgumentException e){error(e);}
    }
    private synchronized void error(Exception e){long now=System.currentTimeMillis();if(now-errorAt>10000){errorAt=now;LOG.warn("MiniMap request/cache failed; it will be retried: {}",e.toString());}}
}
