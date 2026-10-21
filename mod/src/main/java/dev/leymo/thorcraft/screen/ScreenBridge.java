package dev.leymo.thorcraft.screen;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import dev.leymo.thorcraft.ThorCraft;
import dev.leymo.thorcraft.cobble.CobbleBridge;
import dev.leymo.thorcraft.mixin.HandledScreenAccessor;
import dev.leymo.thorcraft.mixin.MouseAccessor;
import dev.leymo.thorcraft.state.ItemJson;
import dev.leymo.thorcraft.state.TextJson;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.Element;
import net.minecraft.client.gui.ParentElement;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.AbstractSignEditScreen;
import net.minecraft.client.gui.screen.ingame.AnvilScreen;
import net.minecraft.client.gui.screen.ingame.BookEditScreen;
import net.minecraft.client.gui.screen.ingame.CreativeInventoryScreen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.widget.CheckboxWidget;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.CyclingButtonWidget;
import net.minecraft.client.gui.widget.SliderWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.util.Window;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.screen.GenericContainerScreenHandler;
import net.minecraft.screen.ScreenHandler;
import net.minecraft.screen.slot.Slot;
import net.minecraft.screen.slot.SlotActionType;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Turns whatever screen is open into something the bottom panel can show:
 *  - "container": any inventory / chest / furnace / modded machine, as real slots you can tap
 *  - "menu"    : a server GUI built out of a chest (detected automatically) -> big tappable buttons
 *  - "mirror"  : everything else (Cobblemon PC, options, mod menus...) as a live picture you can touch
 *  - "chat", "battle": handled by their own panel views
 */
public final class ScreenBridge {
    private ScreenBridge() {}

    private static final Set<String> VANILLA_CHEST_TITLES_KEYS = Set.of(
            "container.chest", "container.chestDouble", "container.enderchest", "container.barrel",
            "container.shulkerBox", "container.minecart", "container.spruce_chest_boat");

    // --------------------------------------------------------------- snapshot

    public static JsonObject snapshot(MinecraftClient mc) {
        Screen sc = mc.currentScreen;
        JsonObject o = new JsonObject();
        if (sc == null) {
            o.addProperty("kind", "none");
            return o;
        }
        o.addProperty("cls", sc.getClass().getName());
        o.add("title", TextJson.segs(sc.getTitle()));
        o.addProperty("typing", acceptsTyping(sc));
        TextFieldWidget field = focusedField(sc);
        if (field != null) {
            // what is actually in the game's text box right now (shown live above the Android keyboard)
            o.addProperty("focusText", field.getText());
            o.addProperty("focusId", System.identityHashCode(field));
        }
        Window w = mc.getWindow();
        o.addProperty("aspect", (float) w.getFramebufferWidth() / Math.max(1, w.getFramebufferHeight()));

        if (CobbleBridge.isBattleScreen(sc)) {
            o.addProperty("kind", "battle");
        } else if (sc instanceof ChatScreen) {
            o.addProperty("kind", "chat");
        } else if (sc instanceof CreativeInventoryScreen) {
            o.addProperty("kind", "mirror");
            o.add("widgets", widgetsJson(sc));
        } else if (sc instanceof HandledScreen<?> hs) {
            container(mc, hs, o);
        } else {
            o.addProperty("kind", "mirror");
            o.add("widgets", widgetsJson(sc));
            // Cobblemon's interaction wheel (Battle / Trade / Team up / Spectate) sits small in the middle -> zoom in
            String name = sc.getClass().getSimpleName().toLowerCase();
            if (name.contains("wheel")) o.addProperty("zoom", 0.55);
        }
        return o;
    }

