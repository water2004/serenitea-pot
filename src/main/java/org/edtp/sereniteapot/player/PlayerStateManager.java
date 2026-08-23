package org.edtp.sereniteapot.player;

import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.game.ClientboundGameEventPacket;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.network.protocol.game.ClientboundSetExperiencePacket;
import net.minecraft.network.protocol.game.ClientboundSetHealthPacket;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.ProblemReporter;
import net.minecraft.world.ItemStackWithSlot;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.food.FoodData;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.PlayerDataStorage;
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.edtp.sereniteapot.SereniteaPotMod;
import org.edtp.sereniteapot.level.SereniteaPotLevelKeys;
import org.edtp.sereniteapot.level.SereniteaPotLifecycleService;
import org.edtp.sereniteapot.level.SereniteaPotManager;
import org.edtp.sereniteapot.mixin.accessor.PlayerListAccessor;
import org.edtp.sereniteapot.model.SereniteaPotRecord;

import java.util.Objects;
import java.util.UUID;

/**
 * 在原版公共玩家数据与每个尘歌壶的私有玩家数据之间切换玩家状态。
 *
 * <p>公共状态始终由原版 playerdata 负责，模组只保存尘歌壶状态。尘歌壶状态包含
 * 物品栏、末影箱、经验、生命、效果、游戏模式和所在位置。
 * 跨 realm 传送分为 {@link #beforeTeleport(ServerPlayer, ServerLevel)} 的保存阶段与
 * {@link #afterTeleport(ServerPlayer, StateSwitchPlan)} 的恢复阶段；同一尘歌壶三个维度
 * 之间传送不会切换状态。</p>
 */
public final class PlayerStateManager {
    private static final int SNAPSHOT_VERSION = 3;

    private static MinecraftServer server;
    private static PlayerStateStore store;

    private PlayerStateManager() {
    }

    public static void start(MinecraftServer server) {
        PlayerStateManager.server = server;
        store = new PlayerStateStore(
            server.getWorldPath(LevelResource.ROOT).resolve(SereniteaPotMod.MOD_ID).resolve("player_states")
        );
    }

    public static void stop(MinecraftServer server) {
        if (PlayerStateManager.server != server) {
            return;
        }
        if (store != null) {
            store.clear();
        }
        store = null;
        PlayerStateManager.server = null;
    }

    public static StateSwitchPlan beforeTeleport(ServerPlayer player, ServerLevel destination) {
        requireAttached(player.level().getServer());
        UUID sourceRealm = realm(player.level());
        UUID destinationRealm = realm(destination);
        if (java.util.Objects.equals(sourceRealm, destinationRealm)) {
            return null;
        }

        // 先关闭容器，避免跨 realm 时仍有公共世界容器菜单引用或未提交的物品操作。
        player.closeContainer();
        if (sourceRealm == null) {
            // 原版 playerdata 是公共状态的唯一权威来源。
            playerDataStorage(player).save(player);
        } else {
            savePotState(player, sourceRealm);
        }
        return new StateSwitchPlan(sourceRealm, destinationRealm);
    }

    public static void afterTeleport(ServerPlayer player, StateSwitchPlan plan) {
        requireAttached(player.level().getServer());
        UUID targetOwner = plan.targetOwner();
        if (targetOwner == null) {
            restorePublicState(player);
        } else {
            CompoundTag state = store == null ? null : store.get(player.getUUID(), potStateKey(targetOwner));
            applyPotState(player, state == null ? blankPotState(player) : state);
        }
        if (player.getUUID().equals(plan.sourceOwner()) && !player.getUUID().equals(targetOwner)) {
            requestCloseOnServerThread(player);
        }
    }

    public static void onDisconnect(ServerPlayer player) {
        if (server == null) {
            return;
        }
        requireAttached(player.level().getServer());
        UUID realmOwner = realm(player.level());
        if (player.getUUID().equals(realmOwner)) {
            requestCloseOnServerThread(player);
        }
    }

    /** Saves private pot state and tells Vanilla not to overwrite public playerdata. */
    public static boolean saveIsolatedStateIfInsidePot(ServerPlayer player) {
        if (server == null) {
            return false;
        }
        requireAttached(player.level().getServer());
        UUID realmOwner = realm(player.level());
        if (realmOwner == null) {
            return false;
        }
        savePotState(player, realmOwner);
        return true;
    }

    public static SavedLocation savedPotLocation(ServerPlayer player, UUID owner) {
        requireServerThread(player.level().getServer());
        CompoundTag state = store == null ? null : store.get(player.getUUID(), potStateKey(owner));
        return readLocation(player, state);
    }

