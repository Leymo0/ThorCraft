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

    /** Fraction of the screen to show, centred (1 = whole screen, 0.5 = 2x zoom on the middle). */
    private static volatile float crop = 1f;

    public static void request(float zoomCrop) {
        crop = Math.max(0.25f, Math.min(1f, zoomCrop));
        wantedUntil = System.currentTimeMillis() + 1500;
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
        int sw = Math.min(ThorCraft.CONFIG.mirrorWidth, mw);
        int sh = Math.max(1, Math.round((float) sw * mh / mw));
        if (small == null || small.textureWidth != sw || small.textureHeight != sh) {
            if (small != null) small.delete();
            small = new SimpleFramebuffer(sw, sh, false, MinecraftClient.IS_SYSTEM_MAC);
        }

        GlStateManager._glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, main.fbo);
        GlStateManager._glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, small.fbo);
        int cx0 = Math.round(mw * (1f - crop) / 2f), cy0 = Math.round(mh * (1f - crop) / 2f);
        GlStateManager._glBlitFrameBuffer(cx0, cy0, mw - cx0, mh - cy0, 0, 0, sw, sh, GL11.GL_COLOR_BUFFER_BIT, GL11.GL_LINEAR);
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
