package org.edtp.sereniteapot.gametest;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.protocol.game.ClientboundPlayerAbilitiesPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.server.players.NameAndId;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import org.AndrewElizabeth.teleportcommandsfabric.core.teleport.manager.TeleportOperationManager;
import org.AndrewElizabeth.teleportcommandsfabric.core.teleport.task.TeleportExecutor;
import org.AndrewElizabeth.teleportcommandsfabric.core.teleport.types.TeleportStatus;
import org.AndrewElizabeth.teleportcommandsfabric.core.teleport.types.TeleportTarget;
import org.AndrewElizabeth.teleportcommandsfabric.core.teleport.types.target.TargetTeleportOptions;
import org.AndrewElizabeth.teleportcommandsfabric.core.teleport.types.target.TeleportRequest;
import org.edtp.sereniteapot.level.*;
import org.edtp.sereniteapot.model.*;
import org.edtp.sereniteapot.player.PlayerStateManager;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

public final class TpcCompatibilityGameTest {
    @GameTest(maxTicks = 300)
    public void pendingReadCompletesOriginalTpcRequest(GameTestHelper helper) { pendingRead(helper, 0); }

    @GameTest(maxTicks = 300)
    public void cancelledTpcRequestDoesNotTeleportLater(GameTestHelper helper) { pendingRead(helper, 1); }

    @GameTest(maxTicks = 300)
    public void failedReadFailsOriginalTpcRequest(GameTestHelper helper) { pendingRead(helper, 2); }

    private static void pendingRead(GameTestHelper helper, int scenario) {
        if (!FabricLoader.getInstance().isModLoaded("teleport_commands_fabric")) { helper.succeed(); return; }
        var probe = new PendingReadProbe(helper, scenario);
        var queued = new java.util.concurrent.atomic.AtomicBoolean();
        helper.onEachTick(() -> {
            if (probe.done || !queued.compareAndSet(false, true)) return;
            helper.getLevel().getServer().execute(() -> {
                try { probe.step(); }
                catch (Throwable error) {
                    probe.cleanup();
                    probe.done = true;
                    helper.fail("TPC pending read: " + error);
                } finally { queued.set(false); }
            });
        });
    }

    private static final class PendingReadProbe {
        final GameTestHelper helper;
        final int scenario;
        ConnectedTestPlayer traveler;
        final TeleportOperationManager manager = new TeleportOperationManager();
        org.AndrewElizabeth.teleportcommandsfabric.core.teleport.types.TeleportOperation operation;
        java.util.concurrent.CompletableFuture<net.minecraft.nbt.CompoundTag> gate;
        net.minecraft.nbt.CompoundTag publicData;
        int tick;
        boolean done;

        PendingReadProbe(GameTestHelper helper, int scenario) { this.helper = helper; this.scenario = scenario; }

