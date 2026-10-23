package io.github.minimap.server;

public final class TokenBucket {
    private final double rate,capacity;
    private double tokens;
    private long last;
    public TokenBucket(double rate,double capacity,long now){if(!Double.isFinite(rate)||!Double.isFinite(capacity)||rate<=0||capacity<=0)throw new IllegalArgumentException();this.rate=rate;this.capacity=capacity;tokens=capacity;last=now;}
    public boolean available(int bytes,long now){tokens=Math.min(capacity,tokens+Math.max(0,now-last)/1e9*rate);last=now;return bytes>=0&&bytes<=tokens;}
    public boolean take(int bytes,long now){if(!available(bytes,now))return false;tokens-=bytes;return true;}
}
