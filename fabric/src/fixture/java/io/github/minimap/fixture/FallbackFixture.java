package io.github.minimap.fixture;

import io.github.minimap.fabric.MiniMapClient;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.gui.screens.*;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import java.nio.file.*;

public final class FallbackFixture implements ClientModInitializer {
    private int ticks;private boolean connecting,done;
    @Override public void onInitializeClient(){
        ClientTickEvents.END_CLIENT_TICK.register(client->{
            if(done||++ticks%20!=0)return;
            if(client.level==null&&!connecting&&ticks>=100){connecting=true;String address=System.getenv("MINIMAP_TEST_SERVER");ConnectScreen.startConnecting(new TitleScreen(),client,ServerAddress.parseString(address),new ServerData("isolated",address,ServerData.Type.OTHER),false,null);}
            boolean passed=client.level!=null&&MiniMapClient.identity()==null;
            if((ticks<400||!passed)&&ticks<1600)return;
            done=true;
            try{Files.writeString(Path.of(System.getenv("MINIMAP_TEST_REPORT")),"{\"status\":\""+(passed?"PASS":"FAIL")+"\",\"detail\":\"Normal multiplayer without Xaero\"}");}catch(Exception e){throw new RuntimeException(e);}
            client.stop();
        });
    }
}
