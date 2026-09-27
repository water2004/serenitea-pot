package org.edtp.sereniteapot.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Util;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.level.storage.PlayerDataStorage;
import org.edtp.sereniteapot.player.PlayerStateManager;
import org.edtp.sereniteapot.player.PublicPlayerData;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.file.Path;

/** Keeps ordinary playerdata at the last public-world save boundary. */
@Mixin(PlayerDataStorage.class)
public abstract class PlayerDataStorageMixin {
    @WrapOperation(method = "save", at = @At(value = "INVOKE",
            target = "Lnet/minecraft/util/Util;safeReplaceFile(Ljava/nio/file/Path;Ljava/nio/file/Path;Ljava/nio/file/Path;)V"))
    private void sereniteapot$reportSaveResult(Path target, Path temporary, Path backup, Operation<Void> original) {
        if (!PublicPlayerData.isCheckingSave()) {
            original.call(target, temporary, backup);
            return;
        }
        // Exactly the implementation used by safeReplaceFile, but keep its result.
        PublicPlayerData.recordSaveResult(Util.safeReplaceOrMoveFile(target, temporary, backup, false));
    }

    @Inject(method = "save", at = @At("HEAD"), cancellable = true)
    private void sereniteapot$savePrivateStateInstead(Player player, CallbackInfo ci) {
        if (player instanceof ServerPlayer serverPlayer
            && PlayerStateManager.saveIsolatedStateIfInsidePot(serverPlayer)) {
            ci.cancel();
        }
    }
}
