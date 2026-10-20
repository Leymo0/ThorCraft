package dev.leymo.thorcraft.mixin;

import dev.leymo.thorcraft.screen.ScreenBridge;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * DS-style: while the bottom panel is connected, inventories / chests / server menus are drawn
 * only on the bottom screen and the top screen keeps showing the world.
 */
@Mixin(HandledScreen.class)
public abstract class HandledScreenMixin {
    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void thorcraft$hideOnTop(DrawContext context, int mouseX, int mouseY, float delta, CallbackInfo ci) {
        Screen self = (Screen) (Object) this;
        if (ScreenBridge.shouldHideOnTop(self)) {
            ScreenBridge.drawTopHint(context, self);
            ci.cancel();
        }
    }
}
