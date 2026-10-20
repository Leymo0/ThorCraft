package dev.leymo.thorcraft.mixin;

import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Lets the bottom panel "press" any keybinding, including other mods' keys. */
@Mixin(KeyBinding.class)
public interface KeyBindingAccessor {
    @Accessor("timesPressed")
    int thorcraft$getTimesPressed();

    @Accessor("timesPressed")
    void thorcraft$setTimesPressed(int value);

    @Accessor("boundKey")
    InputUtil.Key thorcraft$getBoundKey();
}
