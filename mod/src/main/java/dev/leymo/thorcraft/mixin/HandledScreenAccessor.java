package dev.leymo.thorcraft.mixin;

import net.minecraft.client.gui.screen.ingame.HandledScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(HandledScreen.class)
public interface HandledScreenAccessor {
    @Accessor("backgroundWidth")
    int thorcraft$getBackgroundWidth();

    @Accessor("backgroundHeight")
    int thorcraft$getBackgroundHeight();

    @Accessor("x")
    int thorcraft$getX();

    @Accessor("y")
    int thorcraft$getY();
}
