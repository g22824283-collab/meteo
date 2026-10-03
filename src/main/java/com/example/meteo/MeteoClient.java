package com.example.meteo;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.List;

public class MeteoClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ClientPlayNetworking.registerGlobalReceiver(Meteo.RADAR_DATA, (client, handler, buf, sender) -> {
            BlockPos pos = buf.readBlockPos();
            int n = buf.readVarInt();
            List<StormCell> cells = new ArrayList<>();
            for (int i = 0; i < n; i++) cells.add(StormCell.read(buf));
            client.execute(() -> {
                if (client.currentScreen instanceof RadarScreen s) s.update(cells);
                else client.setScreen(new RadarScreen(pos, cells));
            });
        });
    }
}
