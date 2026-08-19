package org.edtp.universe.level;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.edtp.universe.model.UniverseDimension;
import org.edtp.universe.model.UniverseRecord;
import org.edtp.universe.model.UniverseSlotRecord;
import org.edtp.universe.player.HumanPlayerDetector;
import org.edtp.universe.player.PlayerStateManager;
import org.edtp.universe.region.UniverseCreationService;

import java.util.Set;
import java.util.UUID;

public final class UniverseTravelService {
    private UniverseTravelService() {
    }

    public static Result enter(ServerPlayer player, UUID owner) {
        UniverseRecord record = UniverseManager.record(owner);
        Double creationProgress = UniverseCreationService.progress(owner);
        if (creationProgress != null && (record == null || !record.exists())) {
            return new Rejected("目标小宇宙正在创建（%.1f%%），完成后才能进入"
                    .formatted(creationProgress * 100.0));
        }
        if (record == null || !record.exists()) {
            return new Rejected("目标玩家还没有创建小宇宙");
        }
        if (!record.isEnabled()) return new Rejected("目标小宇宙已被禁用");
        if (UniverseLifecycleService.isUnavailable(owner)) return new Rejected("目标小宇宙正在关闭或维护");

        UniverseBundle bundle;
        if (player.getUUID().equals(owner)) {
            if (!HumanPlayerDetector.isHuman(player)) {
                return new Rejected("假玩家不能加载小宇宙");
            }
            bundle = UniverseManager.loaded(owner);
            if (bundle == null) {
                try {
                    bundle = UniverseManager.load(owner);
                } catch (RuntimeException error) {
                    return new Rejected("小宇宙加载失败：" + error.getMessage());
                }
            }
        } else {
            if (!UniverseAccessPolicy.isRealOwnerInside(player.level().getServer(), owner)) {
                return new Rejected("只有主人本人在小宇宙内时才能进入");
            }
            bundle = UniverseManager.loaded(owner);
            if (bundle == null) {
                return new Rejected("目标小宇宙尚未加载");
            }
        }

        UniverseDimension destinationDimension = UniverseDimension.fromVanillaLevel(player.level().dimension());
        if (destinationDimension == null || !record.getSlots().containsKey(destinationDimension)) {
            destinationDimension = null;
            for (UniverseDimension candidate : UniverseDimension.values()) {
                if (record.getSlots().containsKey(candidate)) {
                    destinationDimension = candidate;
                    break;
                }
            }
        }
        if (destinationDimension == null) {
            return new Rejected("目标小宇宙还没有可进入的维度");
        }
        Destination destination = savedUniverseDestination(player, owner, record, bundle);
        if (destination == null) {
            UniverseSlotRecord slot = record.getSlots().get(destinationDimension);
            destination = new Destination(
                bundle.get(destinationDimension),
                new Vec3(slot.entryX() + 0.5, slot.entryY(), slot.entryZ() + 0.5),
                player.getYRot(),
                player.getXRot()
            );
        }
        boolean success = player.teleportTo(
            destination.level(),
            destination.position().x,
            destination.position().y,
            destination.position().z,
            Set.of(),
            destination.yaw(),
            destination.pitch(),
            true
        );
        return success ? Success.INSTANCE : new Rejected("传送被访问策略拒绝");
    }

    public static Result leave(ServerPlayer player) {
        UniverseLevelKeys.Identity identity = UniverseLevelKeys.identify(player.level().dimension());
        return identity == null ? new Rejected("你当前不在小宇宙内") : evict(player, identity.owner());
    }

