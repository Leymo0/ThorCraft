package dev.leymo.thorcraft.cobble;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import dev.leymo.thorcraft.ThorCraft;
import dev.leymo.thorcraft.render.IconRenderer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.command.argument.EntityAnchorArgumentType;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The battle menu lives here (not in the panel) so the physical ABXY buttons and the touch screen
 * always agree. Pages:
 *   MAIN   : A Fight · X Catch (wild) · Y Bag · B Run · R Pokémon
 *   FIGHT  : A/B/X/Y = moves 1-4 · L back
 *   SWITCH / TARGET / BALL : L/R move the cursor · A choose · B back
 */
public final class BattleController {
    private BattleController() {}

    public enum Page {MAIN, FIGHT, SWITCH, TARGET, BALL, RUN_CONFIRM, WAIT}

    private static Page page = Page.MAIN;
    private static int cursor;
    private static Object lastRequest;
    private static String pendingMoveId;
    private static String toast;
    private static long toastUntil;
    private static int throwInTicks = -1;
    private static int throwSlot = -1;
    private static int restoreGuiInTicks = -1;

    private static final String[] MOVE_BUTTONS = {"A", "B", "X", "Y"};
    private static final Set<String> TARGETED = Set.of("normal", "any", "adjacentfoe", "adjacentally", "adjacentallyorself");

    // ------------------------------------------------------------------ battle log
    // Reads the same messages Cobblemon prints in its battle message box ("Garchomp used Earthquake!",
    // "It's super effective!"...), so wording, translations and colours come straight from Cobblemon
    // and whatever lang/resource packs the modpack ships.

