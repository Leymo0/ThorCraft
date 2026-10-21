package dev.leymo.thorcraft.input;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.leymo.thorcraft.ThorCraft;
import dev.leymo.thorcraft.mixin.KeyBindingAccessor;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.text.Text;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

/** Press any keybinding (vanilla or modded) from the bottom screen. */
public final class KeyTools {
    private KeyTools() {}

    private static final Map<KeyBinding, Long> RELEASE_AT = new HashMap<>();

    public static KeyBinding find(String id) {
        MinecraftClient mc = MinecraftClient.getInstance();
        for (KeyBinding k : mc.options.allKeys) if (k.getTranslationKey().equals(id)) return k;
        return null;
    }

    /** First keybinding whose id contains all the given fragments (e.g. "cobblemon", "throw"). */
    public static KeyBinding findLike(String... parts) {
        MinecraftClient mc = MinecraftClient.getInstance();
        outer:
        for (KeyBinding k : mc.options.allKeys) {
            String id = k.getTranslationKey().toLowerCase();
            for (String p : parts) if (!id.contains(p)) continue outer;
            return k;
        }
        return null;
    }

    /** Keybinding currently bound to a key, e.g. "key.keyboard.c". */
    public static KeyBinding boundTo(String translationKey) {
        MinecraftClient mc = MinecraftClient.getInstance();
        for (KeyBinding k : mc.options.allKeys) {
            if (((dev.leymo.thorcraft.mixin.KeyBindingAccessor) k).thorcraft$getBoundKey().getTranslationKey().equals(translationKey)) return k;
        }
        return null;
    }

    public static boolean tap(KeyBinding k) {
        if (k == null) return false;
        KeyBindingAccessor a = (KeyBindingAccessor) k;
        a.thorcraft$setTimesPressed(a.thorcraft$getTimesPressed() + 1);
        k.setPressed(true);
        RELEASE_AT.put(k, ThorCraft.tick() + 2);
        return true;
    }

    public static boolean tap(String id) {
        return tap(find(id));
    }

    public static void hold(String id, boolean down) {
        KeyBinding k = find(id);
        if (k == null) return;
        RELEASE_AT.remove(k);
        k.setPressed(down);
    }

    public static void toggleHold(String id) {
        KeyBinding k = find(id);
        if (k == null) return;
        RELEASE_AT.remove(k);
        k.setPressed(!k.isPressed());
    }

    // ---------------------------------------------------------------- sneak toggle
    private static boolean sneakLatched;

    public static boolean sneakLatched() {
        return sneakLatched;
    }

    /**
     * Panel sneak button = toggle. If the game's own "Sneak: Toggle" option is on we just flip it;
     * otherwise ThorCraft keeps sneak held until you tap the button again (your controller's sneak still works as normal).
     */
    public static void toggleSneak() {
        MinecraftClient mc = MinecraftClient.getInstance();
        KeyBinding sneak = mc.options.sneakKey;
        if (mc.options.getSneakToggled().getValue()) {
            sneak.setPressed(true); // sticky key: each press flips it
            return;
        }
        sneakLatched = !sneakLatched;
        sneak.setPressed(sneakLatched);
    }

    public static void tick() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (sneakLatched) {
            if (mc.player == null) sneakLatched = false;
            else if (!mc.options.sneakKey.isPressed()) mc.options.sneakKey.setPressed(true);
        }
        if (RELEASE_AT.isEmpty()) return;
        long now = ThorCraft.tick();
        Iterator<Map.Entry<KeyBinding, Long>> it = RELEASE_AT.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<KeyBinding, Long> e = it.next();
            if (e.getValue() <= now) {
                e.getKey().setPressed(false);
                it.remove();
            }
        }
    }

    public static JsonElement listJson() {
        JsonArray arr = new JsonArray();
        MinecraftClient mc = MinecraftClient.getInstance();
        for (KeyBinding k : mc.options.allKeys) {
            JsonObject o = new JsonObject();
            o.addProperty("id", k.getTranslationKey());
            o.addProperty("name", Text.translatable(k.getTranslationKey()).getString());
            o.addProperty("cat", Text.translatable(k.getCategory()).getString());
            o.addProperty("bound", k.getBoundKeyLocalizedText().getString());
            arr.add(o);
        }
        return arr;
    }
}
