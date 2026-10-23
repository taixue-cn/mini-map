package io.github.minimap.fabric;

import io.github.minimap.protocol.*;
import io.github.minimap.fabric.mixin.GuiMapAccess;
import net.minecraft.client.Minecraft;
import xaero.map.*;
import xaero.map.gui.GuiMap;
import xaero.map.region.*;
import java.util.*;

final class XaeroBridge implements MiniMapClient.Bridge {
    private final Minecraft client=Minecraft.getInstance();
    private MapProcessor processor(){var session=WorldMapSession.getCurrentSession();return session==null?null:session.getMapProcessor();}
    private boolean current(MapProcessor processor){
        var id=MiniMapClient.identity();return id!=null&&processor!=null&&processor.isMapWorldUsable()&&processor.getCurrentCaveLayer()==Integer.MAX_VALUE
            &&id.serverFolder().equals(processor.getCurrentWorldId())&&id.worldFolder().equals(processor.getCurrentMWId())
            &&MiniMapClient.isCurrentDimension(processor.getMapWorld().getCurrentDimensionId());
    }
    @Override public boolean open(){
        return client.gui.screen() instanceof GuiMap map&&current(map.getMapProcessor())
            &&map.getDimensionOnInit()==map.getMapProcessor().getMapWorld().getCurrentDimension();
    }
    @Override public List<TilePos> visible(){
        if(!(client.gui.screen() instanceof GuiMap map)||!open())return List.of();
        var view=(GuiMapAccess)map;double scale=view.minimap$scale();if(!Double.isFinite(scale)||scale<=0)return List.of();
        double halfX=client.getWindow().getWidth()/scale/2,halfZ=client.getWindow().getHeight()/scale/2;
        int centerX=(int)Math.floor(view.minimap$cameraX()/64),centerZ=(int)Math.floor(view.minimap$cameraZ()/64);
        // Bound detailed tiles when zoomed out; a future protocol LOD can extend the preview beyond this radius.
        int minX=Math.max(centerX-16,(int)Math.floor((view.minimap$cameraX()-halfX)/64));
        int maxX=Math.min(centerX+15,(int)Math.floor((view.minimap$cameraX()+halfX)/64));
        int minZ=Math.max(centerZ-16,(int)Math.floor((view.minimap$cameraZ()-halfZ)/64));
        int maxZ=Math.min(centerZ+15,(int)Math.floor((view.minimap$cameraZ()+halfZ)/64));
        var result=new ArrayList<TilePos>();
        for(int z=minZ;z<=maxZ;z++)for(int x=minX;x<=maxX;x++)try{result.add(new TilePos(x,z));}catch(IllegalArgumentException ignored){}
        result.sort(Comparator.comparingLong(p->(long)(p.x()-centerX)*(p.x()-centerX)+(long)(p.z()-centerZ)*(p.z()-centerZ)));return result;
    }
    @Override public void tile(TilePos pos,TileData data){
        MapProcessor processor=processor();if(!current(processor))return;
        synchronized(processor){
            MapRegion region=processor.getLeafMapRegion(Integer.MAX_VALUE,pos.x()>>3,pos.z()>>3,true);if(region==null||region.getLoadState()==1)return;
            synchronized(region.writerThreadPauseSync){
                if(region.getLoadState()==0){
                    if(Boolean.TRUE.equals(region.getSaveExists())){processor.getMapSaveLoad().requestLoad(region,"MiniMap surface");return;}
                    region.setLoadState((byte)2);
                }
                MapTileChunk chunk=region.getChunk(pos.x()&7,pos.z()&7);
                if(chunk!=null){var first=chunk.getTile(0,0);if(first!=null&&first.getBlock(0,0) instanceof ServerMapBlock b&&b.source==data)return;}
                if(chunk==null){chunk=new MapTileChunk(region,pos.x(),pos.z());region.setChunk(pos.x()&7,pos.z()&7,chunk);}
                region.pushWriterPause();
                try{
                    for(int tz=0;tz<4;tz++)for(int tx=0;tx<4;tx++){
                        var tile=new MapTile(null,pos.x()*4+tx,pos.z()*4+tz);tile.setLoaded(true);tile.setWrittenOnce(true);tile.setWorldInterpretationVersion(MapTile.CURRENT_WORLD_INTERPRETATION_VERSION);
                        for(int z=0;z<16;z++)for(int x=0;x<16;x++)tile.setBlock(x,z,new ServerMapBlock(data,(tz*16+z)*64+tx*16+x));
                        chunk.setTile(tx,tz,tile,processor.getBlockStateShortShapeCache(),processor);
                    }
                    chunk.setLoadState((byte)2);chunk.setHasHadTerrain();chunk.setChanged(true);chunk.setToUpdateBuffers(true);
                    // The original samples live in our portable cache, never as fake block states in Xaero region files.
                    chunk.unincludeInSave();region.setHasHadTerrain();region.requestRefresh(processor);processor.addToProcess(region);
                }finally{region.popWriterPause();}
            }
        }
    }
    @Override public void missing(TilePos pos){
        MapProcessor processor=processor();if(!current(processor))return;
        var region=processor.getLeafMapRegion(Integer.MAX_VALUE,pos.x()>>3,pos.z()>>3,false);if(region==null)return;
        synchronized(region.writerThreadPauseSync){
            var chunk=region.getChunk(pos.x()&7,pos.z()&7);if(chunk==null)return;
            var first=chunk.getTile(0,0);if(first==null||!(first.getBlock(0,0) instanceof ServerMapBlock))return;
            for(int z=0;z<4;z++)for(int x=0;x<4;x++)chunk.setTile(x,z,null,processor.getBlockStateShortShapeCache(),processor);
            chunk.setToUpdateBuffers(true);chunk.unincludeInSave();region.requestRefresh(processor);processor.addToProcess(region);
        }
    }
}
