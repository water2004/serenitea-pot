package org.edtp.universe.mixin;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.edtp.universe.player.PlayerStateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(PlayerList.class)
public abstract class PlayerListMixin {
    @Inject(method = "remove", at = @At("HEAD"))
    private void universe647$captureStateBeforeDisconnect(ServerPlayer player, CallbackInfo ci) {
        PlayerStateManager.onDisconnect(player);
    }
}
