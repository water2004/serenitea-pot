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

/**
 * 将访问检查和玩家状态隔离放在所有 ServerPlayer 跨维度传送的共同边界上。
 * 这样命令、传送门及其他模组发起的传送不会绕过规则。
 */
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
        // 这里只保存源状态；必须等原版传送成功返回后才能应用目标状态。
        this.sereniteapot$pendingStateSwitch = PlayerStateManager.beforeTeleport(player, transition.newLevel());
    }

    @Inject(method = "teleport", at = @At("RETURN"))
    private void sereniteapot$afterTeleport(
        TeleportTransition transition,
        CallbackInfoReturnable<ServerPlayer> cir
    ) {
        PlayerStateManager.StateSwitchPlan plan = this.sereniteapot$pendingStateSwitch;
        this.sereniteapot$pendingStateSwitch = null;
        // 原版以 null 表示传送失败，失败时保留当前玩家状态不变。
        if (plan != null && cir.getReturnValue() != null) {
            PlayerStateManager.afterTeleport((ServerPlayer) (Object) this, plan);
        }
    }
}
