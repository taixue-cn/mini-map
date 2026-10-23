package io.github.minimap.server;

import io.github.minimap.protocol.*;
import java.util.*;

public final class TileParts {
    private TileParts(){}
    public static List<Wire.Part> split(UUID session,int request,TilePos pos,TileCodec.Encoded tile){
        int count=(tile.body().length+Wire.FRAGMENT-1)/Wire.FRAGMENT;
        var parts=new ArrayList<Wire.Part>(count);
        for(int i=0;i<count;i++)parts.add(new Wire.Part(session,request,pos,tile.hash(),tile.rawLength(),tile.compressed(),tile.body().length,i,count,Arrays.copyOfRange(tile.body(),i*Wire.FRAGMENT,Math.min(tile.body().length,(i+1)*Wire.FRAGMENT))));
        return parts;
    }
}
