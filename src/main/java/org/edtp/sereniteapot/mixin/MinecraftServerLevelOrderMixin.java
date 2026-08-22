package org.edtp.sereniteapot.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.edtp.sereniteapot.performance.SereniteaPotScheduler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

/** Randomizes whole-pot order in the ordinary Vanilla level loop. */
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerLevelOrderMixin {
    @ModifyExpressionValue(
        method = "tickChildren",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/MinecraftServer;getAllLevels()Ljava/lang/Iterable;"
        )
    )
    private Iterable<ServerLevel> sereniteapot$orderWholePots(Iterable<ServerLevel> levels) {
        return SereniteaPotScheduler.orderLevelsForVanillaTick(levels);
    }
}
