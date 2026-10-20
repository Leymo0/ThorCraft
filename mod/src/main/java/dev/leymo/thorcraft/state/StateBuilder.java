package dev.leymo.thorcraft.state;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import dev.leymo.thorcraft.ThorCraft;
import dev.leymo.thorcraft.cobble.BattleController;
import dev.leymo.thorcraft.cobble.CobbleBridge;
import dev.leymo.thorcraft.screen.ScreenBridge;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.LightType;

/** Builds the JSON "state" event the bottom panel renders from. */
public final class StateBuilder {
    private StateBuilder() {}

    private static volatile boolean force;

    public static void forceSoon() {
        force = true;
    }

    public static boolean consumeForce() {
        if (force) {
            force = false;
            return true;
        }
        return false;
    }

    public static void broadcast(MinecraftClient mc) {
        try {
            ThorCraft.SERVER.broadcast("state", build(mc).toString());
        } catch (Throwable t) {
            ThorCraft.LOG.debug("state build failed", t);
        }
    }

    public static JsonObject build(MinecraftClient mc) {
        JsonObject o = new JsonObject();
        o.addProperty("v", 1);
        o.addProperty("tick", ThorCraft.tick());
        o.add("cfg", ThorCraft.CONFIG.toPanelJson());
        o.addProperty("cobblemon", CobbleBridge.present());

        ClientPlayerEntity p = mc.player;
        boolean inWorld = p != null && mc.world != null;
        o.addProperty("inWorld", inWorld);
        o.addProperty("hudHidden", mc.options.hudHidden);
        o.add("screen", ScreenBridge.snapshot(mc));
        if (!inWorld) return o;

        // ---- player
        JsonObject pl = new JsonObject();
        pl.addProperty("name", p.getName().getString());
        pl.addProperty("hp", round1(p.getHealth()));
        pl.addProperty("maxHp", round1(p.getMaxHealth()));
        pl.addProperty("absorb", round1(p.getAbsorptionAmount()));
        pl.addProperty("food", p.getHungerManager().getFoodLevel());
        pl.addProperty("sat", round1(p.getHungerManager().getSaturationLevel()));
        pl.addProperty("armor", p.getArmor());
        pl.addProperty("air", p.getAir());
        pl.addProperty("maxAir", p.getMaxAir());
        pl.addProperty("xpLevel", p.experienceLevel);
        pl.addProperty("xp", round1(p.experienceProgress * 100f) / 100f);
        BlockPos pos = p.getBlockPos();
        pl.addProperty("x", pos.getX());
        pl.addProperty("y", pos.getY());
        pl.addProperty("z", pos.getZ());
        pl.addProperty("yaw", Math.round(MathHelper.wrapDegrees(p.getYaw())));
        pl.addProperty("facing", p.getHorizontalFacing().asString());
        pl.addProperty("biome", mc.world.getBiome(pos).getKey()
                .map(k -> Text.translatable("biome." + k.getValue().getNamespace() + "." + k.getValue().getPath()).getString())
                .orElse("?"));
        pl.addProperty("dim", mc.world.getRegistryKey().getValue().toString());
        long tod = mc.world.getTimeOfDay();
        pl.addProperty("time", tod % 24000L);
        pl.addProperty("day", tod / 24000L);
        pl.addProperty("blockLight", mc.world.getLightLevel(LightType.BLOCK, pos));
        pl.addProperty("skyLight", mc.world.getLightLevel(LightType.SKY, pos));
        pl.addProperty("raining", mc.world.isRaining());
        if (mc.interactionManager != null && mc.interactionManager.getCurrentGameMode() != null) {
            pl.addProperty("mode", mc.interactionManager.getCurrentGameMode().getName());
        }
        ServerInfo server = mc.getCurrentServerEntry();
        pl.addProperty("server", server != null ? server.name : "Singleplayer");
        pl.addProperty("fps", mc.getCurrentFps());
        pl.addProperty("sneaking", p.isSneaking());
        pl.addProperty("sneakLatched", dev.leymo.thorcraft.input.KeyTools.sneakLatched()
                || (mc.options.getSneakToggled().getValue() && mc.options.sneakKey.isPressed()));
        o.add("player", pl);

        // ---- hotbar
        JsonArray hb = new JsonArray();
        for (int i = 0; i < 9; i++) {
            JsonObject it = ItemJson.of(p.getInventory().getStack(i), false);
            hb.add(it == null ? JsonNull.INSTANCE : it);
        }
        o.add("hotbar", hb);
        JsonObject off = ItemJson.of(p.getOffHandStack(), false);
        o.add("offhand", off == null ? JsonNull.INSTANCE : off);
        o.addProperty("selected", p.getInventory().selectedSlot);

        // ---- effects
        JsonArray fx = new JsonArray();
        for (StatusEffectInstance e : p.getStatusEffects()) {
            JsonObject j = new JsonObject();
            j.addProperty("name", e.getEffectType().value().getName().getString());
            e.getEffectType().getKey().ifPresent(k -> j.addProperty("id", k.getValue().getNamespace() + "/" + k.getValue().getPath()));
            j.addProperty("amp", e.getAmplifier());
            j.addProperty("dur", e.isInfinite() ? -1 : e.getDuration());
            j.addProperty("good", e.getEffectType().value().isBeneficial());
            fx.add(j);
        }
        o.add("effects", fx);

        // ---- Cobblemon
        if (CobbleBridge.present()) {
            JsonObject cb = new JsonObject();
            try {
                cb.add("party", CobbleBridge.partyJson());
                cb.addProperty("selected", CobbleBridge.selectedSlot());
                cb.add("nearby", CobbleBridge.nearbyJson(mc));
            } catch (Throwable t) {
                CobbleBridge.reportError("party", t);
            }
            try {
                cb.add("battle", BattleController.json(mc));
            } catch (Throwable t) {
                CobbleBridge.reportError("battle json", t);
            }
            o.add("cobble", cb);
        }
        return o;
    }

    private static float round1(float f) {
        return Math.round(f * 10f) / 10f;
    }
}
