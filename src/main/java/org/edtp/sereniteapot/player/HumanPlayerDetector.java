package org.edtp.sereniteapot.player;

import net.minecraft.network.Connection;
import net.minecraft.server.level.ServerPlayer;
import org.edtp.sereniteapot.mixin.ServerCommonPacketListenerAccessor;

public final class HumanPlayerDetector {
    private static final String CARPET_FAKE_PLAYER = "carpet.patches.EntityPlayerMPFake";

    private HumanPlayerDetector() {
    }

    public static boolean isHuman(ServerPlayer player) {
        if (isCarpetFake(player)) {
            return false;
        }
        Connection network = ((ServerCommonPacketListenerAccessor) player.connection)
            .sereniteapot$getConnection();
        return network.isConnected();
    }

    private static boolean isCarpetFake(ServerPlayer player) {
        Class<?> type = player.getClass();
        while (type != null) {
            if (type.getName().equals(CARPET_FAKE_PLAYER)) {
                return true;
            }
            type = type.getSuperclass();
        }
        return false;
    }
}
