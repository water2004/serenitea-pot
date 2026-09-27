package org.edtp.sereniteapot.gametest;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.storage.LevelResource;
import org.edtp.sereniteapot.level.SereniteaPotBundle;
import org.edtp.sereniteapot.level.SereniteaPotDeletionService;
import org.edtp.sereniteapot.level.SereniteaPotManager;
import org.edtp.sereniteapot.level.SereniteaPotTravelService;
import org.edtp.sereniteapot.mixin.accessor.PlayerListAccessor;
import org.edtp.sereniteapot.model.SereniteaPotDimension;
import org.edtp.sereniteapot.model.SereniteaPotSlotRecord;
import org.edtp.sereniteapot.SereniteaPotMod;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/** Unsupported saved formats must stop a realm switch before it changes either player state. */
public final class PlayerStateVersionGameTest {
    @GameTest(maxTicks = 200)
    public void unsupportedPlayerFileSchemaRejectsTeleport(GameTestHelper helper) {
        rejectsUnsupportedTargetState(helper, true);
    }

    @GameTest(maxTicks = 200)
    public void unsupportedPotSnapshotRejectsTeleport(GameTestHelper helper) {
        rejectsUnsupportedTargetState(helper, false);
    }

    @SuppressWarnings("removal")
    private static void rejectsUnsupportedTargetState(GameTestHelper helper, boolean badSchema) {
        MinecraftServer server = helper.getLevel().getServer();
        ServerPlayer initialPlayer = helper.makeMockServerPlayerInLevel();
        UUID owner = initialPlayer.getUUID();
        AtomicInteger phase = new AtomicInteger();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<Fixture> fixture = new AtomicReference<>();
        AtomicReference<Path> stateFile = new AtomicReference<>();

        server.execute(() -> {
            try {
                initialPlayer.setGameMode(GameType.SURVIVAL);
                initialPlayer.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 3));
                SereniteaPotBundle bundle = SereniteaPotManager.createStaging(owner, 1L, 1L);
                SereniteaPotManager.commitGeneration(
                    bundle,
                    Map.of(SereniteaPotDimension.OVERWORLD, new SereniteaPotSlotRecord(
                        helper.getLevel().dimension().identifier().toString(),
                        initialPlayer.getBlockX(), initialPlayer.getBlockY(), initialPlayer.getBlockZ(), 0
                    )),
                    0
                );

                CompoundTag root = new CompoundTag();
                root.putInt("schemaVersion", badSchema ? 2 : 1);
                CompoundTag privateState = new CompoundTag();
                privateState.putInt("snapshotVersion", badSchema ? 3 : 4);
                privateState.putString("sentinel", "preserve unsupported private data");
                String stateKey = "serenitea_pot_" + owner + "_"
                    + SereniteaPotManager.record(owner).getStateId();
                root.put(stateKey, privateState);
                Path worldRoot = server.getWorldPath(LevelResource.ROOT);
                Path file = worldRoot.resolve(SereniteaPotMod.MOD_ID)
                    .resolve("player_states").resolve(owner + ".dat");
                stateFile.set(file);
                Files.createDirectories(file.getParent());
                NbtIo.writeCompressed(root, file);
                ((PlayerListAccessor) server.getPlayerList()).sereniteapot$getPlayerDataStorage()
                    .save(initialPlayer);
                Path publicFile = server.getWorldPath(LevelResource.PLAYER_DATA_DIR)
                    .resolve(owner + ".dat");
                fixture.set(new Fixture(
                    bundle, file, Files.readAllBytes(file), publicFile, Files.readAllBytes(publicFile)
                ));
                phase.set(1);
            } catch (Throwable thrown) {
                try {
                    cleanup(server, owner, stateFile.get());
                } catch (Throwable cleanupFailure) {
                    thrown.addSuppressed(cleanupFailure);
                }
                failure.set(thrown);
                phase.set(2);
            }
        });

        helper.onEachTick(() -> {
            if (phase.compareAndSet(1, 2)) {
                try {
                    Fixture state = fixture.get();
                    ServerPlayer player = currentPlayer(server, owner);
                    var publicLevel = player.level();
                    var publicPosition = player.position();
                    float publicYaw = player.getYRot();
                    float publicPitch = player.getXRot();
                    var publicMenu = player.containerMenu;
                    if (!player.gameMode.isSurvival()
                        || player.getInventory().countItem(Items.DIAMOND) != 3) {
                        throw new AssertionError("Version test player was not in the intended public state before teleport");
                    }
                    if (player.teleportTo(
                        state.bundle().get(SereniteaPotDimension.OVERWORLD),
                        0.5, 70.0, 0.5, Set.of(), 0.0F, 0.0F, true
                    )) {
                        throw new AssertionError("Unsupported private state was accepted by the teleport boundary");
                    }
                    assertPublicStateUnchanged(
                        server, owner, player, publicLevel, publicPosition,
                        publicYaw, publicPitch, publicMenu, "direct teleport"
                    );
                    if (!(SereniteaPotTravelService.enter(player, owner)
                        instanceof SereniteaPotTravelService.Rejected)) {
                        throw new AssertionError("Travel service accepted unsupported private state");
                    }
                    assertPublicStateUnchanged(
                        server, owner, player, publicLevel, publicPosition,
                        publicYaw, publicPitch, publicMenu, "travel service"
                    );
                    if (!Arrays.equals(Files.readAllBytes(state.file()), state.originalBytes())) {
                        throw new AssertionError("Failed teleport overwrote unsupported private state");
                    }
                    if (!Arrays.equals(Files.readAllBytes(state.publicFile()), state.originalPublicBytes())) {
                        throw new AssertionError("Failed teleport overwrote public playerdata");
                    }
                } catch (Throwable thrown) {
                    failure.set(thrown);
                }
                server.execute(() -> {
                    try {
                        Fixture state = fixture.get();
                        cleanup(server, owner, state == null ? stateFile.get() : state.file());
                    } catch (Throwable thrown) {
                        failure.compareAndSet(null, thrown);
                    } finally {
                        phase.set(3);
                    }
                });
            } else if (phase.get() == 2 && fixture.get() == null) {
                Throwable thrown = failure.get();
                helper.fail("Could not set up player state version test: " + thrown);
            } else if (phase.get() == 3) {
                Throwable thrown = failure.get();
                if (thrown != null) {
                    helper.fail("Player state version preflight failed: " + thrown);
                } else {
                    helper.succeed();
                }
            }
        });
    }

    private static ServerPlayer currentPlayer(MinecraftServer server, UUID playerId) {
        ServerPlayer player = server.getPlayerList().getPlayer(playerId);
        if (player == null) throw new AssertionError("The live test player is not registered");
        return player;
    }

    private static void assertPublicStateUnchanged(
        MinecraftServer server,
        UUID owner,
        ServerPlayer player,
        net.minecraft.server.level.ServerLevel publicLevel,
        net.minecraft.world.phys.Vec3 publicPosition,
        float publicYaw,
        float publicPitch,
        net.minecraft.world.inventory.AbstractContainerMenu publicMenu,
        String route
    ) {
        if (currentPlayer(server, owner) != player) {
            throw new AssertionError(route + " replaced the registered player");
        }
        if (player.level() != publicLevel) {
            throw new AssertionError(route + " changed the public level to " + player.level().dimension());
        }
        if (!player.position().equals(publicPosition)) {
            throw new AssertionError(route + " changed position from " + publicPosition + " to " + player.position());
        }
        if (player.getYRot() != publicYaw || player.getXRot() != publicPitch) {
            throw new AssertionError(route + " changed rotation from " + publicYaw + ", " + publicPitch
                + " to " + player.getYRot() + ", " + player.getXRot());
        }
        if (player.containerMenu != publicMenu) {
            throw new AssertionError(route + " closed or changed the public container");
        }
        if (!player.gameMode.isSurvival()) {
            throw new AssertionError(route + " changed survival game mode");
        }
        int diamonds = player.getInventory().countItem(Items.DIAMOND);
        if (diamonds != 3) {
            throw new AssertionError(route + " changed diamond inventory count from 3 to " + diamonds);
        }
    }

    private static void cleanup(MinecraftServer server, UUID owner, Path stateFile) throws Exception {
        if (SereniteaPotManager.record(owner) != null) {
            var deletion = SereniteaPotDeletionService.deleteAndReset(server, owner);
            if (deletion != SereniteaPotDeletionService.Success.INSTANCE) {
                throw new AssertionError("Could not clean up version test pot: " + deletion);
            }
        }
        if (stateFile != null) Files.deleteIfExists(stateFile);
        ServerPlayer player = server.getPlayerList().getPlayer(owner);
        if (player != null) server.getPlayerList().remove(player);
        SereniteaPotManager.catalog().getPlayers().remove(owner);
        SereniteaPotManager.saveCatalog();
    }

    private record Fixture(
        SereniteaPotBundle bundle,
        Path file,
        byte[] originalBytes,
        Path publicFile,
        byte[] originalPublicBytes
    ) {
    }
}
