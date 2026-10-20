package dev.leymo.thorcraft;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonObject;
import net.fabricmc.loader.api.FabricLoader;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.LinkedHashMap;
import java.util.Map;

/** config/thorcraft.json */
public class ThorConfig {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Port of the local panel server (the companion app / a browser connects here). */
    public int port = 47123;
    /** false = only this device (127.0.0.1). true = also reachable over Wi-Fi with the token (for testing on PC/phone). */
    public boolean lan = false;
    public String token = randomToken();

    /** Draw inventories / chests / server menus only on the bottom screen while the panel is connected. */
    public boolean hideMenusOnTop = true;
    /** Physical ABXY drive the Cobblemon battle menu. */
    public boolean padInBattle = true;
    /** Also swallow mouse clicks mapped to pad buttons during battles (only needed if your launcher maps a face button to a mouse click). */
    public boolean interceptMouse = false;
    /** Close the battle UI for a moment when throwing a ball (turn on only if catching does nothing). */
    public boolean catchCloseGui = false;

    /** Ticks between state pushes (4 = 5 times a second). */
    public int stateInterval = 4;
    public int mirrorWidth = 640;
    public int mirrorFps = 10;
    public int mapRadius = 48;

    /** Calibrated pad buttons: "A" -> {type:"key"|"mouse", code}. */
    public Map<String, PadBinding> pad = new LinkedHashMap<>();

    public static class PadBinding {
        public String type;
        public int code;

        public PadBinding() {}

        public PadBinding(String type, int code) {
            this.type = type;
            this.code = code;
        }
    }

    public String buttonFor(String type, int code) {
        for (Map.Entry<String, PadBinding> e : pad.entrySet()) {
            PadBinding b = e.getValue();
            if (b != null && b.type.equals(type) && b.code == code) return e.getKey();
        }
        return null;
    }

    public JsonObject toPanelJson() {
        JsonObject o = new JsonObject();
        o.addProperty("hideMenusOnTop", hideMenusOnTop);
        o.addProperty("padInBattle", padInBattle);
        o.addProperty("interceptMouse", interceptMouse);
        o.addProperty("catchCloseGui", catchCloseGui);
        o.addProperty("mirrorFps", mirrorFps);
        o.addProperty("mapRadius", mapRadius);
        o.addProperty("padButtons", String.join(",", pad.keySet()));
        return o;
    }

    /** Settings the panel is allowed to change. */
    public void applyFromPanel(JsonObject j) {
        if (j.has("hideMenusOnTop")) hideMenusOnTop = j.get("hideMenusOnTop").getAsBoolean();
        if (j.has("padInBattle")) padInBattle = j.get("padInBattle").getAsBoolean();
        if (j.has("interceptMouse")) interceptMouse = j.get("interceptMouse").getAsBoolean();
        if (j.has("catchCloseGui")) catchCloseGui = j.get("catchCloseGui").getAsBoolean();
        if (j.has("mirrorFps")) mirrorFps = Math.max(2, Math.min(30, j.get("mirrorFps").getAsInt()));
        if (j.has("mapRadius")) mapRadius = Math.max(16, Math.min(96, j.get("mapRadius").getAsInt()));
        save();
    }

    private static Path path() {
        return FabricLoader.getInstance().getConfigDir().resolve("thorcraft.json");
    }

    public static ThorConfig load() {
        try {
            Path p = path();
            if (Files.exists(p)) {
                ThorConfig c = GSON.fromJson(Files.readString(p, StandardCharsets.UTF_8), ThorConfig.class);
                if (c != null) {
                    if (c.pad == null) c.pad = new LinkedHashMap<>();
                    if (c.token == null || c.token.isBlank()) c.token = randomToken();
                    c.save();
                    return c;
                }
            }
        } catch (Exception e) {
            ThorCraft.LOG.warn("Could not read thorcraft.json, using defaults", e);
        }
        ThorConfig c = new ThorConfig();
        c.save();
        return c;
    }

    public void save() {
        try {
            Files.createDirectories(path().getParent());
            Files.writeString(path(), GSON.toJson(this), StandardCharsets.UTF_8);
        } catch (Exception e) {
            ThorCraft.LOG.warn("Could not save thorcraft.json", e);
        }
    }

    private static String randomToken() {
        SecureRandom r = new SecureRandom();
        String chars = "abcdefghjkmnpqrstuvwxyz23456789";
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 8; i++) sb.append(chars.charAt(r.nextInt(chars.length())));
        return sb.toString();
    }
}
