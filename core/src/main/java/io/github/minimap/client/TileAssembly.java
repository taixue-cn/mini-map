package io.github.minimap.client;

import io.github.minimap.protocol.*;
import java.io.*;
import java.util.*;

/** One assembly belongs to one already-authorized request; never allocate from unchecked lengths. */
public final class TileAssembly {
    private Wire.Part first;
    private byte[][] parts;
    private int received;
    public Optional<TileCodec.Encoded> add(Wire.Part p)throws IOException {
        int count=(p.totalLength()+Wire.FRAGMENT-1)/Wire.FRAGMENT;
        if(p.hash().length!=32||p.rawLength()<1||p.rawLength()>TileCodec.MAX_RAW||p.totalLength()<1||p.totalLength()>TileCodec.MAX_RAW
            ||p.count()!=count||count>Wire.MAX_PARTS||p.index()<0||p.index()>=count
            ||p.bytes().length!=Math.min(Wire.FRAGMENT,p.totalLength()-p.index()*Wire.FRAGMENT))throw new IOException("Invalid fragment");
        if(first==null){first=p;parts=new byte[count][];}
        if(!p.session().equals(first.session())||p.requestId()!=first.requestId()||!p.pos().equals(first.pos())||!Arrays.equals(p.hash(),first.hash())
            ||p.rawLength()!=first.rawLength()||p.compressed()!=first.compressed()||p.totalLength()!=first.totalLength()||p.count()!=first.count())throw new IOException("Mixed fragments");
        byte[] previous=parts[p.index()];
        if(previous!=null){if(!Arrays.equals(previous,p.bytes()))throw new IOException("Conflicting fragment");return Optional.empty();}
        parts[p.index()]=p.bytes().clone();received++;
        if(received!=count)return Optional.empty();
        var buffer=new ByteArrayOutputStream(first.totalLength());for(byte[] part:parts)buffer.write(part);
        return Optional.of(new TileCodec.Encoded(first.hash(),first.rawLength(),first.compressed(),buffer.toByteArray()));
    }
}
