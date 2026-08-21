package org.edtp.sereniteapot.compat.axiom;

import com.moulberry.axiom.packets.AxiomClientboundRedoHandshake;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.level.ServerPlayer;

/** Restarts Axiom's own public handshake after its realm-scoped permission becomes available. */
public final class AxiomSessionCompatibility {
    private AxiomSessionCompatibility() {
    }

    public static void refreshIfInstalled(ServerPlayer player) {
        if (FabricLoader.getInstance().isModLoaded("axiom")) {
            Sender.send(player);
        }
    }

    /** Delays resolving Axiom classes until Fabric Loader confirms that the mod exists. */
    private static final class Sender {
        private static void send(ServerPlayer player) {
            new AxiomClientboundRedoHandshake().send(player);
        }
    }
}
