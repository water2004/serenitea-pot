package org.edtp.sereniteapot.mixin;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.storage.PlayerDataStorage;
import org.edtp.sereniteapot.player.PlayerStateManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Keeps ordinary playerdata at the last public-world save boundary. */
@Mixin(PlayerDataStorage.class)
public abstract class PlayerDataStorageMixin {
    @Inject(method = "save", at = @At("HEAD"), cancellable = true)
    private void sereniteapot$savePrivateStateInstead(Player player, CallbackInfo ci) {
        if (player instanceof ServerPlayer serverPlayer
            && PlayerStateManager.saveIsolatedStateIfInsidePot(serverPlayer)) {
            ci.cancel();
        }
    }
}
