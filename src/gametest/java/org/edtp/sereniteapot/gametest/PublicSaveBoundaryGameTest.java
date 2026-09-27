package org.edtp.sereniteapot.gametest;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.NbtIo;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.storage.LevelResource;
import org.edtp.sereniteapot.player.PlayerStateManager;
import org.edtp.sereniteapot.level.SereniteaPotLevelKeys;
import org.edtp.sereniteapot.level.SereniteaPotManager;
import org.edtp.sereniteapot.level.SereniteaPotTravelService;
import org.edtp.sereniteapot.mixin.accessor.PlayerListAccessor;
import org.edtp.sereniteapot.model.SereniteaPotDimension;
import org.edtp.sereniteapot.model.SereniteaPotSlotRecord;

import java.nio.file.Files;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/** Exercises real Vanilla file replacement failure and a fresh read on return. */
public final class PublicSaveBoundaryGameTest {
    @GameTest(maxTicks = 300)
    @SuppressWarnings("removal")
    public void rejectsFailedPublicSaveAndReadsOnlyOnReturn(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        var initial = helper.makeMockServerPlayerInLevel();
        var id = initial.getUUID();
        var directory = server.getWorldPath(LevelResource.PLAYER_DATA_DIR);
        var file = directory.resolve(id + ".dat");
        var backup = directory.resolve(id + ".dat_old");
        var blocker = backup.resolve("test-blocker");
        var queued = new AtomicBoolean();
        var failure = new AtomicReference<Throwable>();
        int[] phase = {0};
        helper.onEachTick(() -> {
            if (failure.get() != null) { helper.fail("Public save boundary: " + failure.get()); return; }
            if (phase[0] == 3) { helper.succeed(); return; }
            if (!queued.compareAndSet(false, true)) return;
            server.execute(() -> {
                try {
                    var player = server.getPlayerList().getPlayer(id);
                    var storage = ((PlayerListAccessor) server.getPlayerList()).sereniteapot$getPlayerDataStorage();
                    switch (phase[0]) {
                        case 0 -> {
                            if (!PlayerStateManager.prepare(player).isDone()) return;
                            player.setGameMode(GameType.SURVIVAL);
                            player.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 3));
                            var bundle = SereniteaPotManager.createStaging(id, 1, 1);
                            GameTestStorage.commitGeneration(bundle, Map.of(SereniteaPotDimension.OVERWORLD,
                                    new SereniteaPotSlotRecord("minecraft:overworld", 0, 80, 0, 0)), 0);
                            storage.save(player);
                            Files.deleteIfExists(backup);
                            Files.createDirectory(backup);
                            Files.writeString(blocker, "Prevent Vanilla backup replacement");
                            try {
                                var result = SereniteaPotTravelService.enter(player, id);
                                if (!(result instanceof SereniteaPotTravelService.Rejected)
                                        || SereniteaPotLevelKeys.identify(player.level().dimension()) != null
                                        || !player.gameMode.isSurvival()
                                        || player.getInventory().countItem(Items.DIAMOND) != 3) {
                                    throw new AssertionError("Failed disk save changed the player's realm/state");
                                }
                            } finally {
                                Files.deleteIfExists(blocker);
                                Files.deleteIfExists(backup);
                            }
                            if (SereniteaPotTravelService.enter(player, id) != SereniteaPotTravelService.Success.INSTANCE) {
                                throw new AssertionError("Failed save poisoned a later successful entry");
                            }
                            phase[0] = 1;
                        }
                        case 1 -> {
                            if (!PlayerStateManager.prepare(player).isDone()) return;
                            // Fixture edit after entry: a visit-long cached read would miss it.
                            var changed = storage.load(player.nameAndId()).orElseThrow();
                            changed.putInt("XpLevel", 37);
                            NbtIo.writeCompressed(changed, file);
                            phase[0] = 2;
                            PlayerStateManager.whenReadyToLeave(player, current -> {
                                try {
                                    if (SereniteaPotTravelService.leave(current) != SereniteaPotTravelService.Success.INSTANCE) {
                                        throw new AssertionError("Asynchronous return was rejected");
                                    }
                                    var returned = server.getPlayerList().getPlayer(id);
                                    if (returned.experienceLevel != 37 || returned.getInventory().countItem(Items.DIAMOND) != 3) {
                                        throw new AssertionError("Return used cached rather than current public file");
                                    }
                                    server.getPlayerList().remove(returned);
                                    GameTestStorage.deleteAndReset(server, id);
                                    SereniteaPotManager.catalog().getPlayers().remove(id);
                                    phase[0] = 3;
                                } catch (Throwable error) { failure.set(error); }
                            });
                        }
                        case 2 -> { }
                        default -> throw new AssertionError("Unexpected phase");
                    }
                } catch (Throwable error) { failure.set(error); }
                finally { queued.set(false); }
            });
        });
    }
}
