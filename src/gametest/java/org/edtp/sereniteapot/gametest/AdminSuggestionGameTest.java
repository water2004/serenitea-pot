package org.edtp.sereniteapot.gametest;

import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;
import org.edtp.sereniteapot.level.SereniteaPotManager;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

/** Verifies that administrative completion excludes targets a command cannot inspect or delete. */
public final class AdminSuggestionGameTest {
    @GameTest(maxTicks = 200)
    public void adminSuggestionsRespectConfiguredTargets(GameTestHelper helper) {
        MinecraftServer server = helper.getLevel().getServer();
        AtomicBoolean complete = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();

        server.execute(() -> {
            ServerPlayer configured = null;
            ServerPlayer unconfigured = null;
            try {
                configured = connectedPlayer(helper, "configuredPot");
                unconfigured = connectedPlayer(helper, "noPotConfig");
                SereniteaPotManager.getOrCreateRecord(configured.getUUID());
                SereniteaPotManager.saveCatalog();

                var dispatcher = server.getCommands().getDispatcher();
                for (String command : Set.of(
                        "sereniteapot admin status ",
                        "sereniteapot admin perf ",
                        "sereniteapot admin delete ")) {
                    Set<String> suggestions = suggestions(dispatcher, server.createCommandSourceStack(), command);
                    if (!suggestions.contains("configuredPot")) {
                        throw new AssertionError("Configured player missing from /" + command + " suggestions");
                    }
                    if (suggestions.contains("noPotConfig")) {
                        throw new AssertionError("Unconfigured player present in /" + command + " suggestions");
                    }
                }

                // disable is intentionally valid before a pot is created, so it keeps vanilla profile completion.
                Set<String> disableSuggestions = suggestions(
                        dispatcher,
                        server.createCommandSourceStack(),
                        "sereniteapot admin disable ");
                if (!disableSuggestions.containsAll(Set.of("configuredPot", "noPotConfig"))) {
                    throw new AssertionError("Pre-configuration target missing from admin disable suggestions");
                }
                complete.set(true);
            } catch (Throwable throwable) {
                failure.set(throwable);
            } finally {
                if (configured != null) {
                    SereniteaPotManager.catalog().getPlayers().remove(configured.getUUID());
                    SereniteaPotManager.saveCatalog();
                }
                remove(server, configured);
                remove(server, unconfigured);
            }
        });

        helper.onEachTick(() -> {
            Throwable throwable = failure.get();
            if (throwable != null) {
                helper.fail("Admin suggestion test failed: " + throwable);
            } else if (complete.get()) {
                helper.succeed();
            }
        });
    }

    private static Set<String> suggestions(
            com.mojang.brigadier.CommandDispatcher<CommandSourceStack> dispatcher,
            CommandSourceStack source,
            String command) {
        var parsed = dispatcher.parse(command, source);
        return dispatcher.getCompletionSuggestions(parsed).join().getList().stream()
                .map(suggestion -> suggestion.getText())
                .collect(Collectors.toSet());
    }

    private static ServerPlayer connectedPlayer(GameTestHelper helper, String name) {
        MinecraftServer server = helper.getLevel().getServer();
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

    private static void remove(MinecraftServer server, ServerPlayer player) {
        if (player != null && server.getPlayerList().getPlayer(player.getUUID()) != null) {
            server.getPlayerList().remove(player);
        }
    }
}
