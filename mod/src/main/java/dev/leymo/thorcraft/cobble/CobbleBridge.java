package dev.leymo.thorcraft.cobble;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import dev.leymo.thorcraft.ThorCraft;
import dev.leymo.thorcraft.input.KeyTools;
import dev.leymo.thorcraft.render.IconRenderer;
import dev.leymo.thorcraft.state.ItemJson;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.TameableEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.text.Text;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Everything ThorCraft reads from Cobblemon. Uses reflection (see {@link R}) so the mod builds and
 * runs without Cobblemon, and a renamed field in a future Cobblemon only disables that one detail.
 * Open http://127.0.0.1:47123/api/debug in a browser on the Thor to see what was found.
 */
public final class CobbleBridge {
    private CobbleBridge() {}

    static final String PKG = "com.cobblemon.mod.common.";
    private static Boolean present;
    private static final Map<String, Integer> ERRORS = new HashMap<>();

    public static boolean present() {
        if (present == null) present = FabricLoader.getInstance().isModLoaded("cobblemon");
        return present;
    }

    public static void reportError(String where, Throwable t) {
        int n = ERRORS.merge(where, 1, Integer::sum);
        if (n <= 2) ThorCraft.LOG.warn("ThorCraft/Cobblemon: {} failed", where, t);
    }

    // ------------------------------------------------------------------ core accessors

    static Object client() {
        return R.kobject(PKG + "client.CobblemonClient");
    }

    public static Object battle() {
        if (!present()) return null;
        return R.get(client(), "battle");
    }

    static Object storage() {
        return R.get(client(), "storage");
    }

    public static List<?> partySlots() {
        Object party = R.get(storage(), "myParty");
        Object slots = R.get(party, "slots");
        return R.list(slots);
    }

    public static int selectedSlot() {
        return R.i(R.get(storage(), "selectedSlot"), 0);
    }

    public static void setSelectedSlot(int slot) {
        R.set(storage(), "selectedSlot", slot);
    }

    public static boolean isBattleScreen(Screen sc) {
        return sc != null && present() && sc.getClass().getName().endsWith(".BattleGUI");
    }

    public static boolean isPokemonEntity(Entity e) {
        return present() && e.getClass().getSimpleName().equals("PokemonEntity");
    }

    public static Object pokemonOf(Entity e) {
        return R.get(e, "pokemon");
    }

    public static boolean isShiny(Object pokemon) {
        return R.b(R.get(pokemon, "shiny"));
    }

    public static boolean isWild(Entity e) {
        return !(e instanceof TameableEntity t) || t.getOwnerUuid() == null;
    }

    public static String speciesName(Object pokemon) {
        Object species = R.get(pokemon, "species");
        String n = R.str(R.get(species, "translatedName"));
        return n != null ? n : String.valueOf(R.get(species, "name"));
    }

    // ------------------------------------------------------------------ party / summary

    public static JsonArray partyJson() {
        JsonArray arr = new JsonArray();
        List<?> slots = partySlots();
        for (int i = 0; i < slots.size() && i < 6; i++) {
            Object p = slots.get(i);
            arr.add(p == null ? JsonNull.INSTANCE : pokemonJson(p, false));
        }
        return arr;
    }

