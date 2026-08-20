package org.edtp.sereniteapot.mixin;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.edtp.sereniteapot.player.PlayerStateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** 在 Vanilla 保存断线玩家之前捕获其当前 realm，并恢复公共 playerdata。 */
@Mixin(PlayerList.class)
public abstract class PlayerListMixin {
    @Inject(method = "remove", at = @At("HEAD"))
    private void sereniteapot$captureStateBeforeDisconnect(ServerPlayer player, CallbackInfo ci) {
        PlayerStateManager.onDisconnect(player);
    }
}
