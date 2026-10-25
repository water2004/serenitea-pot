package org.edtp.sereniteapot.gametest;

import net.fabricmc.fabric.api.gametest.v1.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.permissions.LevelBasedPermissionSet;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameType;
import org.edtp.sereniteapot.level.*;
import org.edtp.sereniteapot.model.SereniteaPotDimension;
import org.edtp.sereniteapot.model.SereniteaPotSlotRecord;
import org.edtp.sereniteapot.player.PlayerStateManager;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/** Real admission, dimension transfer, logout, command revocation and lifecycle ticks. */
public final class ResidentGameTest {
    @GameTest(maxTicks = 400)
    public void residentsKeepPotOpenAfterOwnerLogoutAndCanReopenIt(GameTestHelper helper) {
        run(helper, Scenario.LOGOUT);
    }

    @GameTest(maxTicks = 400)
    public void revokingLastResidentEvictsAllAndDeniesReentry(GameTestHelper helper) {
        run(helper, Scenario.REVOKE);
    }

    @GameTest(maxTicks = 400)
    public void multipleResidentsKeepPotLoadedUntilLastDeparts(GameTestHelper helper) {
        run(helper, Scenario.MULTIPLE);
    }

    @GameTest(maxTicks = 400)
    public void disableOverridesResidentLoadingRights(GameTestHelper helper) {
        run(helper, Scenario.DISABLE);
    }

    @GameTest(maxTicks = 400)
    public void operatorsNeedAResidentAndCannotKeepPotLoaded(GameTestHelper helper) {
        run(helper, Scenario.OPERATOR);
    }

    private enum Scenario { LOGOUT, REVOKE, MULTIPLE, DISABLE, OPERATOR }

    private static void run(GameTestHelper helper, Scenario scenario) {
        var probe = new Probe(helper, scenario);
        var queued = new AtomicBoolean();
        helper.onEachTick(() -> {
            if (probe.failure != null) helper.fail("Resident " + scenario + ": " + probe.failure);
            if (probe.finished || !queued.compareAndSet(false, true)) return;
            helper.getLevel().getServer().execute(() -> {
                try { probe.step(); }
                catch (Throwable error) {
                    probe.finished = true;
                    try { probe.cleanup(); } catch (Throwable cleanup) { error.addSuppressed(cleanup); }
                    probe.failure = error;
                } finally { queued.set(false); }
            });
        });
    }

    private static final class Probe {
        final GameTestHelper helper;
        final Scenario scenario;
        ConnectedTestPlayer owner;
        ConnectedTestPlayer resident;
        ConnectedTestPlayer guest;
        int phase;
        int waited;
        boolean finished;
        volatile Throwable failure;

        Probe(GameTestHelper helper, Scenario scenario) { this.helper = helper; this.scenario = scenario; }

