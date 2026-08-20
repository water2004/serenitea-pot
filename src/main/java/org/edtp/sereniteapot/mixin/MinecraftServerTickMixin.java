package org.edtp.sereniteapot.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.edtp.sereniteapot.performance.SereniteaPotScheduler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.function.BooleanSupplier;

@Mixin(MinecraftServer.class)
public abstract class MinecraftServerTickMixin {
    @WrapOperation(
        method = "tickChildren",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;tick(Ljava/util/function/BooleanSupplier;)V"
        )
    )
    private void sereniteapot$budgetLevelTick(
        ServerLevel level,
        BooleanSupplier hasTimeLeft,
        Operation<Void> original
    ) {
        if (!SereniteaPotScheduler.beforeLevelTick(level)) {
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
