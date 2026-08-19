package org.edtp.universe.player

import net.minecraft.network.protocol.game.ClientboundSetExperiencePacket
import net.minecraft.network.protocol.game.ClientboundSetHealthPacket
import net.minecraft.server.MinecraftServer
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.util.ProblemReporter
import net.minecraft.world.ItemStackWithSlot
import net.minecraft.world.effect.MobEffectInstance
import net.minecraft.world.entity.player.Abilities
import net.minecraft.world.food.FoodData
import net.minecraft.world.level.GameType
import net.minecraft.core.GlobalPos
import net.minecraft.world.level.storage.LevelResource
import net.minecraft.world.level.storage.TagValueInput
import net.minecraft.world.level.storage.TagValueOutput
import org.edtp.universe.UniverseMod
import org.edtp.universe.level.UniverseLevelKeys
import org.edtp.universe.level.UniverseManager
import org.edtp.universe.level.UniverseLifecycleService
import java.util.UUID
import kotlin.math.min

object PlayerStateManager {
    private var server: MinecraftServer? = null
    private var store: PlayerStateStore? = null

    @JvmStatic
    fun start(server: MinecraftServer) {
        this.server = server
        this.store = PlayerStateStore(
            server.getWorldPath(LevelResource.ROOT)
                .resolve(UniverseMod.MOD_ID)
                .resolve("player_states"),
        )
    }

    @JvmStatic
    fun stop(server: MinecraftServer) {
        if (this.server !== server) {
            return
        }
        store?.flushAndClear()
        store = null
        this.server = null
    }

    @JvmStatic
    fun beforeTeleport(player: ServerPlayer, destination: ServerLevel): StateSwitchPlan? {
        requireServerThread(player.level().server)
        val sourceRealm = realm(player.level())
        val destinationRealm = realm(destination)
        if (sourceRealm == destinationRealm) {
            return null
        }

        player.closeContainer()
        capture(player, sourceRealm)
        return StateSwitchPlan(sourceRealm, destinationRealm)
    }

    @JvmStatic
    fun afterTeleport(player: ServerPlayer, plan: StateSwitchPlan) {
        requireServerThread(player.level().server)
        val key = stateKey(plan.targetOwner)
        val snapshot = store?.get(player.uuid, key)
            ?: if (plan.targetOwner == null) blankPublic(player) else blankUniverse(player)
        apply(player, snapshot, plan.targetOwner != null)
        if (plan.sourceOwner == player.uuid && plan.targetOwner != player.uuid) {
            UniverseLifecycleService.ownerLeft(player.uuid)
        }
    }

    @JvmStatic
    fun onDisconnect(player: ServerPlayer) {
        if (server == null) {
            return
        }
        requireServerThread(player.level().server)
        val realmOwner = realm(player.level())
        capture(player, realmOwner)
        if (realmOwner != null) {
            store?.get(player.uuid, stateKey(null))?.let { apply(player, it, false) }
        }
        if (realmOwner == player.uuid) {
            UniverseLifecycleService.ownerLeft(player.uuid)
        }
    }

    @JvmStatic
    fun onJoin(player: ServerPlayer) {
        if (server == null) {
            return
        }
        requireServerThread(player.level().server)
        val realmOwner = realm(player.level())
        val snapshot = store?.get(player.uuid, stateKey(realmOwner)) ?: return
        apply(player, snapshot, realmOwner != null)
    }

