package dev.purevanilla;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;

/**
 * Re-meshes loaded terrain WITHOUT a renderer reload.
 *
 * Vanilla's smooth-lighting option normally calls WorldRenderer.reload(), which throws away all
 * chunk meshes (the world blinks out and rebuilds). Instead we mark the loaded sections dirty.
 * Sodium (and vanilla) keep drawing the old mesh until the new one is built, so nothing
 * disappears; the new lighting simply sweeps in over a few frames on the chunk-builder threads.
 * Sodium reads the smooth-lighting option at mesh-build time, so the rebuilt chunks use the new value.
 */
final class TerrainRefresher {
    private TerrainRefresher() {
    }

    static void refreshLoadedTerrain() {
        MinecraftClient client = MinecraftClient.getInstance();
        ClientWorld world = client.world;
        ClientPlayerEntity player = client.player;
        if (world == null || player == null || client.worldRenderer == null) return;

        int radius = client.options.getClampedViewDistance() + 1;
        int cx = player.getBlockX() >> 4;
        int cz = player.getBlockZ() >> 4;
        int minY = world.getBottomSectionCoord();
        int maxY = world.getTopSectionCoord();

        client.worldRenderer.scheduleChunkRenders(cx - radius, minY, cz - radius, cx + radius, maxY, cz + radius);
    }
}
