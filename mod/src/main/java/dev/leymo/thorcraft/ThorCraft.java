package dev.leymo.thorcraft;

import dev.leymo.thorcraft.cobble.BattleController;
import dev.leymo.thorcraft.cobble.CobbleBridge;
import dev.leymo.thorcraft.input.CalibrationScreen;
import dev.leymo.thorcraft.input.KeyTools;
import dev.leymo.thorcraft.input.PadInput;
import dev.leymo.thorcraft.net.PanelServer;
import dev.leymo.thorcraft.render.IconRenderer;
import dev.leymo.thorcraft.render.MirrorCapture;
import dev.leymo.thorcraft.state.ChatFeed;
import dev.leymo.thorcraft.state.Fx;
import dev.leymo.thorcraft.state.StateBuilder;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import dev.leymo.thorcraft.net.GameAssets;
import net.fabricmc.fabric.api.resource.ResourceManagerHelper;
import net.fabricmc.fabric.api.resource.SimpleSynchronousResourceReloadListener;
import net.minecraft.client.util.InputUtil;
import net.minecraft.resource.ResourceManager;
import net.minecraft.resource.ResourceType;
import net.minecraft.util.Identifier;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class ThorCraft implements ClientModInitializer {
    public static final String ID = "thorcraft";
    public static final Logger LOG = LoggerFactory.getLogger("ThorCraft");

    public static ThorConfig CONFIG;
    public static PanelServer SERVER;

    private static KeyBinding keyCalibrate;
    private static KeyBinding keyToggleTop;
    private static long tick;

    @Override
    public void onInitializeClient() {
        CONFIG = ThorConfig.load();
        SERVER = new PanelServer(CONFIG);
        SERVER.start();

        keyCalibrate = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.thorcraft.calibrate", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, "category.thorcraft"));
        keyToggleTop = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.thorcraft.toggle_top", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_UNKNOWN, "category.thorcraft"));

        ClientTickEvents.END_CLIENT_TICK.register(ThorCraft::onTick);
        ScreenEvents.AFTER_INIT.register((client, screen, w, h) -> PadInput.attach(screen));
        ChatFeed.register();
        ResourceManagerHelper.get(ResourceType.CLIENT_RESOURCES).registerReloadListener(new SimpleSynchronousResourceReloadListener() {
            @Override
            public Identifier getFabricId() {
                return Identifier.of(ID, "panel_assets");
            }

            @Override
            public void reload(ResourceManager manager) {
                GameAssets.clear();
                IconRenderer.clearAll(); // resource pack changed -> re-render every icon
            }
        });

        LOG.info("ThorCraft ready. Cobblemon support: {}", CobbleBridge.present() ? "on" : "off (Cobblemon not installed)");
    }

    public static long tick() {
        return tick;
    }

    private static final List<long[]> LATER_AT = new ArrayList<>();
    private static final List<Runnable> LATER_RUN = new ArrayList<>();

    /** Run something a few ticks from now on the client thread. */
    public static void later(int ticks, Runnable r) {
        LATER_AT.add(new long[]{tick + ticks});
        LATER_RUN.add(r);
    }

    private static void runLater() {
        for (int i = LATER_AT.size() - 1; i >= 0; i--) {
            if (LATER_AT.get(i)[0] <= tick) {
                LATER_AT.remove(i);
                Runnable r = LATER_RUN.remove(i);
                try {
                    r.run();
                } catch (Throwable t) {
                    LOG.warn("ThorCraft delayed task failed", t);
                }
            }
        }
    }

    private static void onTick(MinecraftClient mc) {
        tick++;
        runLater();

        while (keyCalibrate.wasPressed()) mc.setScreen(new CalibrationScreen(mc.currentScreen));
        while (keyToggleTop.wasPressed()) {
            CONFIG.hideMenusOnTop = !CONFIG.hideMenusOnTop;
            CONFIG.save();
            if (mc.player != null) {
                mc.player.sendMessage(Text.literal("ThorCraft: menus on top screen " + (CONFIG.hideMenusOnTop ? "hidden" : "shown")), true);
            }
        }

        KeyTools.tick();
        try {
            BattleController.tick(mc);
        } catch (Throwable t) {
            CobbleBridge.reportError("battle tick", t);
        }

        if (SERVER.clientCount() > 0) {
            if (tick % Math.max(1, CONFIG.stateInterval) == 0 || StateBuilder.consumeForce()) {
                StateBuilder.broadcast(mc);
            }
            Fx.tick(mc);
        }
    }

    /** Called on the render thread at the end of every frame. */
    public static void onFrameEnd() {
        MinecraftClient mc = MinecraftClient.getInstance();
        try {
            IconRenderer.renderPending(mc);
        } catch (Throwable t) {
            IconRenderer.fail(t);
        }
        try {
            MirrorCapture.capture(mc);
        } catch (Throwable t) {
            MirrorCapture.fail(t);
        }
    }
}
