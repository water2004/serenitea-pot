package org.edtp.sereniteapot.gametest;

import com.moulberry.axiom.AxiomServer;
import com.moulberry.axiom.restrictions.AxiomPermission;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.level.ServerPlayer;
import org.edtp.sereniteapot.level.SereniteaPotBundle;
import org.edtp.sereniteapot.level.SereniteaPotDeletionService;
import org.edtp.sereniteapot.level.SereniteaPotManager;
import org.edtp.sereniteapot.model.SereniteaPotDimension;
import org.edtp.sereniteapot.model.SereniteaPotSlotRecord;
import org.edtp.sereniteapot.permission.SereniteaPotToolPermissions;

import java.util.Map;

/** Exercises the common permission contract against each supported Axiom runtime. */
public final class AxiomCompatibilityGameTest {
    @GameTest
    public void scopesAxiomPermissionAndRefreshesItsSession(GameTestHelper helper) {
        if (!FabricLoader.getInstance().isModLoaded("axiom")) {
            helper.succeed();
            return;
        }
        AxiomAssertions.run(helper);
    }

    /** Keeps Axiom classes unresolved in the normal GameTest runtime where Axiom is absent. */
    private static final class AxiomAssertions {
        @SuppressWarnings("removal")
        private static void run(GameTestHelper helper) {
            var server = helper.getLevel().getServer();
            ServerPlayer owner = helper.makeMockServerPlayerInLevel();
            ServerPlayer publicPlayer = helper.makeMockServerPlayerInLevel();
            try {
                long generation = System.currentTimeMillis();
                SereniteaPotBundle bundle = SereniteaPotManager.createStaging(
                        owner.getUUID(), generation, helper.getLevel().getSeed());
                SereniteaPotManager.commitGeneration(
                        bundle,
                        Map.of(
                                SereniteaPotDimension.OVERWORLD,
                                new SereniteaPotSlotRecord(
                                        helper.getLevel().dimension().identifier().toString(),
                                        owner.getBlockX(),
                                        owner.getBlockY(),
                                        owner.getBlockZ(),
                                        0)),
                        0);
                owner.setServerLevel(bundle.get(SereniteaPotDimension.OVERWORLD));

                helper.assertTrue(
                        AxiomServer.hasPermission(owner, AxiomPermission.ALL),
                        "Axiom must receive axiom.all for an owner inside their own pot");
                helper.assertTrue(
                        !AxiomServer.hasPermission(publicPlayer, AxiomPermission.ALL),
                        "Axiom permission must not leak into the public world");

                // Axiom 5 receives its legacy redo packet; Axiom 6 deliberately takes its native path.
                SereniteaPotToolPermissions.afterRealmChange(owner, owner.getUUID());
            } finally {
                owner.setServerLevel(server.overworld());
                SereniteaPotDeletionService.deleteAndReset(server, owner.getUUID());
                SereniteaPotManager.catalog().getPlayers().remove(owner.getUUID());
                SereniteaPotManager.saveCatalog();
                server.getPlayerList().remove(owner);
                server.getPlayerList().remove(publicPlayer);
            }
            helper.succeed();
        }
    }
}