    private static void container(MinecraftClient mc, HandledScreen<?> hs, JsonObject o) {
        ScreenHandler h = hs.getScreenHandler();
        o.addProperty("kind", "container");
        o.addProperty("syncId", h.syncId);
        HandledScreenAccessor acc = (HandledScreenAccessor) hs;
        o.addProperty("bgW", acc.thorcraft$getBackgroundWidth());
        o.addProperty("bgH", acc.thorcraft$getBackgroundHeight());
        if (h instanceof GenericContainerScreenHandler g) o.addProperty("rows", g.getRows());

        JsonArray slots = new JsonArray();
        int containerSlots = 0, fillers = 0, named = 0;
        for (Slot sl : h.slots) {
            if (!sl.isEnabled()) continue;
            boolean playerInv = sl.inventory instanceof PlayerInventory;
            JsonObject j = new JsonObject();
            j.addProperty("id", sl.id);
            j.addProperty("x", sl.x);
            j.addProperty("y", sl.y);
            if (playerInv) {
                j.addProperty("p", 1);
                j.addProperty("idx", sl.getIndex());
            }
            ItemStack st = sl.getStack();
            if (!st.isEmpty()) {
                j.add("it", ItemJson.of(st, true));
                if (!playerInv) {
                    Identifier id = Registries.ITEM.getId(st.getItem());
                    if (id.getPath().endsWith("glass_pane")) fillers++;
                    if (st.contains(net.minecraft.component.DataComponentTypes.CUSTOM_NAME)) named++;
                }
            }
            if (!playerInv) containerSlots++;
            slots.add(j);
        }
        o.add("slots", slots);
        JsonObject cursor = ItemJson.of(h.getCursorStack(), false);
        o.add("cursor", cursor == null ? JsonNull.INSTANCE : cursor);

        // Server GUI ("chest menu") detection
        boolean menu = false;
        if (h instanceof GenericContainerScreenHandler && containerSlots > 0) {
            String title = hs.getTitle().getString();
            boolean vanillaTitle = false;
            for (String k : VANILLA_CHEST_TITLES_KEYS) {
                if (Text.translatable(k).getString().equals(title)) {
                    vanillaTitle = true;
                    break;
                }
            }
            boolean onServer = mc.getCurrentServerEntry() != null;
            menu = fillers >= 2 || (!vanillaTitle && onServer && named > 0) || (named * 2 >= containerSlots && onServer);
        }
        o.addProperty("menu", menu);
        // Server resource packs often paint a custom background over the menu using a special font
        // or private-use glyphs in the title. Those menus only look right as a picture.
        o.addProperty("painted", isPainted(hs.getTitle()));
    }

    /** True if the title uses a non-default font or private-use / negative-space glyphs (resource-pack GUI art). */
    public static boolean isPainted(Text title) {
        if (title == null) return false;
        boolean[] painted = {false};
        title.visit((style, str) -> {
            net.minecraft.util.Identifier font = style.getFont();
            if (font != null && !font.equals(net.minecraft.text.Style.DEFAULT_FONT_ID)) painted[0] = true;
            str.codePoints().forEach(cp -> {
                if ((cp >= 0xE000 && cp <= 0xF8FF) || cp >= 0xF0000) painted[0] = true;
            });
            return painted[0] ? java.util.Optional.of(Boolean.TRUE) : java.util.Optional.empty();
        }, net.minecraft.text.Style.EMPTY);
        return painted[0];
    }

    /** The text box that currently has focus, following nested containers (lists, tabs, panels). */
    public static TextFieldWidget focusedField(Screen sc) {
        Element f = sc.getFocused();
        for (int depth = 0; f != null && depth < 6; depth++) {
            if (f instanceof TextFieldWidget tf) return tf;
            if (f instanceof ParentElement pe) f = pe.getFocused();
            else break;
        }
        return null;
    }

    /** Replace the focused text box's contents (live typing from the panel). Returns false if there's no plain text box. */
    public static boolean setFocusedText(MinecraftClient mc, String text) {
        Screen sc = mc.currentScreen;
        if (sc == null || text == null) return false;
        TextFieldWidget tf = focusedField(sc);
        if (tf == null) return false;
        tf.setText(text);
        tf.setCursorToEnd(false);
        return true;
    }

    private static boolean acceptsTyping(Screen sc) {
        if (sc instanceof AbstractSignEditScreen || sc instanceof BookEditScreen || sc instanceof AnvilScreen || sc instanceof ChatScreen) return true;
        if (focusedField(sc) != null) return true;
        Element f = sc.getFocused();
        return f != null && f.getClass().getSimpleName().toLowerCase().contains("edit");
    }

    // --------------------------------------------------------------- widgets (buttons list for non-inventory screens)

    private static List<ClickableWidget> collectWidgets(Screen sc) {
        List<ClickableWidget> out = new ArrayList<>();
        for (Element e : sc.children()) collect(e, out, 0);
        return out;
    }

    private static void collect(Element e, List<ClickableWidget> out, int depth) {
        if (e instanceof ClickableWidget w) out.add(w);
        if (depth < 4 && e instanceof ParentElement pe && !(e instanceof ClickableWidget)) {
            for (Element c : pe.children()) collect(c, out, depth + 1);
        }
    }

    private static JsonArray widgetsJson(Screen sc) {
        JsonArray arr = new JsonArray();
        List<ClickableWidget> ws = collectWidgets(sc);
        for (int i = 0; i < ws.size() && i < 60; i++) {
            ClickableWidget w = ws.get(i);
            if (!w.visible) continue;
            JsonObject j = new JsonObject();
            j.addProperty("i", i);
            j.addProperty("label", w.getMessage().getString());
            j.addProperty("a", w.active);
            String type = "button";
            if (w instanceof TextFieldWidget tf) {
                type = "text";
                j.addProperty("value", tf.getText());
            } else if (w instanceof SliderWidget) type = "slider";
            else if (w instanceof CheckboxWidget cb) {
                type = "check";
                j.addProperty("checked", cb.isChecked());
            } else if (w instanceof CyclingButtonWidget<?>) type = "cycle";
            j.addProperty("type", type);
            arr.add(j);
        }
        return arr;
    }