        @SuppressWarnings("unchecked")
        void step() throws Exception {
            var server = helper.getLevel().getServer();
            if (traveler == null) {
                traveler = new ConnectedTestPlayer(server);
                var pot = TpcAssertions.createPot(traveler);
                traveler.player().setGameMode(GameType.SURVIVAL);
                TpcAssertions.transfer(traveler, pot.get(SereniteaPotDimension.END));
                traveler.player().getAbilities().flying = true;
                PlayerStateManager.prepareReturn(traveler.player()).join(); // fixture-only I/O
                var field = PlayerStateManager.class.getDeclaredField("pendingPublicReturns");
                field.setAccessible(true);
                var reads = (Map<java.util.UUID, java.util.concurrent.CompletableFuture<net.minecraft.nbt.CompoundTag>>) field.get(null);
                publicData = reads.get(traveler.id).join();
                gate = new java.util.concurrent.CompletableFuture<>();
                reads.put(traveler.id, gate);
                var target = TeleportTarget.of(server.overworld(), new Vec3(8, 80, 8));
                operation = manager.createPending(traveler.id, TeleportRequest.resolved(target,
                    new TargetTeleportOptions(0, 0, false, false)), 0).pending();
                traveler.takePackets();
                TpcAssertions.require(new TeleportExecutor(null, manager).executeResolved(server, operation, target)
                    == TeleportStatus.ACCEPTED, "Pending transfer was not accepted");
            } else if (++tick < 5) {
                TpcAssertions.require(!operation.resultFuture().isDone(), "TPC completed before read");
                TpcAssertions.require(SereniteaPotLevelKeys.identify(traveler.player().level().dimension()) != null,
                    "Player moved before read");
            } else if (!gate.isDone()) {
                if (scenario == 1) manager.cancelCurrent(traveler.id, TeleportStatus.CANCELLED);
                if (scenario == 2) gate.completeExceptionally(new java.io.IOException("injected read failure"));
                else gate.complete(publicData);
            } else if (tick >= 8 && operation.resultFuture().isDone()) {
                var expected = scenario == 0 ? TeleportStatus.SUCCESS : scenario == 1 ? TeleportStatus.CANCELLED : TeleportStatus.FAILED;
                TpcAssertions.require(operation.resultFuture().join() == expected, "Wrong final TPC result");
                var packets = traveler.takePackets();
                long respawns = packets.stream().filter(net.minecraft.network.protocol.game.ClientboundRespawnPacket.class::isInstance).count();
                TpcAssertions.require(respawns == (scenario == 0 ? 1 : 0), "Unexpected deferred/duplicate teleport");
                TpcAssertions.require((traveler.player().level() == server.overworld()) == (scenario == 0), "Wrong final realm");
                if (scenario == 0) TpcAssertions.require(!traveler.player().gameMode.isCreative()
                    && !traveler.player().getAbilities().flying, "Source abilities leaked");
                cleanup();
                done = true;
                helper.succeed();
            }
        }

        void cleanup() {
            if (gate != null && !gate.isDone()) gate.complete(publicData);
            if (traveler != null) {
                traveler.close();
                GameTestStorage.deleteAndReset(helper.getLevel().getServer(), traveler.id);
                SereniteaPotManager.catalog().getPlayers().remove(traveler.id);
            }
        }
    }

    @GameTest(maxTicks = 300)
    public void preservesRealmAbilitiesWithoutChangingOrdinaryTpcFlight(GameTestHelper helper) {
        if (!FabricLoader.getInstance().isModLoaded("teleport_commands_fabric")) {
            helper.succeed();
            return;
        }
        // Keep all optional TPC linkage in a class that is never loaded without TPC.
        var result = new AtomicReference<Throwable>();
        var done = new java.util.concurrent.atomic.AtomicBoolean();
        helper.getLevel().getServer().execute(() -> {
            try { TpcAssertions.run(helper); }
            catch (Throwable error) { result.set(error); }
            finally { done.set(true); }
        });
        helper.onEachTick(() -> {
            if (!done.get()) return;
            if (result.get() != null) helper.fail("TPC adapter: " + result.get());
            else helper.succeed();
        });
    }

    private static final class TpcAssertions {
        static void run(GameTestHelper helper) {
            var server = helper.getLevel().getServer();
            try (var traveler = new ConnectedTestPlayer(server); var other = new ConnectedTestPlayer(server)) {
                var a = createPot(traveler);
                var b = createPot(other);
                var profile = new NameAndId(traveler.player().getGameProfile());
                try {
                    traveler.player().setGameMode(GameType.SURVIVAL);
                    transfer(traveler, a.get(SereniteaPotDimension.END));
                    traveler.player().getAbilities().flying = true;
                    transfer(traveler, server.overworld());
                    assertAbilities(traveler, false, false); // No old creative packet may follow restoration.

                    traveler.player().setGameMode(GameType.CREATIVE);
                    traveler.player().getAbilities().flying = true;
                    transfer(traveler, a.get(SereniteaPotDimension.END));
                    traveler.player().getAbilities().flying = false;
                    transfer(traveler, server.overworld());
                    assertAbilities(traveler, true, true); // Destination public flight is retained.
                    transfer(traveler, a.get(SereniteaPotDimension.END));
                    assertAbilities(traveler, true, false); // Source flight must not override saved pot flight.

                    traveler.player().getAbilities().flying = true;
                    transfer(traveler, a.get(SereniteaPotDimension.OVERWORLD));
                    assertOrdinaryTpcFlight(traveler); // Same pot, different dimension.
                    traveler.player().getAbilities().flying = true;
                    SereniteaPotManager.record(other.id).setEnabled(false);
                    require(execute(traveler, b.get(SereniteaPotDimension.END)) == TeleportStatus.FAILED,
                        "Disabled pot accepted a transfer");
                    require(traveler.player().getAbilities().flying, "Denied transfer changed real source abilities");
                    SereniteaPotManager.record(other.id).setEnabled(true);

                    transfer(other, b.get(SereniteaPotDimension.END));
                    server.getPlayerList().op(profile, Optional.of(LevelBasedPermissionSet.OWNER), Optional.empty());
                    transfer(traveler, b.get(SereniteaPotDimension.END));
                    assertAbilities(traveler, true, false); // Another owner's fresh private state.
                    transfer(traveler, server.overworld());
                    transfer(traveler, server.getLevel(Level.NETHER));
                    assertOrdinaryTpcFlight(traveler); // Public-to-public remains TPC's normal behavior.
                } finally {
                    server.getPlayerList().deop(profile);
                    GameTestStorage.deleteAndReset(server, traveler.id);
                    GameTestStorage.deleteAndReset(server, other.id);
                }
            }
        }

