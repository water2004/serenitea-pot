package org.edtp.sereniteapot.mixin.compat.tpc;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import org.AndrewElizabeth.teleportcommandsfabric.core.teleport.types.TeleportOperation;
import org.AndrewElizabeth.teleportcommandsfabric.core.teleport.types.TeleportTarget;
import org.AndrewElizabeth.teleportcommandsfabric.core.teleport.types.TeleportStatus;
import org.AndrewElizabeth.teleportcommandsfabric.core.teleport.manager.TeleportOperationManager;
import org.edtp.sereniteapot.level.SereniteaPotLevelKeys;
import org.edtp.sereniteapot.player.PlayerStateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.Objects;

/** Optional, call-site-only adapter: never changes Player.getAbilities() globally. */
@Pseudo
@Mixin(targets = "org.AndrewElizabeth.teleportcommandsfabric.core.teleport.task.TeleportExecutor", remap = false)
public abstract class TeleportExecutorMixin {
    @Shadow @Final private TeleportOperationManager operationManager;
    @Shadow public abstract TeleportStatus executeResolved(MinecraftServer server, TeleportOperation operation, TeleportTarget target);
    @Shadow public abstract TeleportStatus finishOperation(TeleportOperation operation, TeleportStatus status);

    @Inject(method = "executeResolved", at = @At("HEAD"), cancellable = true)
    private void sereniteapot$awaitRealmData(MinecraftServer server, TeleportOperation operation, TeleportTarget target,
            CallbackInfoReturnable<TeleportStatus> cir) {
        ServerPlayer player = server.getPlayerList().getPlayer(operation.playerUuid());
        if (!operationManager.isCurrent(operation) || player == null || player.isDeadOrDying()) return;
        var ready = PlayerStateManager.prepareTeleport(player, target.world());
        if (ready.isDone()) return;
        // TPC already separates acceptance from resultFuture completion. Resume its
        // executor, not a raw teleport, so cancellation, /back recording and effects
        // still use TPC's own path and complete the original request exactly once.
        PlayerStateManager.whenReady(player, ready, current -> {
            if (server.getLevel(target.world().dimension()) != target.world()) {
                finishOperation(operation, TeleportStatus.TARGET_UNAVAILABLE);
            } else {
                executeResolved(server, operation, target);
            }
        }).whenComplete((executed, error) -> {
            if (error != null) finishOperation(operation, TeleportStatus.FAILED);
            else if (!executed) finishOperation(operation, TeleportStatus.CANCELLED_BY_EVENT);
        });
        cir.setReturnValue(TeleportStatus.ACCEPTED);
    }

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
