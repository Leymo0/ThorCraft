package dev.leymo.thorcraft.input;

import dev.leymo.thorcraft.ThorCraft;
import dev.leymo.thorcraft.cobble.BattleController;
import dev.leymo.thorcraft.cobble.CobbleBridge;
import net.fabricmc.fabric.api.client.screen.v1.ScreenKeyboardEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.minecraft.client.gui.screen.Screen;

/**
 * Physical Thor buttons during a Cobblemon battle. Whatever key/mouse button your launcher sends
 * for A/B/X/Y/L/R (captured once in the Controller setup screen) drives the battle menu.
 */
public final class PadInput {
    private PadInput() {}

    public static void attach(Screen screen) {
        if (screen instanceof CalibrationScreen) return;
        if (!CobbleBridge.isBattleScreen(screen)) return;
        ScreenKeyboardEvents.allowKeyPress(screen).register((s, key, scancode, modifiers) -> !handle("key", key));
        ScreenMouseEvents.allowMouseClick(screen).register((s, mouseX, mouseY, button) ->
                !(ThorCraft.CONFIG.interceptMouse && handle("mouse", button)));
    }

    /** @return true if the input was used (and should not reach the game). */
    private static boolean handle(String type, int code) {
        if (!ThorCraft.CONFIG.padInBattle) return false;
        String button = ThorCraft.CONFIG.buttonFor(type, code);
        if (button == null) return false;
        try {
            return BattleController.press(button);
        } catch (Throwable t) {
            CobbleBridge.reportError("pad " + button, t);
            return false;
        }
    }
}
