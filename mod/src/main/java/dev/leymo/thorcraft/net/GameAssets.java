package dev.leymo.thorcraft.net;

import dev.leymo.thorcraft.render.IconRenderer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.resource.Resource;
import net.minecraft.util.Identifier;

import java.io.InputStream;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Lets the panel use the game's own art, through whatever resource packs are active:
 *   /tex/minecraft/gui/sprites/hud/heart/full.png  -> assets/minecraft/textures/gui/sprites/hud/heart/full.png
 *   /itemicon/minecraft/chest.png                 -> the chest item exactly as the game draws it
 */
public final class GameAssets {
    private GameAssets() {}

    private static final Map<String, byte[]> TEX_CACHE = new ConcurrentHashMap<>();
    private static final byte[] MISSING = new byte[0];

    /** "namespace/path/inside/textures" (no .png). */
    public static byte[] texture(String nsAndPath) {
        if (!nsAndPath.matches("[a-z0-9_.\\-]+/[a-z0-9_.\\-/]+")) return null;
        byte[] cached = TEX_CACHE.get(nsAndPath);
        if (cached != null) return cached == MISSING ? null : cached;
        int slash = nsAndPath.indexOf('/');
        Identifier id = Identifier.tryParse(nsAndPath.substring(0, slash) + ":textures/" + nsAndPath.substring(slash + 1) + ".png");
        if (id == null) return null;
        byte[] data = null;
        try {
            data = MinecraftClient.getInstance().submit(() -> {
                Optional<Resource> res = MinecraftClient.getInstance().getResourceManager().getResource(id);
                if (res.isEmpty()) return null;
                try (InputStream in = res.get().getInputStream()) {
                    return in.readAllBytes();
                } catch (java.io.IOException e) {
                    return null;
                }
            }).get(2, TimeUnit.SECONDS);
        } catch (Exception ignored) {
        }
        TEX_CACHE.put(nsAndPath, data == null ? MISSING : data);
        return data;
    }

    /** Called after a resource pack reload so the panel picks up new art. */
    public static void clear() {
        TEX_CACHE.clear();
    }

    /** "namespace/path" of an item -> icon key (rendering is queued if needed). */
    public static String itemIconKey(String nsAndPath) {
        int slash = nsAndPath.indexOf('/');
        if (slash <= 0) return null;
        Identifier id = Identifier.tryParse(nsAndPath.substring(0, slash) + ":" + nsAndPath.substring(slash + 1));
        if (id == null) return null;
        try {
            return MinecraftClient.getInstance().submit(() -> {
                Item item = Registries.ITEM.get(id);
                if (item == Items.AIR) return null;
                return IconRenderer.key(new ItemStack(item));
            }).get(2, TimeUnit.SECONDS);
        } catch (Exception e) {
            return null;
        }
    }
}