    // --------------------------------------------------------------- commands

    public static void clickSlot(MinecraftClient mc, int slotId, int button, String action) {
        if (mc.player == null || mc.interactionManager == null) return;
        if (!(mc.currentScreen instanceof HandledScreen<?> hs)) return;
        SlotActionType type;
        try {
            type = SlotActionType.valueOf(action);
        } catch (Exception e) {
            type = SlotActionType.PICKUP;
        }
        mc.interactionManager.clickSlot(hs.getScreenHandler().syncId, slotId, button, type, mc.player);
    }

    public static void clickWidget(MinecraftClient mc, int index) {
        Screen sc = mc.currentScreen;
        if (sc == null) return;
        List<ClickableWidget> ws = collectWidgets(sc);
        if (index < 0 || index >= ws.size()) return;
        ClickableWidget w = ws.get(index);
        double cx = w.getX() + w.getWidth() / 2.0, cy = w.getY() + w.getHeight() / 2.0;
        sc.mouseClicked(cx, cy, 0);
        sc.mouseReleased(cx, cy, 0);
    }

    public static void typeText(MinecraftClient mc, String text) {
        Screen sc = mc.currentScreen;
        if (sc == null || text == null) return;
        text.codePoints().forEach(cp -> {
            for (char c : Character.toChars(cp)) sc.charTyped(c, 0);
        });
    }

    public static void pressKey(MinecraftClient mc, String key) {
        Screen sc = mc.currentScreen;
        if (sc == null) return;
        int code = switch (key) {
            case "backspace" -> GLFW.GLFW_KEY_BACKSPACE;
            case "enter" -> GLFW.GLFW_KEY_ENTER;
            case "tab" -> GLFW.GLFW_KEY_TAB;
            case "escape" -> GLFW.GLFW_KEY_ESCAPE;
            case "up" -> GLFW.GLFW_KEY_UP;
            case "down" -> GLFW.GLFW_KEY_DOWN;
            case "left" -> GLFW.GLFW_KEY_LEFT;
            case "right" -> GLFW.GLFW_KEY_RIGHT;
            case "delete" -> GLFW.GLFW_KEY_DELETE;
            default -> -1;
        };
        if (code < 0) return;
        if (code == GLFW.GLFW_KEY_ESCAPE && !sc.shouldCloseOnEsc()) return;
        sc.keyPressed(code, 0, 0);
        sc.keyReleased(code, 0, 0);
    }

    // --- touch on the mirror image (x, y are 0..1 of the picture)
    private static boolean mirrorDown;
    private static double lastX, lastY;

    public static void mirrorTouch(MinecraftClient mc, String kind, double nx, double ny, int button, double amount) {
        Screen sc = mc.currentScreen;
        if (sc == null) return;
        Window w = mc.getWindow();
        double sx = nx * w.getScaledWidth();
        double sy = ny * w.getScaledHeight();
        try {
            MouseAccessor m = (MouseAccessor) mc.mouse;
            m.thorcraft$setX(nx * w.getWidth());
            m.thorcraft$setY(ny * w.getHeight());
        } catch (Throwable ignored) {
        }
        switch (kind) {
            case "down" -> {
                sc.mouseMoved(sx, sy);
                sc.mouseClicked(sx, sy, button);
                mirrorDown = true;
            }
            case "move" -> {
                if (mirrorDown) sc.mouseDragged(sx, sy, button, sx - lastX, sy - lastY);
                else sc.mouseMoved(sx, sy);
            }
            case "up" -> {
                sc.mouseReleased(sx, sy, button);
                mirrorDown = false;
            }
            case "scroll" -> sc.mouseScrolled(sx, sy, 0, amount);
            default -> {
            }
        }
        lastX = sx;
        lastY = sy;
    }

    // --------------------------------------------------------------- top-screen hiding

    public static boolean shouldHideOnTop(Screen sc) {
        if (!ThorCraft.CONFIG.hideMenusOnTop) return false;
        if (ThorCraft.SERVER == null || ThorCraft.SERVER.clientCount() == 0) return false;
        if (sc instanceof CreativeInventoryScreen) return false;
        if (sc instanceof AnvilScreen) return false; // keep the text box visible
        return sc instanceof HandledScreen<?>;
    }

    public static void drawTopHint(DrawContext ctx, Screen sc) {
        MinecraftClient mc = MinecraftClient.getInstance();
        String title = sc.getTitle().getString();
        Text hint = Text.literal((title.isEmpty() ? "Menu" : title) + "  ▼ on the bottom screen");
        int w = mc.textRenderer.getWidth(hint);
        int x = (sc.width - w) / 2;
        ctx.fill(x - 6, 6, x + w + 6, 20, 0x99000000);
        ctx.drawTextWithShadow(mc.textRenderer, hint, x, 9, 0xFFFFFF);
    }
}
