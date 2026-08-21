package org.edtp.sereniteapot.mixin;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.edtp.sereniteapot.player.PlayerStateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 玩家数据落盘后只需处理离线引起的壶生命周期。 */
@Mixin(PlayerList.class)
public abstract class PlayerListMixin {
    @Inject(
        method = "remove",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/players/PlayerList;save(Lnet/minecraft/server/level/ServerPlayer;)V",
            shift = At.Shift.AFTER
        )
    )
    private void sereniteapot$afterDisconnectSave(ServerPlayer player, CallbackInfo ci) {
        PlayerStateManager.onDisconnect(player);
    }
}
