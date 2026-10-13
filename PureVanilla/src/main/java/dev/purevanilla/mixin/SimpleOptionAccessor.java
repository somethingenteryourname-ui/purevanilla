package dev.purevanilla.mixin;

import net.minecraft.client.option.SimpleOption;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * The only mixin in PureVanilla. It lets us read/write an option's stored value
 * without firing the option's change callback.
 *
 * Why: some vanilla callbacks do heavy work (smooth lighting calls a full
 * world-renderer reload, which blanks the terrain for a moment), and changing
 * any graphics option flips the Graphics preset to "Custom". PureVanilla sets
 * the raw value and then refreshes only what actually needs refreshing,
 * seamlessly. Accessor mixins add no per-frame cost.
 */
@Mixin(SimpleOption.class)
public interface SimpleOptionAccessor {
    @Accessor("value")
    Object purevanilla$getRawValue();

    @Accessor("value")
    void purevanilla$setRawValue(Object value);
}