        void step() throws Exception {
            var server = helper.getLevel().getServer();
            if (phase == 0) {
                String suffix = UUID.randomUUID().toString().substring(0, 6);
                owner = new ConnectedTestPlayer(server, "owner" + suffix);
                resident = new ConnectedTestPlayer(server, "resident" + suffix);
                guest = new ConnectedTestPlayer(server, "guest" + suffix);
                for (var fixture : new ConnectedTestPlayer[]{owner, resident, guest}) {
                    PlayerStateManager.prepare(fixture.player()).join(); // fixture-only I/O
                    fixture.player().setGameMode(GameType.SURVIVAL);
                    fixture.player().getInventory().setItem(0, new ItemStack(Items.DIAMOND, 7));
                    server.services().nameToIdCache().add(new NameAndId(fixture.player().getGameProfile()));
                }
                var bundle = SereniteaPotManager.createStaging(owner.id, 1, 1);
                GameTestStorage.commitGeneration(bundle, Map.of(SereniteaPotDimension.OVERWORLD,
                    new SereniteaPotSlotRecord("minecraft:overworld", 0, 80, 0, 0)), 0);
                check(command(owner, "resident add " + resident.player().getScoreboardName()) == 1, "Invitation failed");
                check(command(owner, "resident add " + resident.player().getScoreboardName()) == 0, "Duplicate accepted");
                check(command(resident, "resident add " + guest.player().getScoreboardName()) == 0,
                    "Resident could manage the owner's invitations");
                enter(owner);
                check(SereniteaPotInvitationService.request(guest.player(), owner.id)
                    == SereniteaPotInvitationService.Success.INSTANCE, "Temporary request failed");
                check(SereniteaPotInvitationService.approve(owner.player(), guest.id, null)
                    == SereniteaPotInvitationService.Success.INSTANCE, "Temporary approval failed");
                enter(resident);
                check(resident.player().gameMode.isCreative() && resident.player().getAbilities().invulnerable,
                    "Resident must be creative");
                check(resident.player().getInventory().isEmpty(), "Public inventory leaked into pot");
                check(!server.getCommands().getDispatcher().getRoot().getChild("give").getRequirement()
                    .test(resident.player().createCommandSourceStack()), "Resident received owner command permissions");
                if (scenario == Scenario.MULTIPLE) {
                    check(command(owner, "resident add " + guest.player().getScoreboardName()) == 1, "Second invitation failed");
                }
                if (scenario == Scenario.LOGOUT) owner.close();
                else leave(owner);
                phase = 1;
            } else if (phase == 1) {
                check(SereniteaPotManager.loaded(owner.id) != null, "Owner departure unloaded a resident's pot");
                check(inside(guest), "Temporary guest was removed while a resident remained");
                if (++waited < 4) return;
                var bundle = SereniteaPotManager.loaded(owner.id);
                check(resident.player().teleportTo(bundle.get(SereniteaPotDimension.NETHER),
                    8, 80, 8, Set.of(), 0, 0, true), "Resident could not cross pot dimensions");
                if (scenario == Scenario.REVOKE) {
                    check(command(owner, "resident remove " + resident.id) == 1, "Revocation failed");
                } else if (scenario == Scenario.DISABLE) {
                    server.getCommands().getDispatcher().execute("sereniteapot admin disable "
                        + owner.player().getScoreboardName(), server.createCommandSourceStack());
                } else {
                    if (scenario == Scenario.MULTIPLE) {
                        check(command(owner, "resident remove " + resident.id) == 1, "Revocation failed");
                        check(inside(resident), "Revocation needlessly kicked an admitted visitor");
                    }
                    leave(resident);
                }
                phase = 2;
                waited = 0;
            } else if (phase == 2) {
                if (scenario == Scenario.MULTIPLE) {
                    check(SereniteaPotManager.loaded(owner.id) != null && inside(guest),
                        "First resident departure unloaded the second resident");
                    if (++waited < 4) return;
                    guest.close();
                    phase = 5;
                    return;
                }
                if (SereniteaPotManager.loaded(owner.id) != null) return;
                check(!inside(guest) && !inside(resident), "Close left players in removed levels");
                check(guest.player().getInventory().getItem(0).is(Items.DIAMOND)
                    && guest.player().gameMode.getGameModeForPlayer() == GameType.SURVIVAL,
                    "Guest public state not restored");
                if (scenario != Scenario.LOGOUT && scenario != Scenario.OPERATOR) {
                    check(SereniteaPotTravelService.enter(resident.player(), owner.id)
                        instanceof SereniteaPotTravelService.Rejected, "Revoked/disabled resident reopened pot");
                    check(SereniteaPotManager.loaded(owner.id) == null, "Rejected entry loaded pot");
                    finish();
                    return;
                }
                if (scenario == Scenario.OPERATOR) {
                    server.getPlayerList().op(new NameAndId(guest.player().getGameProfile()),
                        Optional.of(LevelBasedPermissionSet.OWNER), Optional.empty());
                    check(SereniteaPotTravelService.enter(guest.player(), owner.id)
                        instanceof SereniteaPotTravelService.Rejected, "OP loaded an unoccupied pot");
                }
                // Suggestions and entry must work even when the owner is offline.
                var dispatcher = server.getCommands().getDispatcher();
                var suggestions = dispatcher.getCompletionSuggestions(dispatcher.parse(
                    "sereniteapot enter ", resident.player().createCommandSourceStack())).join();
                String ownerName = server.services().nameToIdCache().get(owner.id).orElseThrow().name();
                check(suggestions.getList().stream().anyMatch(s -> s.getText().equals(ownerName)),
                    "Offline invited owner missing from completion");
                SereniteaPotManager.record(owner.id).setFrozen(true);
                command(resident, "enter " + owner.id);
                phase = 3;
            } else if (phase == 3) {
                if (!inside(resident)) return;
                check(resident.player().gameMode.isCreative(), "Reentry lost creative mode");
                var level = resident.player().level();
                if (scenario == Scenario.OPERATOR) {
                    enter(guest);
                    check(inside(guest), "OP could not visit a resident-hosted pot");
                } else {
                    check(!guest.player().teleportTo(level, 8, 80, 8, Set.of(), 0, 0, true),
                        "Consumed temporary grant allowed unauthorized reentry");
                    check(!inside(guest), "External teleport bypassed invitations");
                }
                // Last resident logout, not only /leave, must unload all three dimensions.
                resident.close();
                phase = 5;
            } else if (phase == 5) {
                if (SereniteaPotManager.loaded(owner.id) != null) return;
                if (scenario == Scenario.OPERATOR) check(!inside(guest), "OP was left in unloaded pot");
                finish();
            }
        }

        int command(ConnectedTestPlayer player, String suffix) throws Exception {
            return helper.getLevel().getServer().getCommands().getDispatcher().execute(
                "sereniteapot " + suffix, player.player().createCommandSourceStack());
        }

        void enter(ConnectedTestPlayer player) {
            var result = SereniteaPotTravelService.enter(player.player(), owner.id);
            check(result == SereniteaPotTravelService.Success.INSTANCE, "Entry failed: " + result);
        }

        void leave(ConnectedTestPlayer player) {
            PlayerStateManager.prepareReturn(player.player()).join(); // fixture-only I/O
            check(SereniteaPotTravelService.leave(player.player()) == SereniteaPotTravelService.Success.INSTANCE,
                "Leave failed");
        }

        boolean inside(ConnectedTestPlayer player) {
            if (player.player() == null) return false;
            var identity = SereniteaPotLevelKeys.identify(player.player().level().dimension());
            return identity != null && identity.owner().equals(owner.id);
        }

        void check(boolean condition, String message) { helper.assertTrue(condition, message); }

        void finish() {
            cleanup();
            finished = true;
            helper.succeed();
        }

        void cleanup() {
            if (guest != null) {
                if (scenario == Scenario.OPERATOR && guest.player() != null) {
                    helper.getLevel().getServer().getPlayerList().deop(new NameAndId(guest.player().getGameProfile()));
                }
                guest.close();
            }
            if (resident != null) resident.close();
            if (owner != null) {
                owner.close();
                GameTestStorage.deleteAndReset(helper.getLevel().getServer(), owner.id);
                SereniteaPotManager.catalog().getPlayers().remove(owner.id);
            }
        }
    }
}
