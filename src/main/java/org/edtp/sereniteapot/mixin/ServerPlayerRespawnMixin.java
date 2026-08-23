package org.edtp.sereniteapot.mixin;

import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.portal.TeleportTransition;
import org.edtp.sereniteapot.level.SereniteaPotTravelService;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Prevents Vanilla's default death respawn from crossing a pot/public realm boundary. */
@Mixin(ServerPlayer.class)
public abstract class ServerPlayerRespawnMixin {
    @Inject(method = "findRespawnPositionAndUseSpawnBlock", at = @At("RETURN"), cancellable = true)
    private void sereniteapot$containRespawn(
        boolean consumeSpawnBlock,
        TeleportTransition.PostTeleportTransition postTeleportTransition,
        CallbackInfoReturnable<TeleportTransition> cir
    ) {
        ServerPlayer player = (ServerPlayer) (Object) this;
        cir.setReturnValue(SereniteaPotTravelService.containRespawn(player, cir.getReturnValue()));
    }
}
