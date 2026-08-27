package org.edtp.sereniteapot.gametest;

import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import org.edtp.sereniteapot.level.SereniteaPotBundle;
import org.edtp.sereniteapot.level.SereniteaPotDeletionService;
import org.edtp.sereniteapot.level.SereniteaPotInvitationService;
import org.edtp.sereniteapot.level.SereniteaPotManager;
import org.edtp.sereniteapot.model.SereniteaPotDimension;
import org.edtp.sereniteapot.model.SereniteaPotSlotRecord;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/** Verifies that invitation-related completion reflects the command's actionable targets. */
public final class InvitationSuggestionGameTest {
    @GameTest(maxTicks = 200)
    public void suggestionsMatchAvailableOwnersAndPendingRequests(GameTestHelper helper) {
        var server = helper.getLevel().getServer();
        AtomicBoolean complete = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        server.execute(() -> {
            ServerPlayer requester = null;
            ServerPlayer publicPlayer = null;
            ServerPlayer potOwner = null;
            try {
                requester = connectedPlayer(helper, "requester");
                publicPlayer = connectedPlayer(helper, "publicPlayer");
                potOwner = connectedPlayer(helper, "potOwner");

                SereniteaPotBundle bundle = SereniteaPotManager.createStaging(potOwner.getUUID(), 1L, 1L);
                SereniteaPotManager.commitGeneration(
                        bundle,
                        Map.of(
                                SereniteaPotDimension.OVERWORLD,
                                new SereniteaPotSlotRecord(
                                        helper.getLevel().dimension().identifier().toString(),
                                        potOwner.getBlockX(),
                                        potOwner.getBlockY(),
                                        potOwner.getBlockZ(),
                                        0)),
                        0);
                potOwner.setServerLevel(bundle.get(SereniteaPotDimension.OVERWORLD));

                var dispatcher = server.getCommands().getDispatcher();
                assertSuggestions(
                        helper,
                        dispatcher,
                        requester,
                        "sereniteapot request ",
                        Set.of("potOwner"));
                assertSuggestions(
                        helper,
                        dispatcher,
                        requester,
                        "sereniteapot enter ",
                        Set.of("potOwner"));

                var request = SereniteaPotInvitationService.request(requester, potOwner.getUUID());
                if (request != SereniteaPotInvitationService.Success.INSTANCE) {
                    throw new AssertionError("Could not create invitation request: " + request);
                }
                assertSuggestions(
                        helper,
                        dispatcher,
                        potOwner,
                        "sereniteapot approve ",
                        Set.of("requester"));
                assertSuggestions(
                        helper,
                        dispatcher,
                        potOwner,
                        "sereniteapot deny ",
                        Set.of("requester"));
                var denied = SereniteaPotInvitationService.deny(potOwner, requester.getUUID(), null);
                if (denied != SereniteaPotInvitationService.Success.INSTANCE) {
                    throw new AssertionError("Could not clean up invitation request: " + denied);
                }

                potOwner.setServerLevel(server.overworld());
                var deletion = SereniteaPotDeletionService.deleteAndReset(server, potOwner.getUUID());
                if (deletion != SereniteaPotDeletionService.Success.INSTANCE) {
                    throw new AssertionError("Could not clean up invitation suggestion test pot: " + deletion);
                }
                SereniteaPotManager.catalog().getPlayers().remove(potOwner.getUUID());
                SereniteaPotManager.saveCatalog();
                complete.set(true);
            } catch (Throwable throwable) {
                if (potOwner != null) potOwner.setServerLevel(server.overworld());
                failure.set(throwable);
            } finally {
                remove(server, requester);
                remove(server, publicPlayer);
                remove(server, potOwner);
            }
        });

        helper.onEachTick(() -> {
            Throwable throwable = failure.get();
            if (throwable != null) {
                helper.fail("Invitation suggestion test failed: " + throwable);
            } else if (complete.get()) {
                helper.succeed();
            }
        });
    }

    private static void assertSuggestions(
            GameTestHelper helper,
            com.mojang.brigadier.CommandDispatcher<net.minecraft.commands.CommandSourceStack> dispatcher,
            ServerPlayer source,
            String command,
            Set<String> expected) {
        var parsed = dispatcher.parse(command, source.createCommandSourceStack());
        Set<String> actual = dispatcher.getCompletionSuggestions(parsed).join().getList().stream()
                .map(suggestion -> suggestion.getText())
                .collect(Collectors.toSet());
        helper.assertValueEqual(expected, actual, "Unexpected suggestions for /" + command);
    }

    private static ServerPlayer connectedPlayer(GameTestHelper helper, String name) {
        var server = helper.getLevel().getServer();
        CommonListenerCookie cookie = CommonListenerCookie.createInitial(
                new GameProfile(UUID.randomUUID(), name),
                false);
        ServerPlayer player = new ServerPlayer(
                server,
                helper.getLevel(),
                cookie.gameProfile(),
                cookie.clientInformation());
        Connection connection = new Connection(PacketFlow.SERVERBOUND);
        new EmbeddedChannel(connection);
        server.getPlayerList().placeNewPlayer(connection, player, cookie);
        return player;
    }

    private static void remove(net.minecraft.server.MinecraftServer server, ServerPlayer player) {
        if (player != null && server.getPlayerList().getPlayer(player.getUUID()) != null) {
            server.getPlayerList().remove(player);
        }
    }
}
