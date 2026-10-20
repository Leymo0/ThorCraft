package dev.leymo.thorcraft.map;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.leymo.thorcraft.ThorCraft;
import dev.leymo.thorcraft.cobble.CobbleBridge;
import net.minecraft.block.BlockState;
import net.minecraft.block.MapColor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.mob.HostileEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.Heightmap;

import java.util.Base64;

/** DS-style radar for the bottom screen: top-down map colors + nearby players, mobs and Pokémon (shinies starred). */
public final class MiniMap {
    private MiniMap() {}

    public static JsonElement build() {
        MinecraftClient mc = MinecraftClient.getInstance();
        JsonObject o = new JsonObject();
        ClientPlayerEntity p = mc.player;
        ClientWorld w = mc.world;
        if (p == null || w == null) return o;

        int r = ThorCraft.CONFIG.mapRadius;
        int size = r * 2;
        int px = p.getBlockX(), pz = p.getBlockZ(), py = p.getBlockY();
        boolean ceiling = w.getDimension().hasCeiling();
        byte[] rgb = new byte[size * size * 3];
        int[] heights = new int[size];
        BlockPos.Mutable pos = new BlockPos.Mutable();

        for (int dz = 0; dz < size; dz++) {
            for (int dx = 0; dx < size; dx++) {
                int x = px - r + dx, z = pz - r + dz;
                int idx = (dz * size + dx) * 3;
                if (!w.getChunkManager().isChunkLoaded(x >> 4, z >> 4)) {
                    rgb[idx] = rgb[idx + 1] = rgb[idx + 2] = 0;
                    continue;
                }
                int y;
                if (ceiling) {
                    y = py + 2;
                    while (y > py - 24 && w.getBlockState(pos.set(x, y, z)).isAir()) y--;
                } else {
                    y = w.getTopY(Heightmap.Type.WORLD_SURFACE, x, z) - 1;
                }
                BlockState st = w.getBlockState(pos.set(x, y, z));
                MapColor mcol = st.getMapColor(w, pos);
                int tries = 0;
                while (mcol == MapColor.CLEAR && tries++ < 6 && y > w.getBottomY()) {
                    y--;
                    st = w.getBlockState(pos.set(x, y, z));
                    mcol = st.getMapColor(w, pos);
                }
                int color = mcol.color;
                float shade = 1f;
                if (dz > 0) {
                    int north = heights[dx];
                    if (y > north) shade = 1.12f;
                    else if (y < north) shade = 0.82f;
                }
                if (mcol == MapColor.WATER_BLUE) shade = 0.95f;
                heights[dx] = y;
                rgb[idx] = (byte) MathHelper.clamp((int) (((color >> 16) & 0xFF) * shade), 0, 255);
                rgb[idx + 1] = (byte) MathHelper.clamp((int) (((color >> 8) & 0xFF) * shade), 0, 255);
                rgb[idx + 2] = (byte) MathHelper.clamp((int) ((color & 0xFF) * shade), 0, 255);
            }
        }
        o.addProperty("size", size);
        o.addProperty("img", Base64.getEncoder().encodeToString(rgb));
        o.addProperty("yaw", MathHelper.wrapDegrees(p.getYaw()));
        o.addProperty("x", px);
        o.addProperty("z", pz);

        JsonArray ents = new JsonArray();
        int count = 0;
        for (Entity e : w.getEntities()) {
            if (e == p || count > 120) continue;
            double dx = e.getX() - p.getX(), dz = e.getZ() - p.getZ();
            if (Math.abs(dx) > r || Math.abs(dz) > r) continue;
            JsonObject j = new JsonObject();
            j.addProperty("dx", Math.round(dx * 10) / 10.0);
            j.addProperty("dz", Math.round(dz * 10) / 10.0);
            if (e instanceof PlayerEntity pe) {
                j.addProperty("k", "player");
                j.addProperty("n", pe.getName().getString());
            } else if (CobbleBridge.isPokemonEntity(e)) {
                Object pk = CobbleBridge.pokemonOf(e);
                j.addProperty("k", "pokemon");
                j.addProperty("n", pk != null ? CobbleBridge.speciesName(pk) : "?");
                if (pk != null && CobbleBridge.isShiny(pk)) j.addProperty("shiny", true);
                j.addProperty("wild", CobbleBridge.isWild(e));
            } else if (e instanceof HostileEntity) {
                j.addProperty("k", "hostile");
                j.addProperty("n", e.getName().getString());
            } else if (e instanceof ItemEntity) {
                continue;
            } else {
                continue;
            }
            ents.add(j);
            count++;
        }
        o.add("ents", ents);
        return o;
    }
}
