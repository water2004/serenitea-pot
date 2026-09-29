package org.edtp.sereniteapot.mixin.compat.tpc;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.AndrewElizabeth.teleportcommandsfabric.core.teleport.types.TeleportOperation;
import org.AndrewElizabeth.teleportcommandsfabric.core.teleport.types.TeleportTarget;
import org.edtp.sereniteapot.level.SereniteaPotLevelKeys;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Objects;

/** Optional, call-site-only adapter: never changes Player.getAbilities() globally. */
@Pseudo
@Mixin(targets = "org.AndrewElizabeth.teleportcommandsfabric.core.teleport.task.TeleportExecutor", remap = false)
public abstract class TeleportExecutorMixin {
    @ModifyExpressionValue(
        method = "executeResolved",
        at = @At(value = "FIELD", target = "Lnet/minecraft/world/entity/player/Abilities;flying:Z", ordinal = 0)
    )
    private boolean sereniteapot$preserveDestinationFlight(
        boolean flying, MinecraftServer server, TeleportOperation operation, TeleportTarget target,
        @Local ServerPlayer player
    ) {
        var source = SereniteaPotLevelKeys.identify(player.level().dimension());
        var destination = SereniteaPotLevelKeys.identify(target.world().dimension());
        // Crossing realms restores destination abilities. TPC must not overwrite
        // them or send abilities from a player discarded by WorldThreader.
        return flying && Objects.equals(
            source == null ? null : source.owner(),
            destination == null ? null : destination.owner()
        );
    }
}
