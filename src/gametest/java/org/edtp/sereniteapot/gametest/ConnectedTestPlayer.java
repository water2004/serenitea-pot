package org.edtp.sereniteapot.gametest;

import com.mojang.authlib.GameProfile;
import io.netty.channel.embedded.EmbeddedChannel;
import net.minecraft.network.Connection;
import net.minecraft.network.protocol.PacketFlow;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.CommonListenerCookie;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** A real player with observable outgoing packets; follows WorldThreader's instance replacements. */
final class ConnectedTestPlayer implements AutoCloseable {
    final UUID id = UUID.randomUUID();
    private final MinecraftServer server;
    private final EmbeddedChannel channel;

    ConnectedTestPlayer(MinecraftServer server) {
        this.server = server;
        var cookie = CommonListenerCookie.createInitial(new GameProfile(id, "realm-probe"), false);
        var player = new ServerPlayer(server, server.overworld(), cookie.gameProfile(), cookie.clientInformation());
        var connection = new Connection(PacketFlow.SERVERBOUND);
        channel = new EmbeddedChannel(connection);
        server.getPlayerList().placeNewPlayer(connection, player, cookie);
    }

    ServerPlayer player() {
        return server.getPlayerList().getPlayer(id);
    }

    List<Object> takePackets() {
        channel.runPendingTasks();
        var packets = new ArrayList<>();
        Object packet;
        while ((packet = channel.readOutbound()) != null) packets.add(packet);
        return packets;
    }

    @Override
    public void close() {
        if (player() != null) server.getPlayerList().remove(player());
        channel.finishAndReleaseAll();
    }
}
