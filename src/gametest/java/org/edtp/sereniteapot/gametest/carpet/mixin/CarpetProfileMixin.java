package org.edtp.sereniteapot.gametest.carpet.mixin;

import com.mojang.authlib.GameProfile;
import net.minecraft.server.MinecraftServer;
import org.edtp.sereniteapot.gametest.carpet.CarpetTestProfiles;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/** Only the external profile lookup is replaced; Carpet's real birth/logout still run. */
@Pseudo
@Mixin(targets = "carpet.patches.EntityPlayerMPFake", remap = false)
public abstract class CarpetProfileMixin {
    @Inject(method = "fetchGameProfile", at = @At("HEAD"), cancellable = true)
    private static void sereniteapot$testProfile(MinecraftServer server, UUID id,
        CallbackInfoReturnable<CompletableFuture<GameProfile>> cir) {
        var lookup = CarpetTestProfiles.pending.get(id);
        if (lookup != null) cir.setReturnValue(lookup);
    }
}
