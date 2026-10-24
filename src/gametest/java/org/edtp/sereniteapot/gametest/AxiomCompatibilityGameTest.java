package org.edtp.sereniteapot.gametest;

import com.moulberry.axiom.AxiomServer;
import com.moulberry.axiom.restrictions.AxiomPermission;
import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.gametest.framework.GameTestHelper;
import org.edtp.sereniteapot.level.SereniteaPotManager;
import org.edtp.sereniteapot.level.SereniteaPotTravelService;
import org.edtp.sereniteapot.model.SereniteaPotDimension;
import org.edtp.sereniteapot.model.SereniteaPotSlotRecord;
import org.edtp.sereniteapot.player.PlayerStateManager;

import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/** Real transfers must trigger Axiom's own level-change permission refresh. */
public final class AxiomCompatibilityGameTest {
    @GameTest(maxTicks = 300)
    public void scopesAxiomPermissionAcrossRepeatedRealmChanges(GameTestHelper helper) {
        if (!FabricLoader.getInstance().isModLoaded("axiom")) { helper.succeed(); return; }
        var probe = new AxiomProbe(helper);
        var queued = new AtomicBoolean();
        helper.onEachTick(() -> {
            if (probe.finished || !queued.compareAndSet(false, true)) return;
            helper.getLevel().getServer().execute(() -> {
                try { probe.step(); }
                catch (Throwable error) {
                    probe.finished = true;
                    try { probe.cleanup(); } catch (Throwable cleanup) { error.addSuppressed(cleanup); }
                    helper.fail("Axiom realm permissions: " + error);
                } finally { queued.set(false); }
            });
        });
    }

    private static final class AxiomProbe {
        final GameTestHelper helper;
        ConnectedTestPlayer owner;
        int phase;
        boolean finished;

        AxiomProbe(GameTestHelper helper) { this.helper = helper; }

        void step() {
            var server = helper.getLevel().getServer();
            if (phase == 0) {
                owner = new ConnectedTestPlayer(server);
                PlayerStateManager.prepare(owner.player()).join(); // fixture-only I/O
                var bundle = SereniteaPotManager.createStaging(owner.id, 1, 1);
                GameTestStorage.commitGeneration(bundle, Map.of(SereniteaPotDimension.OVERWORLD,
                    new SereniteaPotSlotRecord("minecraft:overworld", 0, 80, 0, 0)), 0);
                checkPermission(false);
                enter();
                phase = 1;
            } else if (phase == 1) {
                checkPermission(true);
                if (!PlayerStateManager.prepareReturn(owner.player()).isDone()) return;
                helper.assertTrue(SereniteaPotTravelService.leave(owner.player()) == SereniteaPotTravelService.Success.INSTANCE,
                    "Owner leave failed");
                phase = 2;
            } else if (phase == 2) {
                checkPermission(false);
                enter();
                phase = 3;
            } else {
                checkPermission(true);
                cleanup();
                finished = true;
                helper.succeed();
            }
        }

        void enter() {
            helper.assertTrue(SereniteaPotTravelService.enter(owner.player(), owner.id) == SereniteaPotTravelService.Success.INSTANCE,
                "Owner entry failed");
        }

        void checkPermission(boolean allowed) {
            helper.assertTrue(AxiomServer.hasPermission(owner.player(), AxiomPermission.ALL) == allowed,
                "Unexpected Axiom permission in phase " + phase);
        }

        void cleanup() {
            if (owner == null) return;
            owner.close();
            GameTestStorage.deleteAndReset(helper.getLevel().getServer(), owner.id);
            SereniteaPotManager.catalog().getPlayers().remove(owner.id);
        }
    }
}