    private static final java.util.Set<Object> SEEN = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());
    private static final java.util.ArrayDeque<JsonArray> LOG = new java.util.ArrayDeque<>();
    private static Object logBattleId;

    private static void pollLog(MinecraftClient mc, Object battle) {
        Object id = R.get(battle, "battleId");
        if (id != null && !id.equals(logBattleId)) {
            logBattleId = id;
            SEEN.clear();
            LOG.clear();
        }
        List<Object> found = new ArrayList<>();
        // Prefer the battle's own message list; fall back to the battle screen's message box only if that's empty.
        collectFromMessageFields(battle, found);
        if (found.isEmpty() && CobbleBridge.isBattleScreen(mc.currentScreen)) collectFromMessageFields(mc.currentScreen, found);
        for (Object t : found) {
            if (!SEEN.add(t)) continue;
            JsonArray segs = t instanceof net.minecraft.text.Text tx ? dev.leymo.thorcraft.state.TextJson.segs(tx)
                    : dev.leymo.thorcraft.state.TextJson.segs((net.minecraft.text.OrderedText) t);
            String plain = segs.toString();
            if (segs.isEmpty() || plain.equals("[{\"t\":\" \"}]")) continue;
            LOG.addLast(segs);
            while (LOG.size() > 60) LOG.removeFirst();
        }
        if (SEEN.size() > 4000) SEEN.clear();
    }

    /** Only look inside fields whose name mentions "message" (the battle's message queue / the GUI's message pane). */
    private static void collectFromMessageFields(Object root, List<Object> out) {
        for (Class<?> k = root.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
            for (java.lang.reflect.Field f : k.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers())) continue;
                if (!f.getName().toLowerCase().contains("message")) continue;
                try {
                    f.setAccessible(true);
                    collectTexts(f.get(root), out, 0, java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()));
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static void collectTexts(Object o, List<Object> out, int depth, java.util.Set<Object> visited) {
        if (o == null || depth > 5 || out.size() > 400 || !visited.add(o)) return;
        if (o instanceof net.minecraft.text.Text || o instanceof net.minecraft.text.OrderedText) {
            out.add(o);
            return;
        }
        if (o instanceof Iterable<?> it) {
            for (Object x : it) collectTexts(x, out, depth + 1, visited);
            return;
        }
        if (o instanceof Object[] arr) {
            for (Object x : arr) collectTexts(x, out, depth + 1, visited);
            return;
        }
        // Wrapper objects from Cobblemon (e.g. a message line that holds its text): look inside.
        if (!o.getClass().getName().startsWith("com.cobblemon")) return;
        for (Class<?> k = o.getClass(); k != null && k != Object.class; k = k.getSuperclass()) {
            for (java.lang.reflect.Field f : k.getDeclaredFields()) {
                if (java.lang.reflect.Modifier.isStatic(f.getModifiers()) || f.getType().isPrimitive()) continue;
                try {
                    f.setAccessible(true);
                    collectTexts(f.get(o), out, depth + 1, visited);
                } catch (Throwable ignored) {
                }
            }
        }
    }

    // ------------------------------------------------------------------ tick

    public static void tick(MinecraftClient mc) {
        if (!CobbleBridge.present()) return;
        if (throwInTicks >= 0 && --throwInTicks < 0) doThrow(mc, throwSlot);
        if (restoreGuiInTicks >= 0 && --restoreGuiInTicks < 0) R.set(CobbleBridge.battle(), "minimised", false);

        Object battle = CobbleBridge.battle();
        if (battle == null) {
            page = Page.MAIN;
            lastRequest = null;
            return;
        }
        if (ThorCraft.tick() % 5 == 0) {
            try {
                pollLog(mc, battle);
            } catch (Throwable t) {
                CobbleBridge.reportError("battle log", t);
            }
        }
        Object req = currentRequest(battle);
        if (req != lastRequest) {
            lastRequest = req;
            cursor = 0;
            pendingMoveId = null;
            if (req == null) page = Page.WAIT;
            else page = R.b(R.get(req, "forceSwitch")) ? Page.SWITCH : Page.MAIN;
        }
    }

    static Object currentRequest(Object battle) {
        if (battle == null) return null;
        if (!R.b(R.get(battle, "mustChoose"))) return null;
        for (Object r : R.list(R.get(battle, "pendingActionRequests"))) {
            if (R.get(r, "response") == null) return r;
        }
        return null;
    }

    // ------------------------------------------------------------------ input

    /** Physical or on-screen button. Returns true if it did something. */
    public static boolean press(String btn) {
        MinecraftClient mc = MinecraftClient.getInstance();
        Object battle = CobbleBridge.battle();
        if (battle == null) return false;
        Object req = currentRequest(battle);
        if (req == null) return false;

        switch (page) {
            case MAIN -> {
                switch (btn) {
                    case "A" -> openFight(req);
                    case "X" -> {
                        if (isWild(battle)) openBalls(mc);
                        else openSwitch(req);
                    }
                    case "Y" -> openBag(mc, req);
                    case "B" -> {
                        page = Page.RUN_CONFIRM;
                        say("Press B again to run");
                    }
                    case "R" -> openSwitch(req);
                    default -> {
                        return false;
                    }
                }
            }
            case RUN_CONFIRM -> {
                if (btn.equals("B") || btn.equals("A")) run(req);
                else page = Page.MAIN;
            }
            case FIGHT -> {
                if (btn.equals("L")) page = Page.MAIN;
                else {
                    for (int i = 0; i < MOVE_BUTTONS.length; i++) if (MOVE_BUTTONS[i].equals(btn)) chooseMove(battle, req, i);
                }
            }
            case SWITCH, TARGET, BALL -> {
                int n = listSize(mc, battle, req);
                switch (btn) {
                    case "L" -> cursor = n == 0 ? 0 : (cursor - 1 + n) % n;
                    case "R" -> cursor = n == 0 ? 0 : (cursor + 1) % n;
                    case "A" -> pick(mc, battle, req, cursor);
                    case "B" -> {
                        if (page == Page.SWITCH && R.b(R.get(req, "forceSwitch"))) say("You must choose a Pokémon");
                        else page = page == Page.TARGET ? Page.FIGHT : Page.MAIN;
                    }
                    default -> {
                        return false;
                    }
                }
            }
            case WAIT -> {
                return false;
            }
        }
        return true;
    }

    /** Commands from the touch panel. */
    public static void op(MinecraftClient mc, JsonObject j) {
        Object battle = CobbleBridge.battle();
        if (battle == null) return;
        Object req = currentRequest(battle);
        String op = j.has("op") ? j.get("op").getAsString() : "";
        int index = j.has("index") ? j.get("index").getAsInt() : 0;
        if (op.equals("press")) {
            press(j.get("btn").getAsString());
            return;
        }
        if (req == null) return;
        switch (op) {
            case "fight" -> openFight(req);
            case "switchPage" -> openSwitch(req);
            case "catch" -> openBalls(mc);
            case "bag" -> openBag(mc, req);
            case "run" -> run(req);
            case "back" -> page = page == Page.TARGET ? Page.FIGHT : Page.MAIN;
            case "move" -> chooseMove(battle, req, index);
            case "pick" -> {
                cursor = index;
                pick(mc, battle, req, index);
            }
            default -> {
            }
        }
    }

    // ------------------------------------------------------------------ pages

    private static void openFight(Object req) {
        if (R.b(R.get(req, "forceSwitch"))) {
            page = Page.SWITCH;
            return;
        }
        page = Page.FIGHT;
    }

    private static void openSwitch(Object req) {
        Object moveSet = R.get(req, "moveSet");
        if (R.b(R.get(moveSet, "trapped"))) {
            say("Can't switch out — trapped!");
            return;
        }
        page = Page.SWITCH;
        cursor = 0;
    }

    private static void openBalls(MinecraftClient mc) {
        if (balls(mc).isEmpty()) {
            say("No Poké Balls in your inventory");
            return;
        }
        page = Page.BALL;
        cursor = 0;
    }

    /** Cobblemon's bag UI: try to open it directly; the panel shows the live mirror so you can tap items. */
    private static void openBag(MinecraftClient mc, Object req) {
        Screen sc = mc.currentScreen;
        boolean opened = false;
        if (CobbleBridge.isBattleScreen(sc)) {
            for (String cls : new String[]{
                    "client.gui.battle.subscreen.BattleBackpackSelection",
                    "client.gui.battle.subscreen.BattleBagSelection",
                    "client.gui.battle.subscreen.BattleBagActionSelection"}) {
                Object selection = R.newInstance(CobbleBridge.PKG + cls, sc, req);
                if (selection != null && R.call(sc, "changeActionSelection", selection) != R.NONE) {
                    opened = true;
                    break;
                }
            }
        }
        say(opened ? "Bag opened — tap an item below" : "Tap Bag on the picture below");
        bagMirror = true;
    }

    private static boolean bagMirror;

    // ------------------------------------------------------------------ actions

    private static void chooseMove(Object battle, Object req, int index) {
        List<?> moves = moves(req);
        if (index >= moves.size()) return;
        Object m = moves.get(index);
        if (R.b(R.get(m, "disabled"))) {
            say("That move can't be used right now");
            return;
        }
        String id = moveId(m);
        String target = String.valueOf(R.get(m, "target")).toLowerCase();
        List<JsonObject> targets = targetOptions(battle, target);
        if (TARGETED.contains(target) && targets.size() > 1) {
            pendingMoveId = id;
            page = Page.TARGET;
            cursor = 0;
            return;
        }
        submit(battle, req, R.newInstance(CobbleBridge.PKG + "battles.MoveActionResponse", id, null, null));
    }

    private static void run(Object req) {
        Object battle = CobbleBridge.battle();
        submit(battle, req, R.newInstance(CobbleBridge.PKG + "battles.ForfeitActionResponse"));
    }

    private static void pick(MinecraftClient mc, Object battle, Object req, int index) {
        switch (page) {
            case SWITCH -> {
                List<JsonObject> opts = switchOptions(battle, req);
                if (index >= opts.size()) return;
                JsonObject o = opts.get(index);
                if (!o.get("ok").getAsBoolean()) {
                    say(o.get("why").getAsString());
                    return;
                }
                UUID id = UUID.fromString(o.get("uuid").getAsString());
                submit(battle, req, R.newInstance(CobbleBridge.PKG + "battles.SwitchActionResponse", id));
            }
            case TARGET -> {
                String target = String.valueOf(R.get(currentMove(req), "target")).toLowerCase();
                List<JsonObject> opts = targetOptions(battle, target);
                if (index >= opts.size() || pendingMoveId == null) return;
                String pnx = opts.get(index).get("pnx").getAsString();
                submit(battle, req, R.newInstance(CobbleBridge.PKG + "battles.MoveActionResponse", pendingMoveId, pnx, null));
            }
            case BALL -> {
                List<JsonObject> opts = balls(mc);
                if (index >= opts.size()) return;
                throwBall(mc, battle, opts.get(index).get("slot").getAsInt());
            }
            default -> {
            }
        }
    }

    private static Object currentMove(Object req) {
        List<?> moves = moves(req);
        for (Object m : moves) if (moveId(m).equals(pendingMoveId)) return m;
        return null;
    }

    private static void submit(Object battle, Object req, Object response) {
        if (response == null) {
            say("Cobblemon changed something — open /api/debug");
            CobbleBridge.reportError("create response", new IllegalStateException("null response"));
            return;
        }
        MinecraftClient mc = MinecraftClient.getInstance();
        Screen sc = mc.currentScreen;
        boolean done = false;
        if (CobbleBridge.isBattleScreen(sc)) {
            done = R.call(sc, "selectAction", req, response) != R.NONE;
        }
        if (!done) {
            // Battle UI is minimised: answer the request ourselves.
            R.set(req, "response", response);
            List<?> all = R.list(R.get(battle, "pendingActionRequests"));
            boolean allAnswered = true;
            List<Object> responses = new ArrayList<>();
            for (Object r : all) {
                Object resp = R.get(r, "response");
                if (resp == null) allAnswered = false;
                responses.add(resp);
            }
            if (allAnswered) {
                Object pkt = R.newInstance(CobbleBridge.PKG + "net.messages.server.battle.BattleSelectActionsPacket",
                        R.get(battle, "battleId"), responses);
                if (CobbleBridge.sendToServer(pkt)) R.set(battle, "mustChoose", false);
                else say("Couldn't send — use the battle screen");
            }
        }
        page = Page.WAIT;
        bagMirror = false;
    }

    // ------------------------------------------------------------------ catching

    private static List<JsonObject> balls(MinecraftClient mc) {
        Map<String, JsonObject> byItem = new LinkedHashMap<>();
        if (mc.player == null) return new ArrayList<>();
        PlayerInventory inv = mc.player.getInventory();
        for (int i = 0; i < 36; i++) {
            ItemStack st = inv.getStack(i);
            if (st.isEmpty()) continue;
            Identifier id = Registries.ITEM.getId(st.getItem());
            if (!id.getNamespace().equals("cobblemon") || !id.getPath().endsWith("ball")) continue;
            JsonObject o = byItem.get(id.toString());
            if (o == null) {
                o = new JsonObject();
                o.addProperty("name", st.getName().getString());
                o.addProperty("icon", IconRenderer.key(st));
                o.addProperty("count", 0);
                o.addProperty("slot", i);
                byItem.put(id.toString(), o);
            }
            o.addProperty("count", o.get("count").getAsInt() + st.getCount());
            if (i < 9 && o.get("slot").getAsInt() >= 9) o.addProperty("slot", i); // prefer a hotbar stack
        }
        return new ArrayList<>(byItem.values());
    }

    private static void throwBall(MinecraftClient mc, Object battle, int invSlot) {
        if (ThorCraft.CONFIG.catchCloseGui) {
            R.set(battle, "minimised", true);
            mc.setScreen(null);
            throwSlot = invSlot;
            throwInTicks = 3;
            restoreGuiInTicks = 40;
        } else {
            doThrow(mc, invSlot);
        }
        say("Throwing!");
        page = Page.MAIN;
    }

    private static void doThrow(MinecraftClient mc, int invSlot) {
        if (mc.player == null || mc.interactionManager == null || invSlot < 0) return;
        PlayerInventory inv = mc.player.getInventory();
        int hotbar = invSlot;
        if (invSlot >= 9) {
            hotbar = inv.selectedSlot;
            // swap the ball stack into the selected hotbar slot (player inventory handler: slots 9-35 = main inventory)
            mc.interactionManager.clickSlot(mc.player.playerScreenHandler.syncId, invSlot, hotbar, SlotActionType.SWAP, mc.player);
        }
        inv.selectedSlot = hotbar;

        Entity target = findBattleTarget(mc, CobbleBridge.battle());
        if (target != null) {
            Vec3d c = target.getBoundingBox().getCenter();
            double dist = mc.player.getEyePos().distanceTo(c);
            mc.player.lookAt(EntityAnchorArgumentType.EntityAnchor.EYES, c.add(0, dist * 0.045, 0));
        }
        mc.interactionManager.interactItem(mc.player, Hand.MAIN_HAND);
    }

    private static Entity findBattleTarget(MinecraftClient mc, Object battle) {
        if (mc.world == null || mc.player == null) return null;
        Object battleId = R.get(battle, "battleId");
        Entity nearest = null;
        double best = 24 * 24;
        for (Entity e : mc.world.getEntities()) {
            if (!CobbleBridge.isPokemonEntity(e) || !CobbleBridge.isWild(e)) continue;
            Object bid = R.get(e, "battleId");
            if (bid instanceof java.util.Optional<?> opt) bid = opt.orElse(null);
            if (battleId != null && battleId.equals(bid)) return e;
            double d = e.squaredDistanceTo(mc.player);
            if (d < best) {
                best = d;
                nearest = e;
            }
        }
        return nearest;
    }

    // ------------------------------------------------------------------ data

    private static boolean isWild(Object battle) {
        return R.b(R.get(battle, "isPvW"));
    }

    static List<?> moves(Object req) {
        Object moveSet = R.get(req, "moveSet");
        return R.list(R.get(moveSet, "moves"));
    }

    static String moveId(Object m) {
        Object id = R.get(m, "id");
        if (id == null) id = R.get(m, "move");
        return String.valueOf(id);
    }

    /** Which side the local player is on: [allies side, foes side]. */
    private static Object[] sides(Object battle) {
        Object s1 = R.get(battle, "side1"), s2 = R.get(battle, "side2");
        MinecraftClient mc = MinecraftClient.getInstance();
        UUID me = mc.player != null ? mc.player.getUuid() : null;
        for (Object a : R.list(R.get(s2, "actors"))) {
            if (me != null && me.equals(CobbleBridge.uuid(a))) return new Object[]{s2, s1};
        }
        return new Object[]{s1, s2};
    }

    private static JsonArray sideJson(Object side, boolean mine) {
        JsonArray arr = new JsonArray();
        for (Object actor : R.list(R.get(side, "actors"))) {
            String trainer = R.str(R.get(actor, "displayName"));
            for (Object active : R.list(R.get(actor, "activePokemon"))) {
                Object bp = R.get(active, "battlePokemon");
                if (bp == null) continue;
                JsonObject o = new JsonObject();
                Object pnx = R.call(active, "getPNX");
                o.addProperty("pnx", pnx == R.NONE ? "" : String.valueOf(pnx));
                o.addProperty("trainer", trainer);
                o.addProperty("name", R.str(R.get(bp, "displayName")));
                o.addProperty("level", R.i(R.get(bp, "level"), 0));
                boolean flat = R.b(R.get(bp, "isHpFlat"));
                double hp = R.d(R.get(bp, "hpValue"), 0), max = R.d(R.get(bp, "maxHp"), 1);
                o.addProperty("ratio", flat ? (max > 0 ? hp / max : 0) : hp);
                if (flat) {
                    o.addProperty("hp", (int) Math.round(hp));
                    o.addProperty("maxHp", (int) Math.round(max));
                }
                o.addProperty("status", CobbleBridge.statusOf(bp));
                Object species = R.get(bp, "species");
                o.add("types", CobbleBridge.typesOfHolder(species));
                o.addProperty("species", R.str(R.get(species, "translatedName")));
                String icon = CobbleBridge.speciesIcon(species, R.get(bp, "aspects"));
                if (icon != null) o.addProperty("icon", icon);
                o.addProperty("mine", mine);
                arr.add(o);
            }
        }
        return arr;
    }

    private static List<JsonObject> targetOptions(Object battle, String target) {
        List<JsonObject> out = new ArrayList<>();
        Object[] sides = sides(battle);
        boolean foes = !target.equals("adjacentally") && !target.equals("adjacentallyorself");
        boolean allies = !target.equals("adjacentfoe");
        // Double battles: a move can't target the Pokémon using it (except "ally or self" moves)
        String self = "";
        Object req = currentRequest(battle);
        Object pnx = R.call(R.get(req, "activePokemon"), "getPNX");
        if (pnx != R.NONE && pnx != null) self = String.valueOf(pnx);
        boolean selfOk = target.equals("adjacentallyorself");
        if (foes) for (var e : sideJson(sides[1], false)) out.add(e.getAsJsonObject());
        if (allies) {
            for (var e : sideJson(sides[0], true)) {
                JsonObject o = e.getAsJsonObject();
                if (!selfOk && !self.isEmpty() && self.equals(o.get("pnx").getAsString())) continue;
                out.add(o);
            }
        }
        return out;
    }

    private static List<JsonObject> switchOptions(Object battle, Object req) {
        List<JsonObject> out = new ArrayList<>();
        java.util.Set<String> active = new java.util.HashSet<>();
        for (Object a : R.list(R.get(sides(battle)[0], "actors"))) {
            for (Object ap : R.list(R.get(a, "activePokemon"))) {
                Object bp = R.get(ap, "battlePokemon");
                if (bp != null) active.add(String.valueOf(R.get(bp, "uuid")));
            }
        }
        // Double battles: don't let the second Pokémon switch to the one the first already picked this turn
        java.util.Set<String> chosen = new java.util.HashSet<>();
        for (Object r : R.list(R.get(battle, "pendingActionRequests"))) {
            Object resp = R.get(r, "response");
            Object id = R.get(resp, "newPokemonId");
            if (id != null) chosen.add(String.valueOf(id));
        }
        List<?> party = CobbleBridge.partySlots();
        for (Object p : party) {
            if (p == null) continue;
            JsonObject o = CobbleBridge.pokemonJson(p, false);
            boolean fainted = o.get("hp").getAsInt() <= 0;
            String uuid = o.get("uuid").getAsString();
            boolean isActive = active.contains(uuid);
            boolean picked = chosen.contains(uuid);
            o.addProperty("ok", !fainted && !isActive && !picked);
            o.addProperty("why", fainted ? "It has no energy left to battle!" : isActive ? "Already in battle"
                    : picked ? "Already chosen this turn" : "");
            out.add(o);
        }
        return out;
    }

    private static int listSize(MinecraftClient mc, Object battle, Object req) {
        return switch (page) {
            case SWITCH -> switchOptions(battle, req).size();
            case TARGET -> {
                Object m = currentMove(req);
                yield targetOptions(battle, String.valueOf(R.get(m, "target")).toLowerCase()).size();
            }
            case BALL -> balls(mc).size();
            default -> 0;
        };
    }

    private static void say(String s) {
        toast = s;
        toastUntil = System.currentTimeMillis() + 2500;
    }

    // ------------------------------------------------------------------ JSON for the panel

    public static JsonObject json(MinecraftClient mc) {
        Object battle = CobbleBridge.battle();
        if (battle == null) return null;
        JsonObject o = new JsonObject();
        Object req = currentRequest(battle);
        o.addProperty("page", page.name());
        o.addProperty("cursor", cursor);
        o.addProperty("wild", isWild(battle));
        o.addProperty("choosing", req != null);
        o.addProperty("guiOpen", CobbleBridge.isBattleScreen(mc.currentScreen));
        o.addProperty("bagMirror", bagMirror);
        if (toast != null && System.currentTimeMillis() < toastUntil) o.addProperty("toast", toast);

        JsonArray log = new JsonArray();
        int skip = Math.max(0, LOG.size() - 30);
        int n = 0;
        for (JsonArray line : LOG) if (n++ >= skip) log.add(line);
        o.add("log", log);

        Object[] sides = sides(battle);
        o.add("allies", sideJson(sides[0], true));
        o.add("foes", sideJson(sides[1], false));

        if (req != null) {
            Object activeHolder = R.get(req, "activePokemon");
            Object bp = R.get(activeHolder, "battlePokemon");
            if (bp != null) o.addProperty("activeName", R.str(R.get(bp, "displayName")));
            Object selfPnx = R.call(activeHolder, "getPNX");
            if (selfPnx != R.NONE && selfPnx != null) o.addProperty("activePnx", String.valueOf(selfPnx));
            List<?> reqs = R.list(R.get(battle, "pendingActionRequests"));
            o.addProperty("requestIndex", reqs.indexOf(req) + 1);
            o.addProperty("requestCount", reqs.size());
            o.addProperty("forceSwitch", R.b(R.get(req, "forceSwitch")));
            o.addProperty("trapped", R.b(R.get(R.get(req, "moveSet"), "trapped")));

            JsonArray moves = new JsonArray();
            List<?> ms = moves(req);
            for (int i = 0; i < ms.size() && i < 4; i++) {
                Object m = ms.get(i);
                JsonObject j = new JsonObject();
                String id = moveId(m);
                j.addProperty("id", id);
                j.addProperty("btn", MOVE_BUTTONS[i]);
                j.addProperty("pp", R.i(R.get(m, "pp"), 0));
                j.addProperty("maxPp", R.i(R.get(m, "maxpp", "maxPp"), 0));
                j.addProperty("disabled", R.b(R.get(m, "disabled")));
                j.addProperty("target", String.valueOf(R.get(m, "target")));
                Object template = CobbleBridge.moveTemplate(id);
                CobbleBridge.fillMoveTemplate(j, template, null);
                if (!j.has("name")) j.addProperty("name", id);
                moves.add(j);
            }
            o.add("moves", moves);

            JsonArray list = new JsonArray();
            switch (page) {
                case SWITCH -> switchOptions(battle, req).forEach(list::add);
                case TARGET -> {
                    Object m = currentMove(req);
                    targetOptions(battle, String.valueOf(R.get(m, "target")).toLowerCase()).forEach(list::add);
                }
                case BALL -> balls(mc).forEach(list::add);
                default -> {
                }
            }
            o.add("list", list);
        } else {
            o.add("moves", new JsonArray());
            o.add("list", new JsonArray());
        }
        o.add("pad", ThorCraft.CONFIG.pad.isEmpty() ? JsonNull.INSTANCE : new com.google.gson.JsonPrimitive(String.join(",", ThorCraft.CONFIG.pad.keySet())));
        return o;
    }
}
