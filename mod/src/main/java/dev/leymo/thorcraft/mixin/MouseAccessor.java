package dev.leymo.thorcraft.mixin;

import net.minecraft.client.Mouse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Moves the in-game cursor to where you touched the mirror, so hover tooltips follow your finger. */
@Mixin(Mouse.class)
public interface MouseAccessor {
    @Accessor("x")
    void thorcraft$setX(double x);

    @Accessor("y")
    void thorcraft$setY(double y);
}
