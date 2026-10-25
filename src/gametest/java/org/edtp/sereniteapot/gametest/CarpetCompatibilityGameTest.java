package org.edtp.sereniteapot.gametest;

import carpet.patches.EntityPlayerMPFake;
import com.mojang.authlib.GameProfile;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.UUIDUtil;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ClientInformation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import net.minecraft.world.phys.Vec3;
import org.edtp.sereniteapot.adapters.carpet.CarpetCompatibility;
import org.edtp.sereniteapot.gametest.carpet.CarpetTestProfiles;
import org.edtp.sereniteapot.level.*;
import org.edtp.sereniteapot.mixin.accessor.PlayerListAccessor;
import org.edtp.sereniteapot.model.SereniteaPotDimension;
import org.edtp.sereniteapot.model.SereniteaPotSlotRecord;
import org.edtp.sereniteapot.player.PlayerStateManager;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/** Runs real Carpet createFake, private saves, kill and pot lifecycle, including with WorldThreader. */
public final class CarpetCompatibilityGameTest {
    @GameTest(maxTicks = 300)
    public void freshFakeCannotKeepPotLoaded(GameTestHelper helper) { run(helper, Scenario.FRESH); }

    @GameTest(maxTicks = 300)
    public void ownerLogoutRemovesNewFakeAndUnloadsPot(GameTestHelper helper) { run(helper, Scenario.LOGOUT); }

    @GameTest(maxTicks = 300)
    public void fakeRespawnRestoresPrivateInventoryAndFlight(GameTestHelper helper) { run(helper, Scenario.RESPAWN); }

    @GameTest(maxTicks = 300)
    public void fakeBirthNeverLoadsOrOverwritesPublicInventory(GameTestHelper helper) { run(helper, Scenario.PUBLIC_DATA); }

    @GameTest(maxTicks = 300)
    public void delayedProfileCannotJoinClosedPot(GameTestHelper helper) { run(helper, Scenario.LATE_PROFILE); }

    @GameTest(maxTicks = 300)
    public void pendingPrivateReadCannotJoinClosedPot(GameTestHelper helper) { run(helper, Scenario.LATE_DATA); }

    @GameTest(maxTicks = 300)
    public void failedPrivateReadAbortsBirthAndReleasesPendingName(GameTestHelper helper) { run(helper, Scenario.FAILED_DATA); }

    @GameTest(maxTicks = 300)
    public void publicFakeKeepsCarpetModeAndData(GameTestHelper helper) { run(helper, Scenario.PUBLIC_SPAWN); }

    @GameTest(maxTicks = 300)
    public void maintenanceRemovesFakeWithoutPublicData(GameTestHelper helper) { run(helper, Scenario.MAINTENANCE); }

    @GameTest(maxTicks = 300)
    public void residentCanHostFakeButInvitedFakeCannotKeepPotLoaded(GameTestHelper helper) { run(helper, Scenario.RESIDENT); }

    private enum Scenario { FRESH, LOGOUT, RESPAWN, PUBLIC_DATA, LATE_PROFILE, LATE_DATA, FAILED_DATA, PUBLIC_SPAWN, MAINTENANCE, RESIDENT }

    private static void run(GameTestHelper helper, Scenario scenario) {
        if (!FabricLoader.getInstance().isModLoaded("carpet")) { helper.succeed(); return; }
        var probe = new Probe(helper, scenario);
        var queued = new AtomicBoolean();
        helper.onEachTick(() -> {
            if (probe.finished || !queued.compareAndSet(false, true)) return;
            helper.getLevel().getServer().execute(() -> {
                try { probe.step(); }
                catch (Throwable error) {
                    probe.finished = true;
                    try { probe.cleanup(); } catch (Throwable cleanup) { error.addSuppressed(cleanup); }
                    helper.fail("Carpet " + scenario + ": " + error);
                } finally { queued.set(false); }
            });
        });
    }

    private static final class Probe {
        final GameTestHelper helper;
        final Scenario scenario;
        final String name = "pot" + UUID.randomUUID().toString().substring(0, 8);
        final GameProfile profile = new GameProfile(UUIDUtil.createOfflinePlayerUUID(name), name);
        final CompletableFuture<GameProfile> profileRead = new CompletableFuture<>();
        ConnectedTestPlayer owner;
        ConnectedTestPlayer resident;
        ServerLevel level;
        CompletableFuture<net.minecraft.nbt.CompoundTag> dataGate;
        CompletableFuture<SereniteaPotLifecycleService.Result> maintenance;
        int phase;
        int waited;
        boolean finished;
        boolean ownerDisconnected;

