package org.edtp.sereniteapot.mixin.compat.worldthreader;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.server.level.ServerLevel;
import no2.worldthreader.common.thread.WorldThreadingManager;
import org.edtp.sereniteapot.performance.SereniteaPotScheduler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.function.BooleanSupplier;

/**
 * Worldthreader replaces Vanilla's level loop with this call site. Frozen pot
 * levels must still join Worldthreader's internal pre-tick barrier, otherwise
 * every other dimension deadlocks while waiting for them.
 */
@Mixin(targets = "no2.worldthreader.common.ServerWorldTicking", remap = false)
public abstract class WorldThreaderServerWorldTickingMixin {
    @WrapOperation(
        method = "tickThreaded",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;tick(Ljava/util/function/BooleanSupplier;)V",
            remap = true
        ),
        remap = false
    )
    private static void sereniteapot$budgetLevelTick(
        ServerLevel level,
        BooleanSupplier hasTimeLeft,
        Operation<Void> original,
        @Local(argsOnly = true) WorldThreadingManager threadingManager
    ) {
        if (!SereniteaPotScheduler.beforeLevelTick(level)) {
            // Worldthreader's ServerLevel mixin contributes one additional barrier
            // to every level tick. Pot levels are never its PrimaryLevelData world.
            threadingManager.withinTickBarrier();
            return;
        }
        long started = System.nanoTime();
        try {
            original.call(level, hasTimeLeft);
        } finally {
            SereniteaPotScheduler.afterLevelTick(level, System.nanoTime() - started);
        }
    }
}
