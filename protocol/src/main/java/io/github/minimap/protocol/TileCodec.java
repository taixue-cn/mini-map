package io.github.minimap.protocol;

import java.io.*;
import java.security.*;
import java.util.*;
import java.util.zip.*;

/** The hash is over canonical samples, independent of transport compression. */
public final class TileCodec {
    public static final int MAX_RAW = 65536;
    public record Encoded(byte[] hash, int rawLength, boolean compressed, byte[] body) {}
    private TileCodec() {}
    public static Encoded encode(TileData tile) throws IOException {
        var bytes = new ByteArrayOutputStream(); var out = new DataOutputStream(bytes);
        out.writeByte(1);
        for (int i=0;i<TilePos.PIXELS;i++) out.writeInt(tile.color(i));
        int previous = 0;
        for (int i=0;i<TilePos.PIXELS;i++) {
            int delta = tile.height(i)-previous; previous=tile.height(i);
            int n=(delta<<1)^(delta>>31);
            do { int b=n&127; n>>>=7; out.writeByte(b|(n==0?0:128)); } while(n!=0);
        }
        for(int i=0;i<TilePos.PIXELS;i+=2) out.writeByte(tile.light(i)|(tile.light(i+1)<<4));
        byte[] raw=bytes.toByteArray();
        var packed=new ByteArrayOutputStream();
        var compressor=new Deflater(3);
        try(var deflated=new DeflaterOutputStream(packed,compressor)) { deflated.write(raw); }
        finally { compressor.end(); }
        boolean compressed=packed.size()+32 < raw.length;
        return new Encoded(hash(raw),raw.length,compressed,compressed?packed.toByteArray():raw);
    }
    public static TileData decode(Encoded encoded) throws IOException {
        if(encoded.hash.length!=32 || encoded.rawLength<1 || encoded.rawLength>MAX_RAW || encoded.body.length>MAX_RAW)
            throw new IOException("Invalid tile size");
        byte[] raw;
        if(encoded.compressed) {
            Inflater inflater=new Inflater();
            try {
                inflater.setInput(encoded.body); raw=new byte[encoded.rawLength]; int n=0;
                while(n<raw.length && !inflater.finished()) {
                    int read=inflater.inflate(raw,n,raw.length-n);
                    if(read==0) break; n+=read;
                }
                if(n!=raw.length || !inflater.finished() || inflater.getRemaining()!=0) throw new IOException("Invalid compressed tile");
            } catch(DataFormatException e) { throw new IOException("Corrupt tile",e); }
            finally { inflater.end(); }
        } else raw=encoded.body;
        if(raw.length!=encoded.rawLength || !MessageDigest.isEqual(hash(raw),encoded.hash)) throw new IOException("Tile hash mismatch");
        var in=new DataInputStream(new ByteArrayInputStream(raw));
        if(in.readUnsignedByte()!=1) throw new IOException("Unknown tile format");
        int[] colors=new int[TilePos.PIXELS], heights=new int[TilePos.PIXELS]; byte[] light=new byte[TilePos.PIXELS];
        for(int i=0;i<colors.length;i++) colors[i]=in.readInt();
        int previous=0;
        for(int i=0;i<heights.length;i++) {
            int n=0,shift=0,b;
            do { if(shift>=21) throw new IOException("Height delta too large"); b=in.readUnsignedByte(); n|=(b&127)<<shift; shift+=7; }while((b&128)!=0);
            previous+=(n>>>1)^-(n&1);
            if(previous<Short.MIN_VALUE||previous>Short.MAX_VALUE)throw new IOException("Invalid height");
            heights[i]=previous;
        }
        for(int i=0;i<light.length;i+=2) { int b=in.readUnsignedByte();light[i]=(byte)(b&15);light[i+1]=(byte)(b>>>4); }
        if(in.available()!=0) throw new IOException("Trailing tile bytes");
        try { return new TileData(colors,heights,light); } catch(IllegalArgumentException e) { throw new IOException("Invalid samples",e); }
    }
    public static byte[] hash(byte[] bytes) { try { return MessageDigest.getInstance("SHA-256").digest(bytes); }catch(NoSuchAlgorithmException e){throw new AssertionError(e);} }
}