        Probe(GameTestHelper helper, Scenario scenario) { this.helper = helper; this.scenario = scenario; }

        void step() throws Exception {
            var server = helper.getLevel().getServer();
            if (phase == 0) {
                owner = new ConnectedTestPlayer(server);
                PlayerStateManager.prepare(owner.player()).join(); // fixture-only I/O
                var bundle = SereniteaPotManager.createStaging(owner.id, 1, 1);
                GameTestStorage.commitGeneration(bundle, Map.of(SereniteaPotDimension.OVERWORLD,
                    new SereniteaPotSlotRecord("minecraft:overworld", 0, 80, 0, 0)), 0);
                check(SereniteaPotTravelService.enter(owner.player(), owner.id) == SereniteaPotTravelService.Success.INSTANCE,
                    "Owner entry failed");
                level = scenario == Scenario.PUBLIC_SPAWN ? server.overworld() : bundle.get(SereniteaPotDimension.OVERWORLD);
                if (scenario == Scenario.RESIDENT) {
                    resident = new ConnectedTestPlayer(server);
                    PlayerStateManager.prepare(resident.player()).join();
                    var record = SereniteaPotManager.record(owner.id);
                    record.getResidents().add(resident.id);
                    record.getResidents().add(profile.id()); // Explicitly invited fake still cannot host.
                    check(SereniteaPotTravelService.enter(resident.player(), owner.id)
                        == SereniteaPotTravelService.Success.INSTANCE, "Resident entry failed");
                    leave();
                }
                server.services().nameToIdCache().add(new NameAndId(profile));
                CarpetTestProfiles.pending.put(profile.id(), profileRead);
                if (scenario == Scenario.PUBLIC_DATA || scenario == Scenario.PUBLIC_SPAWN) {
                    var publicPlayer = new ServerPlayer(server, server.overworld(), profile, ClientInformation.createDefault());
                    publicPlayer.getInventory().setItem(0, new ItemStack(Items.DIAMOND, 7));
                    storage().save(publicPlayer);
                }
                if (scenario == Scenario.LATE_DATA || scenario == Scenario.FAILED_DATA) dataGate = blockPrivateRead();
                spawn();
                if (scenario != Scenario.LATE_PROFILE) profileRead.complete(profile);
                phase = 1;
                return;
            }
            if (phase == 1 && (scenario == Scenario.LATE_PROFILE || scenario == Scenario.LATE_DATA || scenario == Scenario.FAILED_DATA)) {
                check(fake() == null, "Fake registered before preparation completed");
                if (++waited < 4) return;
                check(EntityPlayerMPFake.isSpawningPlayer(name), "Carpet pending birth vanished");
                if (scenario == Scenario.FAILED_DATA) {
                    dataGate.completeExceptionally(new java.io.IOException("injected Carpet private-read failure"));
                    phase = 5;
                    return;
                }
                leave();
                phase = 4;
                return;
            }
            if (phase == 1 || phase == 3) {
                if (fake() == null || EntityPlayerMPFake.isSpawningPlayer(name)) return;
                ServerPlayer fake = fake();
                check(fake.level() == level, "Wrong birth level");
                if (scenario == Scenario.PUBLIC_SPAWN) {
                    check(fake.gameMode.getGameModeForPlayer() == GameType.SURVIVAL, "Public mode changed");
                    check(fake.getInventory().getItem(0).is(Items.DIAMOND), "Public Carpet data not loaded");
                    CarpetCompatibility.removeFake(fake);
                    phase = 5;
                    return;
                }
                check(fake.gameMode.isCreative() && fake.getAbilities().invulnerable, "Pot fake is not creative");
                if (phase == 3) {
                    check(fake.getInventory().getItem(0).is(Items.EMERALD)
                        && fake.getInventory().getItem(0).getCount() == 11, "Private inventory not restored");
                    check(fake.getAbilities().flying, "Private flight not restored");
                } else {
                    check(fake.getInventory().isEmpty(), "Public inventory leaked into pot");
                }
                if (scenario == Scenario.RESPAWN && phase == 1) {
                    fake.getInventory().setItem(0, new ItemStack(Items.EMERALD, 11));
                    fake.getAbilities().flying = true;
                    CarpetCompatibility.removeFake(fake);
                    phase = 2;
                    return;
                }
                if (scenario == Scenario.MAINTENANCE) {
                    maintenance = SereniteaPotLifecycleService.beginMaintenance(server, owner.id);
                } else if (scenario == Scenario.LOGOUT) {
                    owner.close();
                    ownerDisconnected = true;
                } else if (scenario == Scenario.RESIDENT) {
                    resident.close();
                } else leave();
                phase = 5;
                return;
            }
            if (phase == 2) {
                if (fake() != null) return;
                spawn();
                phase = 3;
                return;
            }
            if (phase == 4) {
                if (SereniteaPotManager.loaded(owner.id) != null) return;
                // Complete the real lookup/read only after the captured level has closed.
                profileRead.complete(profile);
                if (dataGate != null) dataGate.complete(new net.minecraft.nbt.CompoundTag());
                phase = 5;
                return;
            }
            if (phase == 5) {
                if (fake() != null || EntityPlayerMPFake.isSpawningPlayer(name)) return;
                if (maintenance != null) {
                    if (!maintenance.isDone()) return;
                    check(maintenance.join() == SereniteaPotLifecycleService.Success.INSTANCE, "Maintenance blocked by fake");
                    SereniteaPotLifecycleService.endMaintenance(owner.id);
                } else if (scenario != Scenario.PUBLIC_SPAWN && scenario != Scenario.FAILED_DATA
                    && SereniteaPotManager.loaded(owner.id) != null) return;
                var saved = storage().load(new NameAndId(profile));
                check(saved.isPresent() == (scenario == Scenario.PUBLIC_DATA || scenario == Scenario.PUBLIC_SPAWN),
                    "Pot birth created/replaced public playerdata");
                if (scenario == Scenario.PUBLIC_DATA) {
                    var restored = new ServerPlayer(server, server.overworld(), profile, ClientInformation.createDefault());
                    restored.load(net.minecraft.world.level.storage.TagValueInput.create(
                        net.minecraft.util.ProblemReporter.DISCARDING, server.registryAccess(), saved.orElseThrow()));
                    check(restored.getInventory().getItem(0).is(Items.DIAMOND)
                        && restored.getInventory().getItem(0).getCount() == 7, "Public inventory overwritten");
                }
                cleanup();
                finished = true;
                helper.succeed();
            }
        }