    /** Used only by the lifecycle close transaction. */
    static Result evict(ServerPlayer player, UUID owner) {
        UniverseLevelKeys.Identity identity = UniverseLevelKeys.identify(player.level().dimension());
        if (identity == null) {
            return Success.INSTANCE;
        }
        if (!identity.owner().equals(owner)) {
            return new Rejected("玩家位于另一个小宇宙");
        }

        var server = player.level().getServer();
        Destination savedPublic = savedPublicDestination(player);
        if (savedPublic != null) {
            boolean success = player.teleportTo(
                savedPublic.level(),
                savedPublic.position().x,
                savedPublic.position().y,
                savedPublic.position().z,
                Set.of(),
                savedPublic.yaw(),
                savedPublic.pitch(),
                true
            );
            return success ? Success.INSTANCE : new Rejected("无法离开小宇宙");
        }

        UniverseRecord record = UniverseManager.record(owner);
        UniverseSlotRecord slot = record == null ? null : record.getSlots().get(identity.dimension());
        ServerLevel candidate = null;
        if (slot != null && slot.sourceDimension() != null) {
            try {
                candidate = server.getLevel(ResourceKey.create(
                    Registries.DIMENSION,
                    Identifier.parse(slot.sourceDimension())
                ));
            } catch (RuntimeException ignored) {
                candidate = null;
            }
        }
        ServerLevel target = publicTarget(server.overworld(), candidate);
        Vec3 position;
        if (slot != null && target.dimension().identifier().toString().equals(slot.sourceDimension())) {
            position = new Vec3(slot.entryX() + 0.5, slot.entryY(), slot.entryZ() + 0.5);
        } else {
            position = Vec3.atBottomCenterOf(target.getRespawnData().pos());
        }
        boolean success = player.teleportTo(
            target, position.x, position.y, position.z, Set.of(), player.getYRot(), player.getXRot(), true
        );
        return success ? Success.INSTANCE : new Rejected("无法离开小宇宙");
    }

    private static Destination savedUniverseDestination(
        ServerPlayer player,
        UUID owner,
        UniverseRecord record,
        UniverseBundle bundle
    ) {
        PlayerStateManager.SavedLocation saved = PlayerStateManager.savedLocation(player, owner);
        if (saved == null) return null;
        UniverseLevelKeys.Identity identity = UniverseLevelKeys.identify(saved.dimension());
        if (identity == null || !identity.owner().equals(owner)
            || !record.getSlots().containsKey(identity.dimension())) {
            return null;
        }
        // A saved position belongs to the logical owner/dimension, not to its physical
        // generation. Unchanged dimensions are copied forward; the active border rejects
        // positions removed by a trim or a non-overlapping replacement.
        ServerLevel level = bundle.get(identity.dimension());
        return usable(level, saved)
            ? new Destination(level, new Vec3(saved.x(), saved.y(), saved.z()), saved.yaw(), saved.pitch())
            : null;
    }

    private static Destination savedPublicDestination(ServerPlayer player) {
        PlayerStateManager.SavedLocation saved = PlayerStateManager.savedLocation(player, null);
        if (saved == null || UniverseLevelKeys.identify(saved.dimension()) != null) return null;
        ServerLevel level = player.level().getServer().getLevel(saved.dimension());
        return level != null && usable(level, saved)
            ? new Destination(level, new Vec3(saved.x(), saved.y(), saved.z()), saved.yaw(), saved.pitch())
            : null;
    }

    private static boolean usable(ServerLevel level, PlayerStateManager.SavedLocation saved) {
        return saved.y() >= level.getMinY()
            && saved.y() < level.getMaxY()
            && Float.isFinite(saved.yaw())
            && Float.isFinite(saved.pitch())
            && level.getWorldBorder().isWithinBounds(saved.x(), saved.z());
    }

    private static ServerLevel publicTarget(ServerLevel fallback, ServerLevel candidate) {
        return candidate != null && UniverseLevelKeys.identify(candidate.dimension()) == null ? candidate : fallback;
    }

    private record Destination(ServerLevel level, Vec3 position, float yaw, float pitch) {
    }

    public sealed interface Result permits Success, Rejected {
    }

    public enum Success implements Result {
        INSTANCE
    }

    public record Rejected(String reason) implements Result {
    }
}
