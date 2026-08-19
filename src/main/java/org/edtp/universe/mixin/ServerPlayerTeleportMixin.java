package org.edtp.universe.mixin;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.portal.TeleportTransition;
import org.edtp.universe.player.PlayerStateManager;
import org.edtp.universe.level.UniverseAccessPolicy;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerPlayer.class)
public abstract class ServerPlayerTeleportMixin {
    @Unique
    private PlayerStateManager.StateSwitchPlan universe647$pendingStateSwitch;

    @Inject(method = "teleport", at = @At("HEAD"), cancellable = true)
    private void universe647$beforeTeleport(
        TeleportTransition transition,
        CallbackInfoReturnable<ServerPlayer> cir
    ) {
        ServerPlayer player = (ServerPlayer) (Object) this;
        var denial = UniverseAccessPolicy.INSTANCE.denialReason(player, transition.newLevel());
        if (denial != null) {
            player.sendSystemMessage(denial);
            cir.setReturnValue(null);
            return;
        }
        this.universe647$pendingStateSwitch = PlayerStateManager.beforeTeleport(player, transition.newLevel());
    }

    @Inject(method = "teleport", at = @At("RETURN"))
    private void universe647$afterTeleport(
        TeleportTransition transition,
        CallbackInfoReturnable<ServerPlayer> cir
    ) {
        PlayerStateManager.StateSwitchPlan plan = this.universe647$pendingStateSwitch;
        this.universe647$pendingStateSwitch = null;
        if (plan != null && cir.getReturnValue() != null) {
            PlayerStateManager.afterTeleport((ServerPlayer) (Object) this, plan);
        }
    }
}
