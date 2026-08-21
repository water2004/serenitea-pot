package org.edtp.sereniteapot.mixin;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.BaseCommandBlock;
import org.edtp.sereniteapot.level.SereniteaPotLevelKeys;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Prevents block and minecart command blocks from executing inside any pot dimension. */
@Mixin(BaseCommandBlock.class)
public abstract class BaseCommandBlockMixin {
    @Inject(method = "performCommand", at = @At("HEAD"), cancellable = true)
    private void sereniteaPot$disableCommandBlocks(
            ServerLevel level,
            CallbackInfoReturnable<Boolean> callback) {
        if (SereniteaPotLevelKeys.identify(level.dimension()) != null) {
            callback.setReturnValue(false);
        }
    }
}