    private fun capture(player: ServerPlayer, realmOwner: UUID?) {
        val output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, player.registryAccess())
        output.putInt("snapshotVersion", SNAPSHOT_VERSION)
        player.inventory.save(output.list("Inventory", ItemStackWithSlot.CODEC))
        output.putInt("SelectedItemSlot", player.inventory.selectedSlot)
        player.enderChestInventory.storeAsSlots(output.list("EnderItems", ItemStackWithSlot.CODEC))
        output.putFloat("XpP", player.experienceProgress)
        output.putInt("XpLevel", player.experienceLevel)
        output.putInt("XpTotal", player.totalExperience)
        output.putFloat("Health", player.health)
        output.putFloat("Absorption", player.absorptionAmount)
        player.foodData.addAdditionalSaveData(output)
        output.store("Abilities", Abilities.Packed.CODEC, player.abilities.pack())
        output.store("GameMode", GameType.CODEC, player.gameMode())
        output.storeNullable("Respawn", ServerPlayer.RespawnConfig.CODEC, player.respawnConfig)
        player.lastDeathLocation.ifPresent { output.store("LastDeath", GlobalPos.CODEC, it) }
        val effects = output.list("Effects", MobEffectInstance.CODEC)
        for (effect in player.activeEffects) {
            effects.add(MobEffectInstance(effect))
        }
        store?.put(player.uuid, stateKey(realmOwner), output.buildResult())
    }

    private fun apply(player: ServerPlayer, snapshot: net.minecraft.nbt.CompoundTag, forceCreative: Boolean) {
        val input = TagValueInput.create(ProblemReporter.DISCARDING, player.registryAccess(), snapshot)
        player.inventory.load(input.listOrEmpty("Inventory", ItemStackWithSlot.CODEC))
        player.inventory.selectedSlot = input.getIntOr("SelectedItemSlot", 0).coerceIn(0, 8)
        player.enderChestInventory.fromSlots(input.listOrEmpty("EnderItems", ItemStackWithSlot.CODEC))
        player.experienceProgress = input.getFloatOr("XpP", 0.0F)
        player.experienceLevel = input.getIntOr("XpLevel", 0)
        player.totalExperience = input.getIntOr("XpTotal", 0)
        player.foodData.readAdditionalSaveData(input)
        player.health = min(input.getFloatOr("Health", player.maxHealth), player.maxHealth)
        player.absorptionAmount = input.getFloatOr("Absorption", 0.0F)

        player.removeAllEffects()
        for (effect in input.listOrEmpty("Effects", MobEffectInstance.CODEC)) {
            player.addEffect(MobEffectInstance(effect))
        }

        val storedMode = input.read("GameMode", GameType.CODEC).orElse(GameType.SURVIVAL)
        player.setRespawnPosition(input.read("Respawn", ServerPlayer.RespawnConfig.CODEC).orElse(null), false)
        player.lastDeathLocation = input.read("LastDeath", GlobalPos.CODEC)
        player.setGameMode(if (forceCreative) GameType.CREATIVE else storedMode)
        input.read("Abilities", Abilities.Packed.CODEC).ifPresent(player.abilities::apply)
        if (forceCreative) {
            GameType.CREATIVE.updatePlayerAbilities(player.abilities)
        }

        player.inventoryMenu.broadcastFullState()
        player.connection.send(
            ClientboundSetExperiencePacket(
                player.experienceProgress,
                player.totalExperience,
                player.experienceLevel,
            ),
        )
        player.connection.send(
            ClientboundSetHealthPacket(
                player.health,
                player.foodData.foodLevel,
                player.foodData.saturationLevel,
            ),
        )
        player.onUpdateAbilities()
        player.level().server.playerList.sendActivePlayerEffects(player)
    }

    private fun blankUniverse(player: ServerPlayer): net.minecraft.nbt.CompoundTag {
        val output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, player.registryAccess())
        output.putInt("snapshotVersion", SNAPSHOT_VERSION)
        output.list("Inventory", ItemStackWithSlot.CODEC)
        output.putInt("SelectedItemSlot", 0)
        output.list("EnderItems", ItemStackWithSlot.CODEC)
        output.putFloat("XpP", 0.0F)
        output.putInt("XpLevel", 0)
        output.putInt("XpTotal", 0)
        output.putFloat("Health", player.maxHealth)
        output.putFloat("Absorption", 0.0F)
        FoodData().addAdditionalSaveData(output)
        val abilities = Abilities()
        GameType.CREATIVE.updatePlayerAbilities(abilities)
        output.store("Abilities", Abilities.Packed.CODEC, abilities.pack())
        output.store("GameMode", GameType.CODEC, GameType.CREATIVE)
        output.list("Effects", MobEffectInstance.CODEC)
        return output.buildResult()
    }

    private fun blankPublic(player: ServerPlayer): net.minecraft.nbt.CompoundTag {
        UniverseMod.logger.warn("No public-state snapshot existed for {}; using a safe empty survival state", player.uuid)
        val output = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, player.registryAccess())
        output.putInt("snapshotVersion", SNAPSHOT_VERSION)
        output.list("Inventory", ItemStackWithSlot.CODEC)
        output.putInt("SelectedItemSlot", 0)
        output.list("EnderItems", ItemStackWithSlot.CODEC)
        output.putFloat("XpP", 0.0F)
        output.putInt("XpLevel", 0)
        output.putInt("XpTotal", 0)
        output.putFloat("Health", player.maxHealth)
        output.putFloat("Absorption", 0.0F)
        FoodData().addAdditionalSaveData(output)
        output.store("Abilities", Abilities.Packed.CODEC, Abilities().pack())
        output.store("GameMode", GameType.CODEC, GameType.SURVIVAL)
        output.list("Effects", MobEffectInstance.CODEC)
        return output.buildResult()
    }

    private fun realm(level: ServerLevel): UUID? = UniverseLevelKeys.identify(level.dimension())?.owner

    private fun stateKey(owner: UUID?): String {
        if (owner == null) {
            return "public"
        }
        val stateId = UniverseManager.record(owner)?.stateId ?: owner
        return "universe_${owner}_$stateId"
    }

    private fun requireServerThread(server: MinecraftServer) {
        check(this.server === server) { "PlayerStateManager is attached to another server" }
        check(server.isSameThread) { "Player state mutation must run on the server thread" }
    }

    data class StateSwitchPlan(val sourceOwner: UUID?, val targetOwner: UUID?)

    private const val SNAPSHOT_VERSION = 1
}
