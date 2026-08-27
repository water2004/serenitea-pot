package org.edtp.sereniteapot.compat.axiom;

import com.moulberry.axiom.packets.AxiomClientboundRedoHandshake;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.level.ServerPlayer;

/** Bridges realm changes to the session-refresh capability exposed by the installed Axiom. */
public final class AxiomSessionCompatibility {
    private static final String LEGACY_REDO_HANDSHAKE_CLASS =
            "com.moulberry.axiom.packets.AxiomClientboundRedoHandshake";
    private static final boolean LEGACY_REFRESH_AVAILABLE = legacyRefreshAvailable();

    private AxiomSessionCompatibility() {
    }

    public static void refreshIfRequired(ServerPlayer player) {
        if (LEGACY_REFRESH_AVAILABLE) {
            Sender.send(player);
        }
    }

    /**
     * Axiom 5 exposes an explicit redo-handshake packet. Axiom 6 removed it and refreshes
     * permissions itself after level changes, so capability detection is the compatibility boundary.
     */
    private static boolean legacyRefreshAvailable() {
        if (!FabricLoader.getInstance().isModLoaded("axiom")) return false;
        try {
            Class.forName(
                    LEGACY_REDO_HANDSHAKE_CLASS,
                    false,
                    AxiomSessionCompatibility.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError ignored) {
            return false;
        }
    }

    /** Delays linking the Axiom 5-only packet class until capability detection succeeds. */
    private static final class Sender {
        private static void send(ServerPlayer player) {
            new AxiomClientboundRedoHandshake().send(player);
        }
    }
}