        private static SereniteaPotBundle createPot(ConnectedTestPlayer player) {
            PlayerStateManager.prepare(player.player()).join();
            var bundle = SereniteaPotManager.createStaging(player.id, 1L, 1L);
            GameTestStorage.commitGeneration(bundle, Map.of(SereniteaPotDimension.END,
                new SereniteaPotSlotRecord("minecraft:the_end", 8, 80, 8, 0)), 0);
            return bundle;
        }

        private static void transfer(ConnectedTestPlayer player, ServerLevel destination) {
            require(execute(player, destination) == TeleportStatus.SUCCESS, "TPC failed to reach " + destination.dimension());
            SereniteaPotLifecycleService.cancelPendingClose(player.id);
        }

        private static TeleportStatus execute(ConnectedTestPlayer player, ServerLevel destination) {
            // Fixture-only wait: exercise TPC's synchronous success/post-processing branch.
            PlayerStateManager.prepareReturn(player.player()).join();
            player.takePackets();
            var manager = new TeleportOperationManager();
            var target = new TeleportTarget(destination, new Vec3(8, 80, 8));
            var pending = manager.createPending(player.id, TeleportRequest.resolved(target,
                new TargetTeleportOptions(0, 0, false, false)), 0).pending();
            return new TeleportExecutor(null, manager).executeResolved(destination.getServer(), pending, target);
        }

        private static void assertAbilities(ConnectedTestPlayer player, boolean creative, boolean flying) {
            var current = player.player();
            require(current.gameMode.isCreative() == creative && current.getAbilities().flying == flying,
                "Authoritative player at " + current.level().dimension() + " expected creative/flight=" + creative + "/" + flying
                    + " but got " + current.gameMode.isCreative() + "/" + current.getAbilities().flying);
            var packets = player.takePackets().stream().filter(ClientboundPlayerAbilitiesPacket.class::isInstance)
                .map(ClientboundPlayerAbilitiesPacket.class::cast).toList();
            require(!packets.isEmpty(), "No abilities packet captured");
            var last = packets.getLast();
            require(last.canInstabuild() == creative && last.isFlying() == flying
                && last.isInvulnerable() == current.getAbilities().invulnerable
                && last.canFly() == current.getAbilities().mayfly, "Last packet contradicts authoritative player abilities");
        }

        private static void assertOrdinaryTpcFlight(ConnectedTestPlayer player) {
            var packets = player.takePackets().stream().filter(ClientboundPlayerAbilitiesPacket.class::isInstance)
                .map(ClientboundPlayerAbilitiesPacket.class::cast).toList();
            // In the unchanged path TPC still restores flight and sends its packet.
            // WorldThreader may replace the server player here too; correcting TPC's
            // ordinary same-realm behavior is outside this cross-realm adapter.
            require(!packets.isEmpty() && packets.getLast().isFlying(), "Adapter changed same-realm TPC behavior");
        }

        private static void require(boolean condition, String message) {
            if (!condition) throw new AssertionError(message);
        }
    }
}
