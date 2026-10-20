package dev.leymo.thorcraft.input;

import dev.leymo.thorcraft.ThorConfig;
import dev.leymo.thorcraft.ThorCraft;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.Util;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * "Press A... press B..." — records whatever your launcher sends for each Thor button,
 * so ABXY battle controls work no matter how the gamepad is mapped.
 * Buttons you don't press within 6 seconds are skipped.
 */
public class CalibrationScreen extends Screen {
    private static final String[] BUTTONS = {"A", "B", "X", "Y", "L", "R"};
    private static final long STEP_MS = 6000;

    private final Screen parent;
    private final Map<String, ThorConfig.PadBinding> result = new LinkedHashMap<>();
    private int step;
    private long stepStart;
    private long lastInput;

    public CalibrationScreen(Screen parent) {
        super(Text.literal("ThorCraft controller setup"));
        this.parent = parent;
    }

    @Override
    protected void init() {
        stepStart = Util.getMeasuringTimeMs();
    }

    @Override
    public void tick() {
        if (Util.getMeasuringTimeMs() - stepStart > STEP_MS) next();
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        record("key", keyCode);
        return true;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        record("mouse", button);
        return true;
    }

    private void record(String type, int code) {
        long now = Util.getMeasuringTimeMs();
        if (now - lastInput < 250 || now - stepStart < 300) return; // debounce
        for (ThorConfig.PadBinding b : result.values()) {
            if (b.type.equals(type) && b.code == code) return; // already used by an earlier button
        }
        lastInput = now;
        result.put(BUTTONS[step], new ThorConfig.PadBinding(type, code));
        next();
    }

    private void next() {
        step++;
        stepStart = Util.getMeasuringTimeMs();
        if (step >= BUTTONS.length) {
            ThorCraft.CONFIG.pad = result;
            ThorCraft.CONFIG.save();
            if (client != null) client.setScreen(parent);
            step = BUTTONS.length - 1;
        }
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        int cx = width / 2;
        int y = height / 2 - 50;
        ctx.drawCenteredTextWithShadow(textRenderer, title, cx, y, 0xFFFFFF);
        String b = BUTTONS[Math.min(step, BUTTONS.length - 1)];
        ctx.drawCenteredTextWithShadow(textRenderer, Text.literal("Press the  " + b + "  button on your Thor"), cx, y + 24, 0xFFD24A);
        long left = Math.max(0, (STEP_MS - (Util.getMeasuringTimeMs() - stepStart)) / 1000 + 1);
        ctx.drawCenteredTextWithShadow(textRenderer,
                Text.literal("Don't press anything to skip (" + left + "s)"), cx, y + 40, 0xAAAAAA);
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, ThorConfig.PadBinding> e : result.entrySet()) {
            ThorConfig.PadBinding pb = e.getValue();
            String name = pb.type.equals("key")
                    ? InputUtil.Type.KEYSYM.createFromCode(pb.code).getLocalizedText().getString()
                    : "Mouse " + (pb.code + 1);
            sb.append(e.getKey()).append(" = ").append(name).append("   ");
        }
        ctx.drawCenteredTextWithShadow(textRenderer, Text.literal(sb.toString()), cx, y + 64, 0x7FD6FF);
        ctx.drawCenteredTextWithShadow(textRenderer,
                Text.literal("Tip: in your launcher's gamepad settings give X and Y their own keys while in menus"), cx, y + 90, 0x888888);
    }
}
