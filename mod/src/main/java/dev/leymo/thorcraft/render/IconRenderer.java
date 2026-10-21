package dev.leymo.thorcraft.render;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.systems.VertexSorter;
import dev.leymo.thorcraft.ThorCraft;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.SimpleFramebuffer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.DiffuseLighting;
import net.minecraft.client.texture.NativeImage;
import net.minecraft.component.DataComponentTypes;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import org.joml.Matrix4f;
import org.joml.Matrix4fStack;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Renders any ItemStack exactly like the game does (resource packs, modded items, player heads,
 * Cobblemon's 3D Pokémon portraits) into a small PNG that the panel shows with /icon/KEY.png.
 */
public final class IconRenderer {
    private IconRenderer() {}

    public static final int SIZE = 64;
    private static final int PER_FRAME = 8;

    private static final Map<String, byte[]> CACHE = new ConcurrentHashMap<>();
    private static final Set<String> QUEUED = ConcurrentHashMap.newKeySet();
    private static final Deque<Job> QUEUE = new ArrayDeque<>();
    private static final Deque<Job> RETRY = new ArrayDeque<>();
    private static SimpleFramebuffer fb;
    private static int failures;

    private record Job(String key, ItemStack stack, long notBefore, boolean isRetry) {}

    /** Content-based key; queues the icon for rendering if needed. Call on the client thread. */
    public static String key(ItemStack stack) {
        int id = Registries.ITEM.getId(stack.getItem()).toString().hashCode();
        int comp = stack.getComponentChanges().hashCode();
        String key = Integer.toHexString(id) + Integer.toHexString(comp * 31 + 7);
        if (!CACHE.containsKey(key) && QUEUED.add(key)) {
            synchronized (QUEUE) {
                QUEUE.add(new Job(key, stack.copyWithCount(1), 0, false));
            }
        }
        return key;
    }

    public static void clearAll() {
        CACHE.clear();
        QUEUED.clear();
        synchronized (QUEUE) {
            QUEUE.clear();
            RETRY.clear();
        }
    }

    public static byte[] get(String key) {
        return CACHE.get(key);
    }

    public static void fail(Throwable t) {
        if (failures++ < 3) ThorCraft.LOG.warn("ThorCraft icon rendering failed", t);
    }

    /** Render thread, end of frame. */
    public static void renderPending(MinecraftClient mc) {
        if (failures > 20) return;
        long now = System.currentTimeMillis();
        synchronized (QUEUE) {
            while (!RETRY.isEmpty() && RETRY.peekFirst().notBefore() <= now) QUEUE.add(RETRY.pollFirst());
            if (QUEUE.isEmpty()) return;
        }
        if (fb == null) {
            fb = new SimpleFramebuffer(SIZE, SIZE, true, MinecraftClient.IS_SYSTEM_MAC);
            fb.setClearColor(0f, 0f, 0f, 0f);
        }

        Matrix4f oldProjection = new Matrix4f(RenderSystem.getProjectionMatrix());
        VertexSorter oldSorter = RenderSystem.getVertexSorting();
        Matrix4fStack modelView = RenderSystem.getModelViewStack();
        modelView.pushMatrix();
        modelView.identity();
        modelView.translate(0f, 0f, -11000f);
        RenderSystem.applyModelViewMatrix();
        RenderSystem.setProjectionMatrix(new Matrix4f().setOrtho(0f, 16f, 16f, 0f, 1000f, 21000f), VertexSorter.BY_Z);
        DiffuseLighting.enableGuiDepthLighting();

        try {
            for (int n = 0; n < PER_FRAME; n++) {
                Job job;
                synchronized (QUEUE) {
                    job = QUEUE.pollFirst();
                }
                if (job == null) break;
                fb.clear(MinecraftClient.IS_SYSTEM_MAC);
                fb.beginWrite(true);
                DrawContext ctx = new DrawContext(mc, mc.getBufferBuilders().getEntityVertexConsumers());
                ctx.drawItem(job.stack(), 0, 0);
                ctx.draw();

                NativeImage img = new NativeImage(SIZE, SIZE, false);
                try {
                    RenderSystem.bindTexture(fb.getColorAttachment());
                    img.loadFromTextureImage(0, false);
                    img.mirrorVertically();
                    CACHE.put(job.key(), img.getBytes());
                } catch (java.io.IOException e) {
                    fail(e);
                } finally {
                    img.close();
                }
                // Player heads / Pokémon models can still be loading the first time: draw them once more shortly after.
                if (!job.isRetry() && (job.stack().contains(DataComponentTypes.PROFILE) || isAsyncModel(job.stack()))) {
                    synchronized (QUEUE) {
                        RETRY.add(new Job(job.key(), job.stack(), now + 2500, true));
                    }
                }
                if (CACHE.size() > 3000) {
                    CACHE.clear();
                    QUEUED.clear();
                }
            }
        } finally {
            modelView.popMatrix();
            RenderSystem.applyModelViewMatrix();
            RenderSystem.setProjectionMatrix(oldProjection, oldSorter);
            mc.getFramebuffer().beginWrite(true);
        }
    }

    private static boolean isAsyncModel(ItemStack stack) {
        return Registries.ITEM.getId(stack.getItem()).getNamespace().equals("cobblemon")
                && Registries.ITEM.getId(stack.getItem()).getPath().contains("pokemon");
    }
}