    public static SavedLocation savedPublicLocation(ServerPlayer player) {
        requireServerThread(player.level().getServer());
        CompoundTag state = loadPublicState(player);
        if (state == null) return null;
        var input = TagValueInput.create(ProblemReporter.DISCARDING, player.registryAccess(), state);
        ResourceKey<Level> dimension = input.read("Dimension", Level.RESOURCE_KEY_CODEC).orElse(null);
        Vec3 position = input.read("Pos", Vec3.CODEC).orElse(null);
        if (dimension == null || position == null) return null;
        Vec2 rotation = input.read("Rotation", Vec2.CODEC).orElse(Vec2.ZERO);
        return new SavedLocation(
            dimension,
            position.x,
            position.y,
            position.z,
            rotation.x,
            rotation.y
        );
    }

    private static SavedLocation readLocation(ServerPlayer player, CompoundTag state) {
        if (state == null) return null;
        var input = TagValueInput.create(ProblemReporter.DISCARDING, player.registryAccess(), state);
        if (input.getIntOr("snapshotVersion", 0) != SNAPSHOT_VERSION) return null;
        Identifier dimensionId = Identifier.tryParse(input.getStringOr("LocationDimension", ""));
        if (dimensionId == null) return null;
        double x = input.getDoubleOr("LocationX", Double.NaN);
        double y = input.getDoubleOr("LocationY", Double.NaN);
        double z = input.getDoubleOr("LocationZ", Double.NaN);
        if (!Double.isFinite(x) || !Double.isFinite(y) || !Double.isFinite(z)) return null;
        return new SavedLocation(
            ResourceKey.create(Registries.DIMENSION, dimensionId),
            x,
            y,
            z,
            input.getFloatOr("LocationYaw", 0.0F),
            input.getFloatOr("LocationPitch", 0.0F)
        );
    }