    public static JsonObject pokemonJson(Object p, boolean full) {
        JsonObject o = new JsonObject();
        o.addProperty("uuid", String.valueOf(R.get(p, "uuid")));
        String display = displayName(p);
        o.addProperty("name", display);
        Object species = R.get(p, "species");
        o.addProperty("species", speciesName(p));
        o.addProperty("dex", R.i(R.get(species, "nationalPokedexNumber"), 0));
        o.addProperty("level", R.i(R.get(p, "level"), 1));
        int hp = R.i(R.get(p, "currentHealth"), 0);
        int max = R.i(R.get(p, "maxHealth", "hp"), Math.max(1, hp));
        o.addProperty("hp", hp);
        o.addProperty("maxHp", max);
        o.addProperty("shiny", isShiny(p));
        o.addProperty("gender", R.str(R.get(p, "gender")));
        o.addProperty("status", statusOf(p));
        o.add("types", typesOf(p));
        ItemStack held = heldItem(p);
        JsonObject heldJson = held == null ? null : ItemJson.of(held, full);
        o.add("held", heldJson == null ? JsonNull.INSTANCE : heldJson);
        String icon = pokemonIcon(p);
        if (icon != null) o.addProperty("icon", icon);

        if (full) {
            Object form = R.get(p, "form");
            o.addProperty("form", R.str(R.get(form, "name")));
            Object nature = R.get(p, "effectiveNature", "nature");
            o.addProperty("nature", translateMaybe(R.str(R.get(nature, "displayName"))));
            Object ability = R.get(p, "ability");
            o.addProperty("ability", translateMaybe(R.str(R.get(ability, "displayName"))));
            o.addProperty("abilityDesc", translateMaybe(R.str(R.get(ability, "description"))));
            o.addProperty("friendship", R.i(R.get(p, "friendship"), 0));
            o.addProperty("exp", R.i(R.get(p, "experience"), 0));
            Object nextLevel = R.call(p, "getExperienceToNextLevel");
            if (nextLevel instanceof Number n) o.addProperty("expToNext", n.intValue());
            Object ot = R.get(p, "originalTrainerName");
            if (ot != null) o.addProperty("ot", R.str(ot));
            Object ball = R.get(p, "caughtBall");
            Object ballName = R.get(ball, "name");
            if (ballName != null) o.addProperty("ball", String.valueOf(ballName));
            Object tera = R.get(p, "teraType");
            if (tera != null) o.addProperty("tera", R.str(R.get(tera, "showdownId", "name")));
            o.addProperty("nickname", R.str(R.get(p, "nickname")));
            o.add("stats", statsJson(p));
            o.add("moves", movesJson(p));
            o.addProperty("desc", speciesDesc(species));
        }
        return o;
    }

    private static String displayName(Object p) {
        Object dn = R.get(p, "displayName");
        if (dn == null) dn = R.callFill(p, "getDisplayName");
        String s = R.str(dn);
        return s != null ? s : speciesName(p);
    }

    private static String speciesDesc(Object species) {
        Object desc = R.get(species, "pokedex");
        List<?> l = R.list(desc);
        if (!l.isEmpty()) return translateMaybe(String.valueOf(l.get(0)));
        return null;
    }

    static String translateMaybe(String s) {
        if (s == null) return null;
        if (s.contains(".") && !s.contains(" ")) return Text.translatable(s).getString();
        return s;
    }

    static String statusOf(Object p) {
        Object container = R.get(p, "status");
        Object status = R.get(container, "status");
        if (status == null) status = container; // battle Pokémon store the status directly
        if (status == null) return null;
        Object sd = R.get(status, "showdownName");
        if (sd != null) return String.valueOf(sd);
        Object n = R.get(status, "name");
        return n == null ? null : String.valueOf(n);
    }

    static JsonArray typesOf(Object p) {
        Object form = R.get(p, "form");
        Object holder = form != null ? form : R.get(p, "species");
        return typesOfHolder(holder);
    }

    static JsonArray typesOfHolder(Object holder) {
        JsonArray types = new JsonArray();
        for (String k : new String[]{"primaryType", "secondaryType"}) {
            Object t = R.get(holder, k);
            if (t != null) {
                Object n = R.get(t, "name", "showdownId");
                if (n != null) types.add(String.valueOf(n).toLowerCase());
            }
        }
        return types;
    }

    static ItemStack heldItem(Object p) {
        Object h = R.call(p, "heldItem");
        if (h == R.NONE) h = R.get(p, "heldItem");
        return h instanceof ItemStack st && !st.isEmpty() ? st : null;
    }

    private static JsonObject statsJson(Object p) {
        JsonObject o = new JsonObject();
        Class<?> statsEnum = R.cls(PKG + "api.pokemon.stats.Stats");
        if (statsEnum == null || statsEnum.getEnumConstants() == null) return o;
        Object ivs = R.get(p, "ivs");
        Object evs = R.get(p, "evs");
        for (Object stat : statsEnum.getEnumConstants()) {
            String name = ((Enum<?>) stat).name();
            if (!List.of("HP", "ATTACK", "DEFENCE", "SPECIAL_ATTACK", "SPECIAL_DEFENCE", "SPEED").contains(name)) continue;
            JsonObject s = new JsonObject();
            Object v = R.call(p, "getStat", stat);
            if (v instanceof Number n) s.addProperty("v", n.intValue());
            Object iv = R.call(ivs, "get", stat);
            if (iv == R.NONE) iv = R.call(ivs, "getOrDefault", stat);
            if (iv instanceof Number n) s.addProperty("iv", n.intValue());
            Object ev = R.call(evs, "get", stat);
            if (ev == R.NONE) ev = R.call(evs, "getOrDefault", stat);
            if (ev instanceof Number n) s.addProperty("ev", n.intValue());
            o.add(name.toLowerCase(), s);
        }
        return o;
    }

