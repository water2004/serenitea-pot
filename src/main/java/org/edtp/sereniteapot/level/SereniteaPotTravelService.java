package org.edtp.sereniteapot.level;

import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.portal.TeleportTransition;
import net.minecraft.world.phys.Vec3;
import org.edtp.sereniteapot.i18n.MessageKey;
import org.edtp.sereniteapot.model.SereniteaPotDimension;
import org.edtp.sereniteapot.model.SereniteaPotRecord;
import org.edtp.sereniteapot.model.SereniteaPotSlotRecord;
import org.edtp.sereniteapot.i18n.SereniteaPotTranslations.Message;
import org.edtp.sereniteapot.player.HumanPlayerDetector;
import org.edtp.sereniteapot.player.PlayerStateManager;
import org.edtp.sereniteapot.region.SereniteaPotCreationService;

import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import static org.edtp.sereniteapot.i18n.SereniteaPotTranslations.message;

/**
 * 玩家进入、离开和生命周期驱逐共用的传送入口。
 *
 * <p>这里只选择安全目的地；访问控制由 teleport Mixin 统一执行，玩家物品和坐标的
 * realm 切换也由同一次 teleport 的前后钩子完成。</p>
 */
public final class SereniteaPotTravelService {
    private SereniteaPotTravelService() {
    }

