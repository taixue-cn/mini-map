package io.github.minimap.fixture;

import io.github.minimap.fabric.MiniMapClient;
import io.github.minimap.protocol.TileData;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.*;
import net.minecraft.client.multiplayer.*;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import xaero.map.WorldMapSession;
import xaero.map.gui.GuiMap;
import java.nio.file.*;

public final class ClientFixture implements ClientModInitializer {
    private long started;private boolean connecting,opened,done,initialSeen,changeSeen;private int ticks;
    @Override public void onInitializeClient(){
        started=System.currentTimeMillis();
        ClientTickEvents.END_CLIENT_TICK.register(client->{
            if(done||++ticks%20!=0)return;
            try{
                if(System.currentTimeMillis()-started>120000){finish(client,false,"timeout");return;}
                if(client.level==null){if(!connecting&&ticks>100){connecting=true;String address=System.getenv("MINIMAP_TEST_SERVER");ConnectScreen.startConnecting(new TitleScreen(),client,ServerAddress.parseString(address),new ServerData("isolated",address,ServerData.Type.OTHER),false,null);}return;}
                client.options.renderDistance().set(2);
                if(System.getenv("MINIMAP_TEST_MODE")!=null&&System.getenv("MINIMAP_TEST_MODE").equals("fallback")){
                    if(ticks>400)finish(client,MiniMapClient.identity()==null,"normal multiplayer without a MiniMap session");return;
                }
                if(MiniMapClient.identity()==null)return;
                var session=WorldMapSession.getCurrentSession();if(session==null)return;var processor=session.getMapProcessor();
                String debug="usable="+processor.isMapWorldUsable()+" root="+processor.getCurrentWorldId()+" mw="+processor.getCurrentMWId()+" expected="+MiniMapClient.identity().worldFolder()+" cave="+processor.getCurrentCaveLayer()+" dimension="+processor.getMapWorld().getCurrentDimensionId()+" screen="+client.gui.screen();
                if(client.gui.screen() instanceof GuiMap gui){for(String field:new String[]{"cameraX","cameraZ","scale"}){var f=GuiMap.class.getDeclaredField(field);f.setAccessible(true);debug+=" "+field+"="+f.get(gui);}debug+=" sameDimension="+(gui.getDimensionOnInit()==processor.getMapWorld().getCurrentDimension());}
                Files.writeString(Path.of(System.getenv("MINIMAP_TEST_REPORT")+".debug"),debug);
                if(!processor.isMapWorldUsable()||!MiniMapClient.identity().worldFolder().equals(processor.getCurrentMWId()))return;
                if(!opened){
                    var screen=new GuiMap(null,null,processor,client.player);client.gui.setScreen(screen);opened=true;
                    for(String field:new String[]{"cameraX","cameraZ"}){var f=GuiMap.class.getDeclaredField(field);f.setAccessible(true);f.setDouble(screen,672);}
                }
                if(client.gui.screen() instanceof GuiMap screen){
                    var attached=GuiMap.class.getDeclaredField("attachedCamera");attached.setAccessible(true);attached.setBoolean(null,false);
                    for(String field:new String[]{"cameraX","cameraZ"}){var f=GuiMap.class.getDeclaredField(field);f.setAccessible(true);f.setDouble(screen,672);}
                }
                var tile=processor.getMapTile(Integer.MAX_VALUE,40,40);
                var target=processor.getLeafMapRegion(Integer.MAX_VALUE,1,1,false);
                Files.writeString(Path.of(System.getenv("MINIMAP_TEST_REPORT")+".debug")," targetTile="+tile+" regionState="+(target==null?"null":target.getLoadState())+" targetBlock="+(tile==null?"null":tile.getBlock(0,0)),StandardOpenOption.APPEND);
                if(tile==null)return;var block=tile.getBlock(0,0);if(block==null||!block.getClass().getSimpleName().equals("ServerMapBlock"))return;
                var field=block.getClass().getDeclaredField("source");field.setAccessible(true);TileData data=(TileData)field.get(block);
                boolean updates="updates".equals(System.getenv("MINIMAP_TEST_MODE"));
                if(updates){
                    if(data.color(0)==0xffe04090&&data.height(0)==71&&data.light(0)==12)initialSeen=true;
                    else if(data.color(0)==0xff30b050&&data.height(0)==72&&data.light(0)==10)changeSeen=true;
                    else if(data.color(0)!=0)throw new AssertionError("Unexpected updated samples");
                }else if(data.color(0)!=0xffe04090||data.height(0)!=71||data.light(0)!=12)throw new AssertionError("Surface samples changed");
                if(client.level.getChunkSource().hasChunk(40,40))throw new AssertionError("Remote tile is inside vanilla loaded chunks");
                var chunk=processor.getMapChunk(Integer.MAX_VALUE,10,10);
                Files.writeString(Path.of(System.getenv("MINIMAP_TEST_REPORT")+".debug")," update="+chunk.getToUpdateBuffers()+" uploaded="+chunk.getLeafTexture().isUploaded()+" buffer="+(chunk.getLeafTexture().getDirectColorBuffer()!=null)+" cachePrepared="+target.isAllCachePrepared()+" processing="+processor.isProcessed(target)+" regionPaused="+target.isWritingPaused()+" writerPaused="+processor.isWritingPaused()+" uploadPaused="+processor.isUploadingPaused()+" chunkState="+chunk.getLoadState(),StandardOpenOption.APPEND);
                if(chunk.getToUpdateBuffers())return;
                var pixels=chunk.getLeafTexture().getDirectColorBuffer();if(pixels==null||!chunk.getLeafTexture().isUploaded())return;
                if(updates){
                    if(!initialSeen||!changeSeen||data.color(0)!=0||pixels.get(3)!=0)return;
                    finish(client,true,"Native Xaero received initial tile, incremental color/height/light update, then transparent deletion");return;
                }
                if((pixels.get(0)&255)!=0xe0||(pixels.get(1)&255)!=0x40||(pixels.get(2)&255)!=0x90)throw new AssertionError("Native texture color differs");
                finish(client,true,"Remote color, height and light imported into native Xaero tile outside loaded chunks; stable cache identity active");
            }catch(Throwable e){finish(client,false,e.toString());}
        });
    }
    private void finish(Minecraft client,boolean pass,String detail){
        done=true;
        try{Path path=Path.of(System.getenv("MINIMAP_TEST_REPORT"));Files.createDirectories(path.getParent());var json=new com.google.gson.JsonObject();json.addProperty("status",pass?"PASS":"FAIL");json.addProperty("detail",detail);Files.writeString(path,json.toString());}catch(Exception e){throw new RuntimeException(e);}
        client.stop();
    }
}
