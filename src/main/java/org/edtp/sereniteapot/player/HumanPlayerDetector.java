package org.edtp.sereniteapot.player;

import net.minecraft.network.Connection;
import net.minecraft.server.level.ServerPlayer;
import org.edtp.sereniteapot.adapters.carpet.CarpetCompatibility;
import org.edtp.sereniteapot.mixin.ServerCommonPacketListenerAccessor;

public final class HumanPlayerDetector {
    private HumanPlayerDetector() {
    }

    public static boolean isHuman(ServerPlayer player) {
        if (CarpetCompatibility.isFake(player)) {
            return false;
        }
        Connection network = ((ServerCommonPacketListenerAccessor) player.connection)
            .sereniteapot$getConnection();
        return network.isConnected();
    }
}
