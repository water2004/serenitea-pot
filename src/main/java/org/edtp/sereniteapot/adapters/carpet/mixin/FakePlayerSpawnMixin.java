package org.edtp.sereniteapot.adapters.carpet.mixin;

import carpet.patches.EntityPlayerMPFake;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.mojang.authlib.GameProfile;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import org.edtp.sereniteapot.adapters.carpet.CarpetCompatibility;
import org.edtp.sereniteapot.level.SereniteaPotLevelKeys;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;

@Pseudo
@Mixin(targets = "carpet.patches.EntityPlayerMPFake", remap = false)
public abstract class FakePlayerSpawnMixin {
    @WrapOperation(method = "createFake", at = @At(value = "INVOKE", target =
        "Ljava/util/concurrent/CompletableFuture;whenCompleteAsync(Ljava/util/function/BiConsumer;Ljava/util/concurrent/Executor;)Ljava/util/concurrent/CompletableFuture;"))
    private static CompletableFuture<GameProfile> sereniteapot$prepareBirth(
        CompletableFuture<GameProfile> profiles, BiConsumer<GameProfile, Throwable> callback,
        Executor executor, Operation<CompletableFuture<GameProfile>> original,
        @Local(argsOnly = true) MinecraftServer server, @Local ServerLevel level
    ) {
        if (SereniteaPotLevelKeys.identify(level.dimension()) == null) {
            return original.call(profiles, callback, executor);
        }
        return original.call(profiles, (BiConsumer<GameProfile, Throwable>) (profile, error) -> {
            if (error != null) callback.accept(profile, error);
            else CarpetCompatibility.spawnWhenReady(server, level, profile, callback);
        }, executor);
    }

    @WrapOperation(method = "lambda$createFake$0", at = @At(value = "INVOKE", target =
        "Lcarpet/patches/EntityPlayerMPFake;loadPlayerData(Lcarpet/patches/EntityPlayerMPFake;)V"))
    private static void sereniteapot$keepPublicDataOutside(EntityPlayerMPFake player, Operation<Void> original) {
        if (SereniteaPotLevelKeys.identify(player.level().dimension()) == null) original.call(player);
    }
}
