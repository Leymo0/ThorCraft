package dev.leymo.thorcraft.mixin;

import dev.leymo.thorcraft.ThorCraft;
import net.minecraft.client.render.GameRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** After each frame (world + GUI drawn, before the buffer swap) render item icons and the mirror frame. */
@Mixin(GameRenderer.class)
public class GameRendererMixin {
    @Inject(method = "render", at = @At("TAIL"))
    private void thorcraft$afterFrame(CallbackInfo ci) {
        ThorCraft.onFrameEnd();
    }
}
