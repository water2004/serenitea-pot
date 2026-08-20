package org.edtp.sereniteapot.mixin;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.portal.TeleportTransition;
import org.edtp.sereniteapot.player.PlayerStateManager;
import org.edtp.sereniteapot.level.SereniteaPotAccessPolicy;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(ServerPlayer.class)
public abstract class ServerPlayerTeleportMixin {
    @Unique
    private PlayerStateManager.StateSwitchPlan sereniteapot$pendingStateSwitch;

    @Inject(method = "teleport", at = @At("HEAD"), cancellable = true)
    private void sereniteapot$beforeTeleport(
        TeleportTransition transition,
        CallbackInfoReturnable<ServerPlayer> cir
    ) {
        ServerPlayer player = (ServerPlayer) (Object) this;
        var denial = SereniteaPotAccessPolicy.denialReason(player, transition.newLevel());
        if (denial != null) {
            player.sendSystemMessage(denial);
            cir.setReturnValue(null);
            return;
        }
        this.sereniteapot$pendingStateSwitch = PlayerStateManager.beforeTeleport(player, transition.newLevel());
    }

    @Inject(method = "teleport", at = @At("RETURN"))
    private void sereniteapot$afterTeleport(
        TeleportTransition transition,
        CallbackInfoReturnable<ServerPlayer> cir
    ) {
        PlayerStateManager.StateSwitchPlan plan = this.sereniteapot$pendingStateSwitch;
        this.sereniteapot$pendingStateSwitch = null;
        if (plan != null && cir.getReturnValue() != null) {
            PlayerStateManager.afterTeleport((ServerPlayer) (Object) this, plan);
        }
    }
}
