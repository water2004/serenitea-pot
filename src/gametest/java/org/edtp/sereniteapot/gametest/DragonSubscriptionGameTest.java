package org.edtp.sereniteapot.gametest;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundBossEventPacket;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.world.BossEvent;
import org.edtp.sereniteapot.level.*;
import org.edtp.sereniteapot.mixin.accessor.EnderDragonFightAccessor;
import org.edtp.sereniteapot.model.*;
import org.edtp.sereniteapot.player.PlayerStateManager;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

public final class DragonSubscriptionGameTest {
    @GameTest(maxTicks = 300)
    public void clearsOnlySourceDragonWithoutWaitingForAnotherWorldTick(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        var done = new AtomicBoolean();
        var failure = new AtomicReference<Throwable>();
        server.execute(() -> {
            try {
                for (Exit exit : Exit.values()) checkExit(helper, exit);
            } catch (Throwable error) {
                failure.set(error);
            } finally {
                done.set(true);
            }
        });
        helper.onEachTick(() -> {
            if (!done.get()) return;
            if (failure.get() != null) helper.fail("Dragon subscription: " + failure.get());
            else helper.succeed();
        });
    }

    private static void checkExit(GameTestHelper helper, Exit exit) {
        var server = helper.getLevel().getServer();
        try (var connection = new ConnectedTestPlayer(server)) {
            PlayerStateManager.prepare(connection.player()).join();
            var bundle = SereniteaPotManager.createStaging(connection.id, 1L, 1L);
            GameTestStorage.commitGeneration(bundle, Map.of(SereniteaPotDimension.END,
                new SereniteaPotSlotRecord("minecraft:the_end", 8, 80, 8, 0)), 0);
            var source = bundle.get(SereniteaPotDimension.END);
            var dragon = ((EnderDragonFightAccessor) source.getDragonFight()).sereniteapot$getDragonEvent();
            var unrelated = new ServerBossEvent(UUID.randomUUID(), Component.literal("Unrelated boss"),
                BossEvent.BossBarColor.BLUE, BossEvent.BossBarOverlay.PROGRESS);
            try {
                require(SereniteaPotTravelService.enter(connection.player(), connection.id)
                    == SereniteaPotTravelService.Success.INSTANCE, "Failed to enter fixture");
                var departing = connection.player();
                dragon.addPlayer(departing);
                unrelated.addPlayer(departing);
                PlayerStateManager.prepareReturn(departing).join();
                connection.takePackets();
                // No source-world tick runs during this server task. Freeze explicitly
                // as well: cleanup must be driven by departure, not by dragon polling.
                SereniteaPotManager.record(connection.id).setFrozen(true);
                switch (exit) {
                    case LEAVE -> require(SereniteaPotTravelService.leave(departing)
                        == SereniteaPotTravelService.Success.INSTANCE, "Leave failed");
                    case DIRECT -> require(departing.teleportTo(server.overworld(), 8, 80, 8,
                        Set.of(), 0, 0, true), "Direct teleport failed");
                    case SAME_POT -> require(departing.teleportTo(bundle.get(SereniteaPotDimension.OVERWORLD),
                        8, 80, 8, Set.of(), 0, 0, true), "Same-pot teleport failed");
                    case SAME_LEVEL -> require(departing.teleportTo(source, 9, 80, 9,
                        Set.of(), 0, 0, true), "Same-level teleport failed");
                    case DISCONNECT -> server.getPlayerList().remove(departing);
                    case UNLOAD -> require(SereniteaPotLifecycleService.closeNow(server, connection.id)
                        == SereniteaPotLifecycleService.Success.INSTANCE, "Lifecycle close failed");
                }
                Set<UUID> removed = new HashSet<>();
                for (Object packet : connection.takePackets()) {
                    if (packet instanceof ClientboundBossEventPacket bossPacket) {
                        bossPacket.dispatch(new ClientboundBossEventPacket.Handler() {
                            @Override public void remove(UUID id) { removed.add(id); }
                        });
                    }
                }
                if (exit == Exit.SAME_LEVEL) {
                    require(dragon.getPlayers().contains(departing) && removed.isEmpty(),
                        "Same-level movement lost its dragon subscription");
                } else {
                    require(!dragon.getPlayers().contains(departing), exit + " retained departing instance");
                    require(removed.equals(Set.of(dragon.getId())), exit + " must remove only the source dragon bar: " + removed);
                }
                require(unrelated.getPlayers().contains(departing), "Removed an unrelated boss subscription");
            } finally {
                unrelated.removeAllPlayers();
                GameTestStorage.deleteAndReset(server, connection.id);
            }
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private enum Exit { LEAVE, DIRECT, SAME_POT, SAME_LEVEL, DISCONNECT, UNLOAD }
}
