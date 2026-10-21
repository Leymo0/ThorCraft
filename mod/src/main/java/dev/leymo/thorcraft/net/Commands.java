package dev.leymo.thorcraft.net;

import com.google.gson.JsonObject;
import dev.leymo.thorcraft.ThorCraft;
import dev.leymo.thorcraft.cobble.BattleController;
import dev.leymo.thorcraft.cobble.CobbleBridge;
import dev.leymo.thorcraft.input.CalibrationScreen;
import dev.leymo.thorcraft.input.KeyTools;
import dev.leymo.thorcraft.screen.ScreenBridge;
import dev.leymo.thorcraft.state.StateBuilder;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.option.Perspective;
import net.minecraft.client.util.ScreenshotRecorder;

/** Everything the bottom panel can ask the game to do. Always runs on the client thread. */
public final class Commands {
    private Commands() {}

    public static void handle(JsonObject j) {
        MinecraftClient mc = MinecraftClient.getInstance();
        String t = str(j, "t");
        try {
            switch (t) {
                // ---------------- general
                case "hotbar" -> {
                    if (mc.player != null) mc.player.getInventory().selectedSlot = Math.max(0, Math.min(8, num(j, "i")));
                }
                case "key" -> {
                    String id = str(j, "id");
                    switch (str(j, "mode")) {
                        case "hold" -> KeyTools.hold(id, true);
                        case "release" -> KeyTools.hold(id, false);
                        case "toggle" -> KeyTools.toggleHold(id);
                        default -> KeyTools.tap(id);
                    }
                }
                case "sneak" -> KeyTools.toggleSneak();
                case "inventory" -> {
                    if (mc.currentScreen == null) KeyTools.tap("key.inventory");
                }
                case "perspective" -> {
                    Perspective next = mc.options.getPerspective().next();
                    mc.options.setPerspective(next);
                }
                case "hud" -> mc.options.hudHidden = !mc.options.hudHidden;
                case "screenshot" -> ScreenshotRecorder.saveScreenshot(mc.runDirectory, mc.getFramebuffer(),
                        msg -> mc.execute(() -> mc.inGameHud.getChatHud().addMessage(msg)));
                case "pause" -> {
                    if (mc.currentScreen == null) mc.openGameMenu(false);
                }
                case "openChat" -> {
                    if (mc.currentScreen == null) mc.setScreen(new ChatScreen(""));
                }
                case "chat" -> {
                    String text = str(j, "text").trim();
                    if (text.isEmpty() || mc.getNetworkHandler() == null) return;
                    if (text.startsWith("/")) mc.getNetworkHandler().sendChatCommand(text.substring(1));
                    else mc.getNetworkHandler().sendChatMessage(text);
                    mc.inGameHud.getChatHud().addToMessageHistory(text);
                    if (mc.currentScreen instanceof ChatScreen) mc.setScreen(null);
                }
                case "calibrate" -> mc.setScreen(new CalibrationScreen(mc.currentScreen));
                case "config" -> {
                    ThorCraft.CONFIG.applyFromPanel(j.getAsJsonObject("values"));
                    StateBuilder.forceSoon();
                }

                // ---------------- screens
                case "close" -> {
                    if (mc.currentScreen != null) {
                        if (mc.currentScreen instanceof HandledScreen<?> || mc.currentScreen.shouldCloseOnEsc()) mc.currentScreen.close();
                    }
                }
                case "click" -> ScreenBridge.clickSlot(mc, num(j, "slot"), num(j, "button"), str(j, "act"));
                case "widget" -> ScreenBridge.clickWidget(mc, num(j, "i"));
                case "type" -> ScreenBridge.typeText(mc, str(j, "text"));
                case "settext" -> ScreenBridge.setFocusedText(mc, str(j, "text"));
                case "press" -> ScreenBridge.pressKey(mc, str(j, "key"));
                case "touch" -> ScreenBridge.mirrorTouch(mc, str(j, "kind"), dbl(j, "x"), dbl(j, "y"), num(j, "button"), dbl(j, "amount"));

                // ---------------- Cobblemon
                case "battle" -> BattleController.op(mc, j);
                case "party" -> {
                    switch (str(j, "op")) {
                        case "select" -> CobbleBridge.setSelectedSlot(num(j, "slot"));
                        case "sendout" -> CobbleBridge.sendOut(num(j, "slot"));
                        case "summary" -> CobbleBridge.openSummary(num(j, "slot"));
                        case "pokedex" -> KeyTools.tap(KeyTools.findLike("cobblemon", "pokedex"));
                        case "interact" -> {
                            if (!CobbleBridge.interactWith(mc, str(j, "uuid")) && mc.player != null) {
                                mc.player.sendMessage(net.minecraft.text.Text.literal("Get closer to interact"), true);
                            }
                        }
                        case "interactLook" -> CobbleBridge.pressInteract();
                        default -> {
                        }
                    }
                }
                default -> ThorCraft.LOG.debug("Unknown panel command {}", t);
            }
        } catch (Throwable e) {
            ThorCraft.LOG.warn("Panel command '{}' failed", t, e);
        }
        StateBuilder.forceSoon();
    }

    static String str(JsonObject j, String k) {
        return j.has(k) && !j.get(k).isJsonNull() ? j.get(k).getAsString() : "";
    }

    static int num(JsonObject j, String k) {
        return j.has(k) && !j.get(k).isJsonNull() ? j.get(k).getAsInt() : 0;
    }

    static double dbl(JsonObject j, String k) {
        return j.has(k) && !j.get(k).isJsonNull() ? j.get(k).getAsDouble() : 0;
    }
}
