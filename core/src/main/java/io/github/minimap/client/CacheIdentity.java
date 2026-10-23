package io.github.minimap.client;

import io.github.minimap.protocol.*;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

/** Length-delimited identities avoid ambiguous keys; paths contain hashes only. */
public record CacheIdentity(String server,String world,String epoch) {
    public static CacheIdentity of(Wire.Offer offer){return new CacheIdentity(offer.serverId(),offer.worldId(),offer.epoch());}
    public String serverFolder(){return "Multiplayer_minimap_"+digest(server);}
    public String worldFolder(){return "minimap_"+digest(world,epoch);}
    public String key(){return digest(server,world,epoch);}
    private static String digest(String... parts){
        StringBuilder text=new StringBuilder();
        for(String part:parts){if(part==null||part.isBlank())throw new IllegalArgumentException("Blank identity");text.append(part.length()).append(':').append(part);}
        return HexFormat.of().formatHex(TileCodec.hash(text.toString().getBytes(StandardCharsets.UTF_8)));
    }
}