    public static Result enter(ServerPlayer player, UUID owner) {
        SereniteaPotRecord record = SereniteaPotManager.record(owner);
        Double creationProgress = SereniteaPotCreationService.progress(owner);
        if (creationProgress != null && (record == null || !record.exists())) {
            return new Rejected(message(
                MessageKey.TRAVEL_CREATING,
                String.format(Locale.ROOT, "%.1f", creationProgress * 100.0)
            ));
        }
        if (record == null || !record.exists()) {
            return new Rejected(message(MessageKey.TRAVEL_TARGET_NO_POT));
        }
        if (!record.isEnabled()) return new Rejected(message(MessageKey.TRAVEL_DISABLED));
        if (SereniteaPotLifecycleService.isUnavailable(owner)) {
            return new Rejected(message(MessageKey.TRAVEL_UNAVAILABLE));
        }

        SereniteaPotBundle bundle;
        if (player.getUUID().equals(owner)) {
            if (!HumanPlayerDetector.isHuman(player)) {
                return new Rejected(message(MessageKey.TRAVEL_FAKE_PLAYER));
            }
            bundle = SereniteaPotManager.loaded(owner);
            if (bundle == null) {
                try {
                    bundle = SereniteaPotManager.load(owner);
                } catch (RuntimeException error) {
                    return new Rejected(message(MessageKey.TRAVEL_LOAD_FAILED, error.getMessage()));
                }
            }
        } else {
            if (!SereniteaPotAccessPolicy.isRealOwnerInside(player.level().getServer(), owner)) {
                return new Rejected(message(MessageKey.TRAVEL_OWNER_REQUIRED));
            }
            bundle = SereniteaPotManager.loaded(owner);
            if (bundle == null) {
                return new Rejected(message(MessageKey.TRAVEL_NOT_LOADED));
            }
        }

        // 优先进入与玩家当前公共维度对应的壶内维度；尚未提取该维度时再选择已有槽位。
        SereniteaPotDimension destinationDimension = SereniteaPotDimension.fromVanillaLevel(player.level().dimension());
        if (destinationDimension == null || !record.getSlots().containsKey(destinationDimension)) {
            destinationDimension = null;
            for (SereniteaPotDimension candidate : SereniteaPotDimension.values()) {
                if (record.getSlots().containsKey(candidate)) {
                    destinationDimension = candidate;
                    break;
                }
            }
        }
        if (destinationDimension == null) {
            return new Rejected(message(MessageKey.TRAVEL_NO_DIMENSION));
        }
        Destination destination = savedSereniteaPotDestination(player, owner, record, bundle);
        if (destination == null) {
            SereniteaPotSlotRecord slot = record.getSlots().get(destinationDimension);
            destination = new Destination(
                bundle.get(destinationDimension),
                new Vec3(slot.localEntryX() + 0.5, slot.localEntryY(), slot.localEntryZ() + 0.5),
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
        return success ? Success.INSTANCE : new Rejected(message(MessageKey.TRAVEL_DENIED));
    }

    public static Result leave(ServerPlayer player) {
        SereniteaPotLevelKeys.Identity identity = SereniteaPotLevelKeys.identify(player.level().dimension());
        return identity == null
            ? new Rejected(message(MessageKey.TRAVEL_NOT_INSIDE))
            : evict(player, identity.owner());
    }

    /**
     * Keeps Vanilla's death-respawn path inside the current Serenitea Pot realm.
     * A valid bed or respawn anchor in any dimension of the same pot is preserved;
     * Vanilla's public-world fallback is replaced with a local entry in the same pot.
     * If the current dimension has never been extracted, the first extracted pot
     * dimension becomes the fallback instead.
     */
    public static TeleportTransition containRespawn(
        ServerPlayer player,
        TeleportTransition vanillaDestination
    ) {
        SereniteaPotLevelKeys.Identity source = SereniteaPotLevelKeys.identify(player.level().dimension());
        if (source == null) return vanillaDestination;

        SereniteaPotLevelKeys.Identity target = SereniteaPotLevelKeys.identify(
            vanillaDestination.newLevel().dimension()
        );
        if (target != null
            && target.owner().equals(source.owner())
            && target.generation() == source.generation()) {
            return vanillaDestination;
        }

        SereniteaPotRecord record = SereniteaPotManager.record(source.owner());
        SereniteaPotSlotRecord slot = record == null ? null : record.getSlots().get(source.dimension());
        ServerLevel destinationLevel = player.level();
        if (slot == null) {
            SereniteaPotBundle bundle = SereniteaPotManager.loaded(source.owner());
            if (record != null && bundle != null && bundle.generation() == source.generation()) {
                for (SereniteaPotDimension dimension : SereniteaPotDimension.values()) {
                    SereniteaPotSlotRecord candidate = record.getSlots().get(dimension);
                    if (candidate != null) {
                        slot = candidate;
                        destinationLevel = bundle.get(dimension);
                        break;
                    }
                }
            }
        }
        if (slot == null) {
            throw new IllegalStateException("Occupied Serenitea Pot has no extracted dimension");
        }

        return new TeleportTransition(
            destinationLevel,
            new Vec3(slot.localEntryX() + 0.5, slot.localEntryY(), slot.localEntryZ() + 0.5),
            Vec3.ZERO,
            player.getYRot(),
            player.getXRot(),
            vanillaDestination.missingRespawnBlock(),
            false,
            Set.of(),
            vanillaDestination.postTeleportTransition()
        );
    }

    /** Used only by the lifecycle close transaction. */
    static Result evict(ServerPlayer player, UUID owner) {
        SereniteaPotLevelKeys.Identity identity = SereniteaPotLevelKeys.identify(player.level().dimension());
        if (identity == null) {
            return Success.INSTANCE;
        }
        if (!identity.owner().equals(owner)) {
            return new Rejected(message(MessageKey.TRAVEL_OTHER_POT));
        }

        var server = player.level().getServer();
        // 正常离开应精确回到进入前的位置；下方逻辑只处理旧/损坏快照的安全回退。
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
            return success ? Success.INSTANCE : new Rejected(message(MessageKey.TRAVEL_LEAVE_FAILED));
        }

        SereniteaPotRecord record = SereniteaPotManager.record(owner);
        SereniteaPotSlotRecord slot = record == null ? null : record.getSlots().get(identity.dimension());
        ServerLevel candidate = null;
        if (slot != null) {
            Identifier sourceDimension = Identifier.tryParse(slot.sourceDimension());
            if (sourceDimension != null) {
                candidate = server.getLevel(ResourceKey.create(
                    Registries.DIMENSION,
                    sourceDimension
                ));
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
        return success ? Success.INSTANCE : new Rejected(message(MessageKey.TRAVEL_LEAVE_FAILED));
    }

    private static Destination savedSereniteaPotDestination(
        ServerPlayer player,
        UUID owner,
        SereniteaPotRecord record,
        SereniteaPotBundle bundle
    ) {
        PlayerStateManager.SavedLocation saved = PlayerStateManager.savedPotLocation(player, owner);
        if (saved == null) return null;
        SereniteaPotLevelKeys.Identity identity = SereniteaPotLevelKeys.identify(saved.dimension());
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
        PlayerStateManager.SavedLocation saved = PlayerStateManager.savedPublicLocation(player);
        if (saved == null || SereniteaPotLevelKeys.identify(saved.dimension()) != null) return null;
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
        return candidate != null && SereniteaPotLevelKeys.identify(candidate.dimension()) == null ? candidate : fallback;
    }

    private record Destination(ServerLevel level, Vec3 position, float yaw, float pitch) {
    }

    public sealed interface Result permits Success, Rejected {
    }

    public enum Success implements Result {
        INSTANCE
    }

    public record Rejected(Message reason) implements Result {
    }
}
