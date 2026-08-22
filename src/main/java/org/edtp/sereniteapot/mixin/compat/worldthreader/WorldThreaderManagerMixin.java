package org.edtp.sereniteapot.mixin.compat.worldthreader;

import it.unimi.dsi.fastutil.objects.Reference2ReferenceLinkedOpenHashMap;
import it.unimi.dsi.fastutil.objects.Reference2ReferenceOpenHashMap;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import no2.worldthreader.common.thread.ThreadOwnedObject;
import no2.worldthreader.common.thread.WorldThreadingManager;
import org.edtp.sereniteapot.compat.worldthreader.WorldThreaderGroupAccess;
import org.edtp.sereniteapot.compat.worldthreader.WorldThreaderPotTicking;
import org.edtp.sereniteapot.level.SereniteaPotLevelKeys;
import org.edtp.sereniteapot.model.SereniteaPotDimension;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** Groups every pot level under its vanilla dimension-family worker in WorldThreader 3.1.0. */
@Mixin(value = WorldThreadingManager.class, remap = false)
public abstract class WorldThreaderManagerMixin implements WorldThreaderGroupAccess {
    @Shadow @Final
    private Reference2ReferenceLinkedOpenHashMap<Thread, ResourceKey<Level>> worldThreads;

    @Shadow @Final
    private Reference2ReferenceOpenHashMap<Thread, ThreadOwnedObject[]> worldThreads2OwnedObjects;

    @Unique
    private EnumMap<SereniteaPotDimension, ThreadOwnedObject[]> sereniteapot$groupOwnedObjects;

    @Redirect(
        method = "<init>(Lnet/minecraft/server/MinecraftServer;)V",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/MinecraftServer;getAllLevels()Ljava/lang/Iterable;",
            remap = true
        )
    )
    private Iterable<ServerLevel> sereniteapot$excludePotWorkers(MinecraftServer server) {
        List<ServerLevel> workers = new ArrayList<>();
        for (ServerLevel level : server.getAllLevels()) {
            if (SereniteaPotLevelKeys.identify(level.dimension()) == null) workers.add(level);
        }
        return workers;
    }

    @Inject(method = "<init>(Lnet/minecraft/server/MinecraftServer;)V", at = @At("TAIL"))
    private void sereniteapot$attachPotOwnership(MinecraftServer server, CallbackInfo ci) {
        sereniteapot$groupOwnedObjects = new EnumMap<>(SereniteaPotDimension.class);
        for (SereniteaPotDimension family : SereniteaPotDimension.values()) {
            Thread worker = sereniteapot$workerFor(family.vanillaLevelKey());
            if (worker == null) continue;
            ThreadOwnedObject[] anchorObjects = worldThreads2OwnedObjects.get(worker);
            List<ThreadOwnedObject> grouped = new ArrayList<>(Arrays.asList(anchorObjects));
            for (ServerLevel level : server.getAllLevels()) {
                SereniteaPotLevelKeys.Identity identity = SereniteaPotLevelKeys.identify(level.dimension());
                if (identity == null || identity.dimension() != family) continue;
                grouped.add((ThreadOwnedObject) level);
                grouped.add((ThreadOwnedObject) level.getChunkSource());
            }
            ThreadOwnedObject[] ownedObjects = grouped.toArray(ThreadOwnedObject[]::new);
            worldThreads2OwnedObjects.put(worker, ownedObjects);
            sereniteapot$groupOwnedObjects.put(family, ownedObjects);
        }
    }

    @Inject(
        method = "isWorldThreadOf(Lnet/minecraft/resources/ResourceKey;)Z",
        at = @At("HEAD"),
        cancellable = true
    )
    private void sereniteapot$recognizeDimensionFamily(
        ResourceKey<Level> dimension,
        CallbackInfoReturnable<Boolean> cir
    ) {
        SereniteaPotLevelKeys.Identity identity = SereniteaPotLevelKeys.identify(dimension);
        if (identity == null) return;
        ResourceKey<Level> workerDimension = worldThreads.get(Thread.currentThread());
        cir.setReturnValue(identity.dimension().vanillaLevelKey().equals(workerDimension));
    }

    @Override
    public ThreadOwnedObject[] sereniteapot$ownedObjects(
        ServerLevel anchor,
        ThreadOwnedObject[] fallback
    ) {
        SereniteaPotDimension family = SereniteaPotDimension.fromVanillaLevel(anchor.dimension());
        if (family == null || sereniteapot$groupOwnedObjects == null) return fallback;
        return sereniteapot$groupOwnedObjects.getOrDefault(family, fallback);
    }

    @Unique
    private Thread sereniteapot$workerFor(ResourceKey<Level> dimension) {
        for (Map.Entry<Thread, ResourceKey<Level>> entry : worldThreads.entrySet()) {
            if (dimension.equals(entry.getValue())) return entry.getKey();
        }
        return null;
    }

    @Inject(method = "withinTickBarrier()V", at = @At("HEAD"), cancellable = true)
    private void sereniteapot$skipNestedPotBarrier(CallbackInfo ci) {
        if (WorldThreaderPotTicking.isGroupedPotTick()) ci.cancel();
    }
}
