package dev.leymo.thorcraft.state;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.leymo.thorcraft.render.IconRenderer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.tooltip.TooltipType;
import net.minecraft.registry.Registries;
import net.minecraft.text.Text;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class ItemJson {
    private ItemJson() {}

    private static final Map<String, JsonArray> LORE_CACHE = new ConcurrentHashMap<>();

    /** {i: iconKey, c: count, name: segs, d: durability 0..1, id: "minecraft:stone", lore: [[segs]...]} or null for empty. */
    public static JsonObject of(ItemStack st, boolean withLore) {
        if (st == null || st.isEmpty()) return null;
        JsonObject o = new JsonObject();
        String key = IconRenderer.key(st);
        o.addProperty("i", key);
        o.addProperty("c", st.getCount());
        o.addProperty("id", Registries.ITEM.getId(st.getItem()).toString());
        o.add("name", TextJson.segs(st.getName()));
        if (st.isDamageable() && st.getMaxDamage() > 0 && st.isDamaged()) {
            o.addProperty("d", Math.round(1000f * (1f - (float) st.getDamage() / st.getMaxDamage())) / 1000f);
        }
        if (st.hasGlint()) o.addProperty("g", 1);
        if (withLore) {
            JsonArray lore = LORE_CACHE.get(key);
            if (lore == null) {
                lore = buildLore(st);
                if (LORE_CACHE.size() > 4000) LORE_CACHE.clear();
                LORE_CACHE.put(key, lore);
            }
            if (!lore.isEmpty()) o.add("lore", lore);
        }
        return o;
    }

    private static JsonArray buildLore(ItemStack st) {
        JsonArray lore = new JsonArray();
        try {
            MinecraftClient mc = MinecraftClient.getInstance();
            Item.TooltipContext ctx = mc.world != null ? Item.TooltipContext.create(mc.world) : Item.TooltipContext.DEFAULT;
            List<Text> lines = st.getTooltip(ctx, mc.player, TooltipType.BASIC);
            for (int i = 1; i < lines.size() && i < 40; i++) lore.add(TextJson.segs(lines.get(i)));
        } catch (Throwable ignored) {
        }
        return lore;
    }

    public static void clearCache() {
        LORE_CACHE.clear();
    }
}
