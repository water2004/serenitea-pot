package org.edtp.sereniteapot.mixin;

import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import net.casual.arcade.dimensions.level.vanilla.VanillaLikeLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.block.EndPortalBlock;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.level.storage.LevelData.RespawnData;
import net.minecraft.world.phys.Vec3;
import org.edtp.sereniteapot.level.SereniteaPotLevelKeys;
import org.edtp.sereniteapot.level.SereniteaPotManager;
import org.edtp.sereniteapot.model.SereniteaPotDimension;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Arcade selects the paired dimension; Vanilla still builds the platform and transition. */
@Mixin(EndPortalBlock.class)
public abstract class EndPortalBlockMixin {
    @ModifyExpressionValue(
        method = "getPortalDestination",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;getRespawnData()Lnet/minecraft/world/level/storage/LevelData$RespawnData;")
    )
    private RespawnData sereniteapot$pairedReturnSpawn(RespawnData spawn, ServerLevel source) {
        var origin = SereniteaPotLevelKeys.identify(source.dimension());
        if (origin == null) return spawn;
        // Arcade maps the Level.END constant, but the return dimension is obtained
        // from RespawnData instead. Map that input before Vanilla resolves the level.
        var targetKey = VanillaLikeLevel.getReplacementDestinationFor(source, spawn.dimension());
        var identity = SereniteaPotLevelKeys.identify(targetKey);
        var target = source.getServer().getLevel(targetKey);
        if (identity == null || target == null || !identity.owner().equals(origin.owner())
            || identity.generation() != origin.generation()) return spawn;
        var record = SereniteaPotManager.record(origin.owner());
        var slot = record == null ? null : record.getSlots().get(identity.dimension());
        BlockPos position = slot == null
            ? target.getWorldBorder().clampToBounds(spawn.pos())
            : slot.entryPosition(target.getWorldBorder());
        return RespawnData.of(targetKey, position, spawn.yaw(), spawn.pitch());
    }

    @WrapOperation(
        method = "getPortalDestination",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Entity;adjustSpawnLocation(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/core/BlockPos;)Lnet/minecraft/core/BlockPos;")
    )
    private BlockPos sereniteapot$usePairedSpawnColumn(
        Entity entity, ServerLevel destination, BlockPos spawn, Operation<BlockPos> original
    ) {
        if (SereniteaPotLevelKeys.identify(destination.dimension()) == null) {
            return original.call(entity, destination, spawn);
        }
        // Entity's Vanilla implementation ignores its spawn argument and rereads the
        // global spawn. Use the same heightmap rule, but at the pot-local spawn column.
        return destination.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, spawn);
    }

    @ModifyExpressionValue(
        method = "getPortalDestination",
        at = @At(value = "INVOKE", target = "Lnet/minecraft/world/phys/Vec3;atBottomCenterOf(Lnet/minecraft/core/Vec3i;)Lnet/minecraft/world/phys/Vec3;", ordinal = 0)
    )
    private Vec3 sereniteapot$fitEndPlatform(Vec3 spawn, @Local(ordinal = 1) ServerLevel destination) {
        var identity = SereniteaPotLevelKeys.identify(destination.dimension());
        if (identity == null || identity.dimension() != SereniteaPotDimension.END) return spawn;
        var border = destination.getWorldBorder();
        // Vanilla's End spawn is fixed, unlike Nether portal scaling/clamping. Keep
        // it when possible, but reserve two whole blocks on each side for its 5x5 platform.
        return new Vec3(
            Mth.clamp(spawn.x, Mth.ceil(border.getMinX()) + 2.5, Mth.floor(border.getMaxX()) - 2.5),
            spawn.y,
            Mth.clamp(spawn.z, Mth.ceil(border.getMinZ()) + 2.5, Mth.floor(border.getMaxZ()) - 2.5)
        );
    }

    @Inject(method = "getPortalDestination", at = @At("RETURN"), cancellable = true)
    private void sereniteapot$containEndReturn(
        ServerLevel source, Entity entity, BlockPos portalEntry,
        CallbackInfoReturnable<TeleportTransition> cir
    ) {
        var origin = SereniteaPotLevelKeys.identify(source.dimension());
        TeleportTransition result = cir.getReturnValue();
        if (origin == null || origin.dimension() != SereniteaPotDimension.END || result == null) return;
        var target = SereniteaPotLevelKeys.identify(result.newLevel().dimension());
        if (target == null || !target.owner().equals(origin.owner()) || target.generation() != origin.generation()) {
            cir.setReturnValue(null);
            return;
        }
        cir.setReturnValue(result.withPosition(result.newLevel().getWorldBorder().clampVec3ToBound(result.position())));
    }
}
