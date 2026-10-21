package dev.leymo.thorcraft.render;

import com.mojang.blaze3d.platform.GlStateManager;
import com.mojang.blaze3d.systems.RenderSystem;
import dev.leymo.thorcraft.ThorCraft;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.Framebuffer;
import net.minecraft.client.gl.SimpleFramebuffer;
import net.minecraft.client.texture.NativeImage;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL30;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Live picture of the top screen for menus the panel can't draw natively (Cobblemon PC, mod menus,
 * options...). Only runs while the panel is actually showing it (the panel keeps requesting frames).
 */
public final class MirrorCapture {
    private MirrorCapture() {}

    private static volatile long wantedUntil;
    private static volatile byte[] latest;
    private static volatile boolean encoding;
    private static long lastCapture;
    private static SimpleFramebuffer small;
    private static int failures;
    private static final ExecutorService ENCODER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "ThorCraft-mirror");
        t.setDaemon(true);
        return t;
    });

    /** Part of the screen to show, as fractions 0..1 (left, top, right, bottom). */
    private static volatile float[] rect = {0f, 0f, 1f, 1f};

    public static void request(float x0, float y0, float x1, float y1) {
        x0 = clamp01(x0); y0 = clamp01(y0); x1 = clamp01(x1); y1 = clamp01(y1);
        if (x1 - x0 < 0.05f || y1 - y0 < 0.05f) { x0 = 0; y0 = 0; x1 = 1; y1 = 1; }
        rect = new float[]{x0, y0, x1, y1};
        wantedUntil = System.currentTimeMillis() + 1500;
    }

    /** True while the panel is showing the live picture. */
    public static boolean active() {
        return System.currentTimeMillis() < wantedUntil;
    }

    private static float clamp01(float v) {
        return Math.max(0f, Math.min(1f, v));
    }

    public static byte[] latest() {
        return latest;
    }

    public static void fail(Throwable t) {
        if (failures++ < 3) ThorCraft.LOG.warn("ThorCraft mirror capture failed", t);
    }

    public static void capture(MinecraftClient mc) {
        long now = System.currentTimeMillis();
        if (now > wantedUntil || encoding || failures > 20) return;
        if (now - lastCapture < 1000 / Math.max(1, ThorCraft.CONFIG.mirrorFps)) return;
        lastCapture = now;

        Framebuffer main = mc.getFramebuffer();
        int mw = main.textureWidth, mh = main.textureHeight;
        if (mw <= 0 || mh <= 0) return;
        float[] r = rect;
        // GL framebuffers start at the bottom-left, the panel's rectangle at the top-left
        int sx0 = Math.round(r[0] * mw), sx1 = Math.round(r[2] * mw);
        int sy0 = Math.round((1f - r[3]) * mh), sy1 = Math.round((1f - r[1]) * mh);
        int cw = Math.max(1, sx1 - sx0), ch = Math.max(1, sy1 - sy0);
        int sw = Math.min(ThorCraft.CONFIG.mirrorWidth, cw);
        int sh = Math.max(1, Math.round((float) sw * ch / cw));
        if (small == null || small.textureWidth != sw || small.textureHeight != sh) {
            if (small != null) small.delete();
            small = new SimpleFramebuffer(sw, sh, false, MinecraftClient.IS_SYSTEM_MAC);
        }

        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, main.fbo);
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, small.fbo);
        GlStateManager._glBlitFrameBuffer(sx0, sy0, sx1, sy1, 0, 0, sw, sh, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_LINEAR);
        GlStateManager._glBindFramebuffer(GL30.GL_FRAMEBUFFER, main.fbo);
        main.beginWrite(true);

        NativeImage img = new NativeImage(sw, sh, false);
        RenderSystem.bindTexture(small.getColorAttachment());
        img.loadFromTextureImage(0, true);
        img.mirrorVertically();

        encoding = true;
        ENCODER.execute(() -> {
            try {
                latest = img.getBytes();
            } catch (Throwable t) {
                fail(t);
            } finally {
                img.close();
                encoding = false;
            }
        });
    }
}
