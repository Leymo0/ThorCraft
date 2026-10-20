package dev.leymo.thorcraft.state;

import com.google.gson.JsonObject;
import dev.leymo.thorcraft.ThorCraft;
import dev.leymo.thorcraft.cobble.CobbleBridge;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.MathHelper;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/** One-shot events for the panel: vibration on damage, battle start, shiny alerts. */
public final class Fx {
    private Fx() {}

    private static float lastHp = -1;
    private static boolean wasBattle;
    private static final Set<UUID> SHINIES_SEEN = new HashSet<>();

    public static void tick(MinecraftClient mc) {
        if (mc.player == null || mc.world == null) {
            lastHp = -1;
            SHINIES_SEEN.clear();
            return;
        }
        float hp = mc.player.getHealth();
        if (lastHp >= 0 && hp < lastHp - 0.4f) send("hurt", Math.round((lastHp - hp) * 10f) / 10f, null);
        lastHp = hp;

        if (!CobbleBridge.present()) return;
        boolean battle = CobbleBridge.battle() != null;
        if (battle && !wasBattle) send("battle", 0, null);
        wasBattle = battle;

        if (ThorCraft.tick() % 40 == 0) {
            for (Entity e : mc.world.getEntities()) {
                if (!CobbleBridge.isPokemonEntity(e)) continue;
                if (e.squaredDistanceTo(mc.player) > 64 * 64) continue;
                if (SHINIES_SEEN.contains(e.getUuid())) continue;
                Object pokemon = CobbleBridge.pokemonOf(e);
                if (pokemon != null && CobbleBridge.isShiny(pokemon) && CobbleBridge.isWild(e)) {
                    SHINIES_SEEN.add(e.getUuid());
                    double dx = e.getX() - mc.player.getX(), dz = e.getZ() - mc.player.getZ();
                    float angle = (float) (MathHelper.atan2(dz, dx) * 180 / Math.PI) - 90f;
                    String dir = compass(angle);
                    send("shiny", (float) Math.sqrt(dx * dx + dz * dz), CobbleBridge.speciesName(pokemon) + "|" + dir);
                }
            }
            if (SHINIES_SEEN.size() > 500) SHINIES_SEEN.clear();
        }
    }

    private static String compass(float yawDeg) {
        String[] dirs = {"S", "SW", "W", "NW", "N", "NE", "E", "SE"};
        int i = Math.floorMod(Math.round(MathHelper.wrapDegrees(yawDeg) / 45f), 8);
        return dirs[i];
    }

    public static void send(String type, float value, String text) {
        JsonObject o = new JsonObject();
        o.addProperty("type", type);
        o.addProperty("value", value);
        if (text != null) o.addProperty("text", text);
        ThorCraft.SERVER.broadcast("fx", o.toString());
    }
}