    private static void savePotState(ServerPlayer player, UUID owner) {
        TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, player.registryAccess());
        output.putInt("snapshotVersion", SNAPSHOT_VERSION);
        output.putString("LocationDimension", player.level().dimension().identifier().toString());
        output.putDouble("LocationX", player.getX());
        output.putDouble("LocationY", player.getY());
        output.putDouble("LocationZ", player.getZ());
        output.putFloat("LocationYaw", player.getYRot());
        output.putFloat("LocationPitch", player.getXRot());
        player.getInventory().save(output.list("Inventory", ItemStackWithSlot.CODEC));
        output.putInt("SelectedItemSlot", player.getInventory().getSelectedSlot());
        player.getEnderChestInventory().storeAsSlots(output.list("EnderItems", ItemStackWithSlot.CODEC));
        output.putFloat("XpP", player.experienceProgress);
        output.putInt("XpLevel", player.experienceLevel);
        output.putInt("XpTotal", player.totalExperience);
        output.putFloat("Health", player.getHealth());
        output.putFloat("Absorption", player.getAbsorptionAmount());
        player.getFoodData().addAdditionalSaveData(output);
        output.store("Abilities", Abilities.Packed.CODEC, player.getAbilities().pack());
        output.storeNullable("Respawn", ServerPlayer.RespawnConfig.CODEC, player.getRespawnConfig());
        player.getLastDeathLocation().ifPresent(value -> output.store("LastDeath", GlobalPos.CODEC, value));
        var effects = output.list("Effects", MobEffectInstance.CODEC);
        for (MobEffectInstance effect : player.getActiveEffects()) {
            effects.add(new MobEffectInstance(effect));
        }
        if (store != null) {
            store.put(player.getUUID(), potStateKey(owner), output.buildResult());
        }
    }

    private static void applyPotState(ServerPlayer player, CompoundTag state) {
        var input = TagValueInput.create(ProblemReporter.DISCARDING, player.registryAccess(), state);
        player.getInventory().load(input.listOrEmpty("Inventory", ItemStackWithSlot.CODEC));
        player.getInventory().setSelectedSlot(Math.max(0, Math.min(8, input.getIntOr("SelectedItemSlot", 0))));
        player.getEnderChestInventory().fromSlots(input.listOrEmpty("EnderItems", ItemStackWithSlot.CODEC));
        player.experienceProgress = input.getFloatOr("XpP", 0.0F);
        player.experienceLevel = input.getIntOr("XpLevel", 0);
        player.totalExperience = input.getIntOr("XpTotal", 0);
        player.getFoodData().readAdditionalSaveData(input);
        player.setHealth(Math.min(input.getFloatOr("Health", player.getMaxHealth()), player.getMaxHealth()));
        player.setAbsorptionAmount(input.getFloatOr("Absorption", 0.0F));

        player.removeAllEffects();
        for (MobEffectInstance effect : input.listOrEmpty("Effects", MobEffectInstance.CODEC)) {
            player.addEffect(new MobEffectInstance(effect));
        }

        player.setRespawnPosition(input.read("Respawn", ServerPlayer.RespawnConfig.CODEC).orElse(null), false);
        player.setLastDeathLocation(input.read("LastDeath", GlobalPos.CODEC));
        player.setGameMode(GameType.CREATIVE);
        input.read("Abilities", Abilities.Packed.CODEC).ifPresent(player.getAbilities()::apply);
        GameType.CREATIVE.updatePlayerAbilities(player.getAbilities());

        syncClientState(player);
    }

    private static void restorePublicState(ServerPlayer player) {
        CompoundTag state = loadPublicState(player);
        if (state == null) {
            throw new IllegalStateException("Vanilla playerdata is missing for " + player.getUUID());
        }
        Vec3 destination = player.position();
        float destinationYaw = player.getYRot();
        float destinationPitch = player.getXRot();
        player.load(TagValueInput.create(ProblemReporter.DISCARDING, player.registryAccess(), state));
        // The teleport service has already validated the destination. Keep its safe
        // fallback when an old playerdata position is outside the current world border.
        player.snapTo(destination.x, destination.y, destination.z, destinationYaw, destinationPitch);
        player.connection.send(new ClientboundGameEventPacket(
            ClientboundGameEventPacket.CHANGE_GAME_MODE,
            player.gameMode().getId()
        ));
        player.level().getServer().getPlayerList().broadcastAll(new ClientboundPlayerInfoUpdatePacket(
            ClientboundPlayerInfoUpdatePacket.Action.UPDATE_GAME_MODE,
            player
        ));
        syncClientState(player);
    }

    private static void syncClientState(ServerPlayer player) {
        player.inventoryMenu.broadcastFullState();
        player.connection.send(new ClientboundSetExperiencePacket(
            player.experienceProgress,
            player.totalExperience,
            player.experienceLevel
        ));
        player.connection.send(new ClientboundSetHealthPacket(
            player.getHealth(),
            player.getFoodData().getFoodLevel(),
            player.getFoodData().getSaturationLevel()
        ));
        player.onUpdateAbilities();
        player.level().getServer().getPlayerList().sendActivePlayerEffects(player);
    }

    private static CompoundTag blankPotState(ServerPlayer player) {
        TagValueOutput output = blankBase(player);
        Abilities abilities = new Abilities();
        GameType.CREATIVE.updatePlayerAbilities(abilities);
        output.store("Abilities", Abilities.Packed.CODEC, abilities.pack());
        output.list("Effects", MobEffectInstance.CODEC);
        return output.buildResult();
    }

    private static TagValueOutput blankBase(ServerPlayer player) {
        TagValueOutput output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, player.registryAccess());
        output.putInt("snapshotVersion", SNAPSHOT_VERSION);
        output.list("Inventory", ItemStackWithSlot.CODEC);
        output.putInt("SelectedItemSlot", 0);
        output.list("EnderItems", ItemStackWithSlot.CODEC);
        output.putFloat("XpP", 0.0F);
        output.putInt("XpLevel", 0);
        output.putInt("XpTotal", 0);
        output.putFloat("Health", player.getMaxHealth());
        output.putFloat("Absorption", 0.0F);
        new FoodData().addAdditionalSaveData(output);
        return output;
    }

    private static UUID realm(ServerLevel level) {
        SereniteaPotLevelKeys.Identity identity = SereniteaPotLevelKeys.identify(level.dimension());
        return identity == null ? null : identity.owner();
    }

    private static String potStateKey(UUID owner) {
        Objects.requireNonNull(owner, "owner");
        // stateId 在删除尘歌壶时重置，使旧壶的玩家状态自然失效，无需读取或迁移旧快照。
        SereniteaPotRecord record = SereniteaPotManager.record(owner);
        UUID stateId = record == null ? owner : record.getStateId();
        return "serenitea_pot_" + owner + "_" + stateId;
    }

    private static CompoundTag loadPublicState(ServerPlayer player) {
        return playerDataStorage(player)
            .load(player.nameAndId())
            .orElse(null);
    }

    private static PlayerDataStorage playerDataStorage(ServerPlayer player) {
        return ((PlayerListAccessor) player.level().getServer().getPlayerList())
            .sereniteapot$getPlayerDataStorage();
    }

    private static void requireServerThread(MinecraftServer currentServer) {
        requireAttached(currentServer);
        if (!currentServer.isSameThread()) {
            throw new IllegalStateException("Player state mutation must run on the server thread");
        }
    }

    private static void requireAttached(MinecraftServer currentServer) {
        if (server != currentServer) {
            throw new IllegalStateException("PlayerStateManager is attached to another server");
        }
    }

    private static void requestCloseOnServerThread(ServerPlayer player) {
        MinecraftServer currentServer = player.level().getServer();
        UUID owner = player.getUUID();
        if (currentServer.isSameThread()) {
            SereniteaPotLifecycleService.requestClose(owner);
        } else {
            // Worldthreader completes the destination-world transfer before the
            // server thread processes this lifecycle request after its tick barrier.
            currentServer.execute(() -> SereniteaPotLifecycleService.requestClose(owner));
        }
    }

    public record StateSwitchPlan(UUID sourceOwner, UUID targetOwner) {
    }

    public record SavedLocation(
        ResourceKey<Level> dimension,
        double x,
        double y,
        double z,
        float yaw,
        float pitch
    ) {
    }
}