        void spawn() {
            check(EntityPlayerMPFake.createFake(name, helper.getLevel().getServer(), new Vec3(8, 80, 8),
                0, 0, level.dimension(), GameType.SURVIVAL, false), "Carpet rejected fixture birth");
        }

        void leave() {
            PlayerStateManager.prepareReturn(owner.player()).join(); // fixture only
            check(SereniteaPotTravelService.leave(owner.player()) == SereniteaPotTravelService.Success.INSTANCE, "Owner leave failed");
        }

        ServerPlayer fake() { return helper.getLevel().getServer().getPlayerList().getPlayer(profile.id()); }

        net.minecraft.world.level.storage.PlayerDataStorage storage() {
            return ((PlayerListAccessor) helper.getLevel().getServer().getPlayerList()).sereniteapot$getPlayerDataStorage();
        }

        @SuppressWarnings("unchecked")
        CompletableFuture<net.minecraft.nbt.CompoundTag> blockPrivateRead() throws Exception {
            var storeField = PlayerStateManager.class.getDeclaredField("store");
            storeField.setAccessible(true);
            Object store = storeField.get(null);
            var entryMethod = store.getClass().getDeclaredMethod("entry", UUID.class);
            entryMethod.setAccessible(true);
            Object entry = entryMethod.invoke(store, profile.id());
            var loadedField = entry.getClass().getDeclaredField("loaded");
            loadedField.setAccessible(true);
            ((CompletableFuture<?>) loadedField.get(entry)).join(); // fixture read
            var gate = new CompletableFuture<net.minecraft.nbt.CompoundTag>();
            loadedField.set(entry, gate);
            return gate;
        }

        void cleanup() {
            var server = helper.getLevel().getServer();
            if (dataGate != null && !dataGate.isDone()) dataGate.complete(new net.minecraft.nbt.CompoundTag());
            CarpetTestProfiles.pending.remove(profile.id());
            if (fake() != null) server.getPlayerList().remove(fake());
            if (resident != null) resident.close();
            if (owner == null) return;
            if (!ownerDisconnected) owner.close();
            SereniteaPotLifecycleService.endMaintenance(owner.id);
            GameTestStorage.deleteAndReset(server, owner.id);
            SereniteaPotManager.catalog().getPlayers().remove(owner.id);
        }
    }

    private static void check(boolean condition, String message) { if (!condition) throw new AssertionError(message); }
}
