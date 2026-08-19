package org.edtp.universe.player

import net.minecraft.server.level.ServerPlayer
import org.edtp.universe.mixin.ServerCommonPacketListenerAccessor

object HumanPlayerDetector {
    private const val CARPET_FAKE_PLAYER = "carpet.patches.EntityPlayerMPFake"

    fun isHuman(player: ServerPlayer): Boolean {
        if (isCarpetFake(player)) {
            return false
        }
        val network = (player.connection as ServerCommonPacketListenerAccessor).`universe647$getConnection`()
        return network.isConnected
    }

    private fun isCarpetFake(player: ServerPlayer): Boolean {
        var type: Class<*>? = player.javaClass
        while (type != null) {
            if (type.name == CARPET_FAKE_PLAYER) {
                return true
            }
            type = type.superclass
        }
        return false
    }
}
