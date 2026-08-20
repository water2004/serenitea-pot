package org.edtp.sereniteapot.player;

import net.minecraft.core.GlobalPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
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
import net.minecraft.world.level.storage.TagValueInput;
import net.minecraft.world.level.storage.TagValueOutput;
import org.edtp.sereniteapot.SereniteaPotMod;
import org.edtp.sereniteapot.level.SereniteaPotLevelKeys;
import org.edtp.sereniteapot.level.SereniteaPotLifecycleService;
import org.edtp.sereniteapot.level.SereniteaPotManager;
import org.edtp.sereniteapot.model.SereniteaPotRecord;

import java.util.UUID;

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
        requireServerThread(player.level().getServer());
        UUID sourceRealm = realm(player.level());
        UUID destinationRealm = realm(destination);
        if (java.util.Objects.equals(sourceRealm, destinationRealm)) {
            return null;
        }

        player.closeContainer();
        capture(player, sourceRealm);
        return new StateSwitchPlan(sourceRealm, destinationRealm);
    }

    public static void afterTeleport(ServerPlayer player, StateSwitchPlan plan) {
        requireServerThread(player.level().getServer());
        UUID targetOwner = plan.targetOwner();
        CompoundTag snapshot = store == null ? null : store.get(player.getUUID(), stateKey(targetOwner));
        if (snapshot == null) {
            snapshot = targetOwner == null ? blankPublic(player) : blankSereniteaPot(player);
        }
        apply(player, snapshot, targetOwner != null);
        if (player.getUUID().equals(plan.sourceOwner()) && !player.getUUID().equals(targetOwner)) {
            SereniteaPotLifecycleService.ownerLeft(player.getUUID());
        }
    }

    public static void onDisconnect(ServerPlayer player) {
        if (server == null) {
            return;
        }
        requireServerThread(player.level().getServer());
        UUID realmOwner = realm(player.level());
        capture(player, realmOwner);
        if (realmOwner != null && store != null) {
            CompoundTag publicState = store.get(player.getUUID(), stateKey(null));
            if (publicState != null) {
                apply(player, publicState, false);
            }
        }
        if (player.getUUID().equals(realmOwner)) {
            SereniteaPotLifecycleService.ownerLeft(player.getUUID());
        }
    }

    public static void onJoin(ServerPlayer player) {
        if (server == null) {
            return;
        }
        requireServerThread(player.level().getServer());
        UUID realmOwner = realm(player.level());
        CompoundTag snapshot = store == null ? null : store.get(player.getUUID(), stateKey(realmOwner));
        if (snapshot != null) {
            apply(player, snapshot, realmOwner != null);
        }
    }

    public static SavedLocation savedLocation(ServerPlayer player, UUID realmOwner) {
        requireServerThread(player.level().getServer());
        CompoundTag snapshot = store == null ? null : store.get(player.getUUID(), stateKey(realmOwner));
        if (snapshot == null) return null;
        var input = TagValueInput.create(ProblemReporter.DISCARDING, player.registryAccess(), snapshot);
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

    private static void capture(ServerPlayer player, UUID realmOwner) {
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
        output.store("GameMode", GameType.CODEC, player.gameMode());
        output.storeNullable("Respawn", ServerPlayer.RespawnConfig.CODEC, player.getRespawnConfig());
        player.getLastDeathLocation().ifPresent(value -> output.store("LastDeath", GlobalPos.CODEC, value));
        var effects = output.list("Effects", MobEffectInstance.CODEC);
        for (MobEffectInstance effect : player.getActiveEffects()) {
            effects.add(new MobEffectInstance(effect));
        }
        if (store != null) {
            store.put(player.getUUID(), stateKey(realmOwner), output.buildResult());
        }
    }

    private static void apply(ServerPlayer player, CompoundTag snapshot, boolean forceCreative) {
        var input = TagValueInput.create(ProblemReporter.DISCARDING, player.registryAccess(), snapshot);
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

        GameType storedMode = input.read("GameMode", GameType.CODEC).orElse(GameType.SURVIVAL);
        player.setRespawnPosition(input.read("Respawn", ServerPlayer.RespawnConfig.CODEC).orElse(null), false);
        player.setLastDeathLocation(input.read("LastDeath", GlobalPos.CODEC));
        player.setGameMode(forceCreative ? GameType.CREATIVE : storedMode);
        input.read("Abilities", Abilities.Packed.CODEC).ifPresent(player.getAbilities()::apply);
        if (forceCreative) {
            GameType.CREATIVE.updatePlayerAbilities(player.getAbilities());
        }

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

    private static CompoundTag blankSereniteaPot(ServerPlayer player) {
        TagValueOutput output = blankBase(player);
        Abilities abilities = new Abilities();
        GameType.CREATIVE.updatePlayerAbilities(abilities);
        output.store("Abilities", Abilities.Packed.CODEC, abilities.pack());
        output.store("GameMode", GameType.CODEC, GameType.CREATIVE);
        output.list("Effects", MobEffectInstance.CODEC);
        return output.buildResult();
    }

    private static CompoundTag blankPublic(ServerPlayer player) {
        SereniteaPotMod.LOGGER.warn(
            "No public-state snapshot existed for {}; using a safe empty survival state",
            player.getUUID()
        );
        TagValueOutput output = blankBase(player);
        output.store("Abilities", Abilities.Packed.CODEC, new Abilities().pack());
        output.store("GameMode", GameType.CODEC, GameType.SURVIVAL);
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

    private static String stateKey(UUID owner) {
        if (owner == null) {
            return "public";
        }
        SereniteaPotRecord record = SereniteaPotManager.record(owner);
        UUID stateId = record == null ? owner : record.getStateId();
        return "serenitea_pot_" + owner + "_" + stateId;
    }

    private static void requireServerThread(MinecraftServer currentServer) {
        if (server != currentServer) {
            throw new IllegalStateException("PlayerStateManager is attached to another server");
        }
        if (!currentServer.isSameThread()) {
            throw new IllegalStateException("Player state mutation must run on the server thread");
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