    private static JsonArray movesJson(Object p) {
        JsonArray arr = new JsonArray();
        Object moveSet = R.get(p, "moveSet");
        Object moves = R.call(moveSet, "getMoves");
        List<?> list = R.list(moves == R.NONE ? moveSet : moves);
        for (Object m : list) {
            if (m == null) continue;
            JsonObject j = new JsonObject();
            j.addProperty("name", R.str(R.get(m, "displayName", "name")));
            j.addProperty("pp", R.i(R.get(m, "currentPp"), 0));
            j.addProperty("maxPp", R.i(R.get(m, "maxPp"), 0));
            Object template = R.get(m, "template");
            fillMoveTemplate(j, template, R.get(m, "type"));
            arr.add(j);
        }
        return arr;
    }

    static void fillMoveTemplate(JsonObject j, Object template, Object typeOverride) {
        Object type = typeOverride != null ? typeOverride : R.get(template, "elementalType");
        if (type != null) j.addProperty("type", String.valueOf(R.get(type, "name")).toLowerCase());
        Object cat = R.get(template, "damageCategory");
        if (cat != null) j.addProperty("cat", String.valueOf(R.get(cat, "name")).toLowerCase());
        double power = R.d(R.get(template, "power"), 0);
        if (power > 0) j.addProperty("power", (int) power);
        double acc = R.d(R.get(template, "accuracy"), -1);
        if (acc > 0) j.addProperty("acc", (int) acc);
        Object desc = R.get(template, "description");
        if (desc != null) j.addProperty("desc", R.str(desc));
        if (!j.has("name") || j.get("name").isJsonNull()) {
            Object dn = R.get(template, "displayName");
            if (dn != null) j.addProperty("name", R.str(dn));
        }
    }

    /** Move info by Showdown id (used in battle where we only get the id). */
    static Object moveTemplate(String id) {
        Object moves = R.kobject(PKG + "api.moves.Moves");
        Object t = R.call(moves, "getByName", id);
        if (t == R.NONE || t == null) t = R.call(moves, "getByNameOrDummy", id);
        return t == R.NONE ? null : t;
    }

    // ------------------------------------------------------------------ icons

    private static final Map<String, Object> ICON_CACHE = new HashMap<>();

    /** Cobblemon's own 3D model portrait (PokemonItem), rendered through the normal icon pipeline. */
    public static String pokemonIcon(Object pokemon) {
        try {
            Object companion = R.companion(PKG + "item.PokemonItem");
            Object stack = R.callFill(companion, "from", pokemon);
            if (!(stack instanceof ItemStack)) {
                Object species = R.get(pokemon, "species");
                Object aspects = R.get(pokemon, "aspects");
                stack = R.callFill(companion, "from", species, aspects);
            }
            if (stack instanceof ItemStack st && !st.isEmpty()) return IconRenderer.key(st);
        } catch (Throwable t) {
            reportError("pokemon icon", t);
        }
        return null;
    }

    static String speciesIcon(Object species, Object aspects) {
        try {
            Object companion = R.companion(PKG + "item.PokemonItem");
            Object stack = R.callFill(companion, "from", species, aspects == null ? java.util.Collections.emptySet() : aspects);
            if (stack instanceof ItemStack st && !st.isEmpty()) return IconRenderer.key(st);
        } catch (Throwable t) {
            reportError("species icon", t);
        }
        return null;
    }

    // ------------------------------------------------------------------ actions

    /** Send out / recall the Pokémon in that party slot (uses Cobblemon's own "throw party Pokémon" key). */
    public static void sendOut(int slot) {
        setSelectedSlot(slot);
        if (!KeyTools.tap(KeyTools.findLike("cobblemon", "throw"))) {
            Object pkt = R.newInstance(PKG + "net.messages.server.SendOutPokemonPacket", slot);
            sendToServer(pkt);
        }
    }

    /**
     * Same as looking at someone and pressing R on PC: face the player (or Pokémon) and press Cobblemon's
     * "throw party Pokémon" key, which opens Cobblemon's interaction wheel (Battle / Trade / Team up / Spectate).
     * The wheel then shows on the bottom screen, zoomed in, so you can tap an option.
     */
    public static boolean interactWith(MinecraftClient mc, String uuid) {
        if (mc.player == null || mc.world == null) return false;
        Entity target = null;
        for (Entity e : mc.world.getEntities()) {
            if (e.getUuidAsString().equals(uuid)) {
                target = e;
                break;
            }
        }
        if (target == null || target.squaredDistanceTo(mc.player) > 10 * 10) return false;
        mc.player.lookAt(net.minecraft.command.argument.EntityAnchorArgumentType.EntityAnchor.EYES,
                target.getBoundingBox().getCenter().add(0, target.getHeight() * 0.25, 0));
        // let the crosshair update to the new aim before pressing the key
        ThorCraft.later(2, CobbleBridge::pressInteract);
        return true;
    }

