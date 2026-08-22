package org.edtp.sereniteapot.mixin.compat.worldthreader;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import no2.worldthreader.common.ServerWorldTicking;
import no2.worldthreader.common.thread.ThreadHelper;
import no2.worldthreader.common.thread.ThreadOwnedObject;
import no2.worldthreader.common.thread.WorldThreadingManager;
import org.edtp.sereniteapot.compat.worldthreader.WorldThreaderGroupAccess;
import org.edtp.sereniteapot.compat.worldthreader.WorldThreaderPotTicking;
import org.edtp.sereniteapot.model.SereniteaPotDimension;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import java.util.Collection;
import java.util.function.BooleanSupplier;

/** Inserts grouped pot work into the five WorldThreader 3.1.0 world phases. */
@Mixin(value = ServerWorldTicking.class, remap = false)
public abstract class WorldThreaderServerWorldTickingMixin {
    @WrapOperation(
        method = "runWorldThread",
        at = @At(
            value = "INVOKE",
            target = "Lno2/worldthreader/common/thread/ThreadHelper;swapOnMultithreadTickStart"
                + "(Ljava/lang/Thread;Ljava/lang/Thread;[Lno2/worldthreader/common/thread/ThreadOwnedObject;)V"
        )
    )
    private static void sereniteapot$acquireWholeFamily(
        Thread mainThread,
        Thread worldThread,
        ThreadOwnedObject[] originalObjects,
        Operation<Void> original,
        @Local(argsOnly = true) WorldThreadingManager manager,
        @Local(argsOnly = true) ServerLevel anchor
    ) {
        ThreadOwnedObject[] grouped = ((WorldThreaderGroupAccess) manager)
            .sereniteapot$ownedObjects(anchor, originalObjects);
        original.call(mainThread, worldThread, grouped);
    }

    @WrapOperation(
        method = "runWorldThread",
        at = @At(
            value = "INVOKE",
            target = "Lno2/worldthreader/common/thread/ThreadHelper;swapOnMultithreadTickEnd"
                + "(Ljava/lang/Thread;Ljava/lang/Thread;[Lno2/worldthreader/common/thread/ThreadOwnedObject;)V"
        )
    )
    private static void sereniteapot$releaseWholeFamily(
        Thread mainThread,
        Thread worldThread,
        ThreadOwnedObject[] originalObjects,
        Operation<Void> original,
        @Local(argsOnly = true) WorldThreadingManager manager,
        @Local(argsOnly = true) ServerLevel anchor
    ) {
        ThreadOwnedObject[] grouped = ((WorldThreaderGroupAccess) manager)
            .sereniteapot$ownedObjects(anchor, originalObjects);
        original.call(mainThread, worldThread, grouped);
    }

    @WrapOperation(
        method = "tickThreaded",
        at = @At(
            value = "INVOKE",
            target = "Lnet/minecraft/server/level/ServerLevel;tick(Ljava/util/function/BooleanSupplier;)V",
            remap = true
        )
    )
    private static void sereniteapot$tickFamilyWorlds(
        ServerLevel anchor,
        BooleanSupplier shouldKeepTicking,
        Operation<Void> original,
        @Local(argsOnly = true) MinecraftServer server
    ) {
        original.call(anchor, shouldKeepTicking);
        SereniteaPotDimension family = SereniteaPotDimension.fromVanillaLevel(anchor.dimension());
        if (family != null) WorldThreaderPotTicking.tickWorldPhase(server, family, shouldKeepTicking);
    }

    @WrapOperation(
        method = "tickThreaded",
        at = @At(
            value = "INVOKE",
            target = "Lno2/worldthreader/common/ServerWorldTicking;finishTeleportsToWorld"
                + "(Lnet/minecraft/server/level/ServerLevel;)Ljava/util/Collection;"
        )
    )
    private static Collection<Entity> sereniteapot$receiveFamilyTeleports(
        ServerLevel anchor,
        Operation<Collection<Entity>> original,
        @Local(argsOnly = true) MinecraftServer server
    ) {
        Collection<Entity> vanillaArrivals = original.call(anchor);
        SereniteaPotDimension family = SereniteaPotDimension.fromVanillaLevel(anchor.dimension());
        if (family != null) WorldThreaderPotTicking.receiveTeleports(server, family);
        return vanillaArrivals;
    }

    @WrapOperation(
        method = "tickThreaded",
        at = @At(
            value = "INVOKE",
            target = "Lno2/worldthreader/common/thread/WorldThreadingManager;withinTickBarrier()V",
            ordinal = 3
        )
    )
    private static void sereniteapot$tickFamilyArrivals(
        WorldThreadingManager manager,
        Operation<Void> original,
        @Local(argsOnly = true) MinecraftServer server,
        @Local(argsOnly = true) ServerLevel anchor
    ) {
        SereniteaPotDimension family = SereniteaPotDimension.fromVanillaLevel(anchor.dimension());
        if (family != null) WorldThreaderPotTicking.tickArrivals(server, family);
        original.call(manager);
    }

    @WrapOperation(
        method = "tickThreaded",
        at = @At(
            value = "INVOKE",
            target = "Lno2/worldthreader/common/ServerWorldTicking;recoverFailedTeleports"
                + "(Lnet/minecraft/server/level/ServerLevel;)V"
        )
    )
    private static void sereniteapot$recoverFamilyTeleports(
        ServerLevel anchor,
        Operation<Void> original,
        @Local(argsOnly = true) MinecraftServer server
    ) {
        original.call(anchor);
        SereniteaPotDimension family = SereniteaPotDimension.fromVanillaLevel(anchor.dimension());
        if (family != null) WorldThreaderPotTicking.recoverTeleports(server, family);
    }

    @WrapOperation(
        method = "tickThreaded",
        at = @At(
            value = "INVOKE",
            target = "Lno2/worldthreader/common/thread/WorldThreadingManager;withinTickBarrier()V",
            ordinal = 4
        )
    )
    private static void sereniteapot$finishFamilyTick(
        WorldThreadingManager manager,
        Operation<Void> original,
        @Local(argsOnly = true) MinecraftServer server,
        @Local(argsOnly = true) ServerLevel anchor
    ) {
        original.call(manager);
        SereniteaPotDimension family = SereniteaPotDimension.fromVanillaLevel(anchor.dimension());
        if (family != null) WorldThreaderPotTicking.finishTick(server, family);
    }
}
