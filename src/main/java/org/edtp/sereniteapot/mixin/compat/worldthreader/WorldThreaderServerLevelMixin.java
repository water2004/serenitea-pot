package org.edtp.sereniteapot.mixin.compat.worldthreader;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.server.level.ServerLevel;
import no2.worldthreader.common.thread.WorldThreadingManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Adjusts calls injected into ServerLevel by the exact WorldThreader 3.1.0 mixins. */
@Mixin(value = ServerLevel.class, priority = 1100)
public abstract class WorldThreaderServerLevelMixin {
    // WT 3.1.0 calls this even while its active gamerule is false. A null guard
    // prevents the upstream safepoint mixin from dereferencing an absent manager.
    @WrapOperation(
        method = "safePoint",
        at = @At(
            value = "INVOKE",
            target = "Lno2/worldthreader/common/thread/WorldThreadingManager;threadingSafePoint()V",
            remap = false
        ),
        require = 1
    )
    private void sereniteapot$ignoreInactiveSafePoint(
        WorldThreadingManager manager,
        Operation<Void> original
    ) {
        if (manager != null) original.call(manager);
    }
}
