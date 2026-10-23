package io.github.minimap.protocol;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

public final class Wire {
    public static final String CHANNEL="minimap:sync_v1";
    public static final int VERSION=1, MAX_FRAME=24576, FRAGMENT=16384, MAX_PARTS=4;
    public sealed interface Message permits Hello,Offer,Open,Request,Status,Part,Ack,Revoke {}
    public record Hello(int version) implements Message {}
    public record Offer(UUID session,String serverId,String worldId,String epoch,String dimension) implements Message {}
    public record Open(UUID session,boolean open) implements Message {}
    public record Request(UUID session,int requestId,TilePos pos,byte[] knownHash) implements Message {}
    public enum Result { UNCHANGED, MISSING, RETRY }
    public record Status(UUID session,int requestId,TilePos pos,Result result) implements Message {}
    public record Part(UUID session,int requestId,TilePos pos,byte[] hash,int rawLength,boolean compressed,int totalLength,int index,int count,byte[] bytes) implements Message {}
    public record Ack(UUID session,int requestId) implements Message {}
    public record Revoke(UUID session) implements Message {}
    private Wire() {}
    public static byte[] encode(Message m) throws IOException {
        var bytes=new ByteArrayOutputStream();var o=new DataOutputStream(bytes);o.writeInt(0x4d4d0001);
        if(m instanceof Hello v){o.writeByte(0);o.writeInt(v.version);}
        else if(m instanceof Offer v){o.writeByte(1);uuid(o,v.session);str(o,v.serverId);str(o,v.worldId);str(o,v.epoch);str(o,v.dimension);}
        else if(m instanceof Open v){o.writeByte(2);uuid(o,v.session);o.writeBoolean(v.open);}
        else if(m instanceof Request v){o.writeByte(3);uuid(o,v.session);o.writeInt(v.requestId);pos(o,v.pos);blob(o,v.knownHash,32);}
        else if(m instanceof Status v){o.writeByte(4);uuid(o,v.session);o.writeInt(v.requestId);pos(o,v.pos);o.writeByte(v.result.ordinal());}
        else if(m instanceof Part v){o.writeByte(5);uuid(o,v.session);o.writeInt(v.requestId);pos(o,v.pos);blob(o,v.hash,32);o.writeInt(v.rawLength);o.writeBoolean(v.compressed);o.writeInt(v.totalLength);o.writeInt(v.index);o.writeInt(v.count);blob(o,v.bytes,FRAGMENT);}
        else if(m instanceof Ack v){o.writeByte(6);uuid(o,v.session);o.writeInt(v.requestId);}
        else if(m instanceof Revoke v){o.writeByte(7);uuid(o,v.session);}
        if(bytes.size()>MAX_FRAME)throw new IOException("Frame too large");return bytes.toByteArray();
    }
    public static Message decode(byte[] bytes)throws IOException {
        if(bytes.length>MAX_FRAME)throw new IOException("Frame too large");var i=new DataInputStream(new ByteArrayInputStream(bytes));
        if(i.readInt()!=0x4d4d0001)throw new IOException("Unknown protocol");
        try {
            Message m=switch(i.readUnsignedByte()) {
                case 0 -> new Hello(i.readInt());
                case 1 -> new Offer(uuid(i),str(i),str(i),str(i),str(i));
                case 2 -> new Open(uuid(i),i.readBoolean());
                case 3 -> new Request(uuid(i),i.readInt(),pos(i),blob(i,32));
                case 4 -> new Status(uuid(i),i.readInt(),pos(i),Result.values()[i.readUnsignedByte()]);
                case 5 -> new Part(uuid(i),i.readInt(),pos(i),blob(i,32),i.readInt(),i.readBoolean(),i.readInt(),i.readInt(),i.readInt(),blob(i,FRAGMENT));
                case 6 -> new Ack(uuid(i),i.readInt());
                case 7 -> new Revoke(uuid(i));
                default -> throw new IOException("Unknown message");
            };
            if(i.available()!=0)throw new IOException("Trailing frame bytes");return m;
        }catch(IllegalArgumentException|IndexOutOfBoundsException e){throw new IOException("Invalid message",e);}
    }
    private static void uuid(DataOutputStream o,UUID u)throws IOException{o.writeLong(u.getMostSignificantBits());o.writeLong(u.getLeastSignificantBits());}
    private static UUID uuid(DataInputStream i)throws IOException{return new UUID(i.readLong(),i.readLong());}
    private static void str(DataOutputStream o,String s)throws IOException{blob(o,s.getBytes(StandardCharsets.UTF_8),256);}
    private static String str(DataInputStream i)throws IOException{return new String(blob(i,256),StandardCharsets.UTF_8);}
    private static void pos(DataOutputStream o,TilePos p)throws IOException{o.writeInt(p.x());o.writeInt(p.z());}
    private static TilePos pos(DataInputStream i)throws IOException{return new TilePos(i.readInt(),i.readInt());}
    private static void blob(DataOutputStream o,byte[] b,int max)throws IOException{if(b.length>max)throw new IOException("Field too large");o.writeInt(b.length);o.write(b);}
    private static byte[] blob(DataInputStream i,int max)throws IOException{int n=i.readInt();if(n<0||n>max||n>i.available())throw new IOException("Invalid field length");return i.readNBytes(n);}
}