    /** "Press R" on whatever you're looking at. */
    public static void pressInteract() {
        KeyTools.tap(KeyTools.findLike("cobblemon", "throw"));
    }

    /** Players (and your own sent-out Pokémon) close enough to interact with. */
    public static JsonArray nearbyJson(MinecraftClient mc) {
        JsonArray arr = new JsonArray();
        if (mc.player == null || mc.world == null) return arr;
        for (Entity e : mc.world.getEntities()) {
            if (e == mc.player) continue;
            double d2 = e.squaredDistanceTo(mc.player);
            if (d2 > 16 * 16) continue;
            boolean isPlayer = e instanceof net.minecraft.entity.player.PlayerEntity;
            boolean ownPokemon = present() && isPokemonEntity(e) && e instanceof TameableEntity t
                    && mc.player.getUuid().equals(t.getOwnerUuid());
            if (!isPlayer && !ownPokemon) continue;
            JsonObject o = new JsonObject();
            o.addProperty("uuid", e.getUuidAsString());
            o.addProperty("name", e.getName().getString());
            o.addProperty("kind", isPlayer ? "player" : "pokemon");
            o.addProperty("dist", Math.round(Math.sqrt(d2) * 10) / 10.0);
            o.addProperty("reach", d2 <= 10 * 10);
            if (ownPokemon) {
                String icon = pokemonIcon(pokemonOf(e));
                if (icon != null) o.addProperty("icon", icon);
            }
            arr.add(o);
        }
        return arr;
    }

    public static void openSummary(int slot) {
        setSelectedSlot(slot);
        KeyTools.tap(KeyTools.findLike("cobblemon", "summary"));
    }

    public static JsonElement summaryJson(int slot) {
        List<?> slots = partySlots();
        if (slot < 0 || slot >= slots.size() || slots.get(slot) == null) return JsonNull.INSTANCE;
        return pokemonJson(slots.get(slot), true);
    }

    static boolean sendToServer(Object packet) {
        if (packet == null) return false;
        if (R.call(packet, "sendToServer") != R.NONE) return true;
        Object net = R.kobject(PKG + "CobblemonNetwork");
        if (R.call(net, "sendToServer", packet) != R.NONE) return true;
        Class<?> impls = R.cls(PKG + "api.net.NetworkPacket$DefaultImpls");
        if (impls != null) {
            for (Method m : impls.getMethods()) {
                if (m.getName().equals("sendToServer") && Modifier.isStatic(m.getModifiers()) && m.getParameterCount() == 1) {
                    try {
                        m.invoke(null, packet);
                        return true;
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
        return false;
    }

    // ------------------------------------------------------------------ debug

    public static JsonElement debugJson() {
        JsonObject o = new JsonObject();
        o.addProperty("cobblemon", present());
        if (!present()) return o;
        o.addProperty("client", client() != null);
        o.addProperty("storage", storage() != null);
        o.addProperty("partySize", partySlots().size());
        Object battle = battle();
        o.addProperty("battle", battle != null);
        if (battle != null) {
            o.addProperty("battleClass", battle.getClass().getName());
            o.add("battleMembers", members(battle));
            Object req = BattleController.currentRequest(battle);
            if (req != null) o.add("requestMembers", members(req));
        }
        List<?> slots = partySlots();
        if (!slots.isEmpty() && slots.get(0) != null) o.add("pokemonMembers", members(slots.get(0)));
        Screen sc = MinecraftClient.getInstance().currentScreen;
        if (sc != null) {
            o.addProperty("screen", sc.getClass().getName());
            if (isBattleScreen(sc)) o.add("battleGuiMembers", members(sc));
        }
        JsonArray errs = new JsonArray();
        ERRORS.forEach((k, v) -> errs.add(k + " x" + v));
        o.add("errors", errs);
        return o;
    }

    private static JsonArray members(Object obj) {
        JsonArray a = new JsonArray();
        for (Method m : obj.getClass().getMethods()) {
            if (m.getDeclaringClass() == Object.class) continue;
            a.add(m.getName() + "(" + m.getParameterCount() + ")");
        }
        return a;
    }

    static UUID uuid(Object o) {
        Object u = R.get(o, "uuid");
        return u instanceof UUID id ? id : null;
    }
}
