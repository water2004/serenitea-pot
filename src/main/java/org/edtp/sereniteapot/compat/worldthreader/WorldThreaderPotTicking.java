package org.edtp.sereniteapot.compat.worldthreader;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import no2.worldthreader.common.ServerWorldTicking;
import no2.worldthreader.common.WorldThreaderTickPhase;
import no2.worldthreader.common.mixin_support.interfaces.ServerWorldExtended;
import org.edtp.sereniteapot.model.SereniteaPotDimension;
import org.edtp.sereniteapot.performance.SereniteaPotScheduler;

import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/** Executes pot levels inside the matching vanilla dimension thread and WT phase. */
public final class WorldThreaderPotTicking {
    private static final ThreadLocal<Map<ServerLevel, Collection<Entity>>> arrivals =
        ThreadLocal.withInitial(IdentityHashMap::new);
    private static final ThreadLocal<Boolean> groupedPotTick = ThreadLocal.withInitial(() -> false);

    private WorldThreaderPotTicking() {
    }

    public static void tickWorldPhase(
        MinecraftServer server,
        SereniteaPotDimension lane,
        BooleanSupplier shouldKeepTicking
    ) {
        for (UUID owner : SereniteaPotScheduler.tickOrder()) {
            ServerLevel level = SereniteaPotScheduler.activeLevel(server, owner, lane);
            boolean ran = level != null && SereniteaPotScheduler.beforeThreadedLevelTick(level);
            long started = 0L;
            try {
                if (ran) {
                    ((ServerWorldExtended) level).worldthreader$setTickPhase(WorldThreaderTickPhase.WORLD_TICK);
                    started = System.nanoTime();
                    groupedPotTick.set(true);
                    try {
                        level.tick(shouldKeepTicking);
                    } finally {
                        groupedPotTick.remove();
                    }
                }
            } catch (Throwable throwable) {
                SereniteaPotScheduler.abortThreadedTick();
                throw throwable;
            } finally {
                long elapsed = ran ? System.nanoTime() - started : 0L;
                if (!SereniteaPotScheduler.finishThreadedOwner(owner, lane, elapsed, ran)) return;
            }
        }
    }

    public static boolean isGroupedPotTick() {
        return groupedPotTick.get();
    }

    public static void receiveTeleports(MinecraftServer server, SereniteaPotDimension lane) {
        Map<ServerLevel, Collection<Entity>> laneArrivals = arrivals.get();
        laneArrivals.clear();
        forExecutedLevel(server, lane, level -> {
            ((ServerWorldExtended) level).worldthreader$setTickPhase(WorldThreaderTickPhase.RECEIVE_TELEPORTS);
            laneArrivals.put(level, ServerWorldTicking.finishTeleportsToWorld(level));
        });
    }

    public static void tickArrivals(MinecraftServer server, SereniteaPotDimension lane) {
        Map<ServerLevel, Collection<Entity>> laneArrivals = arrivals.get();
        forExecutedLevel(server, lane, level -> {
            ((ServerWorldExtended) level).worldthreader$setTickPhase(WorldThreaderTickPhase.TICK_AFTER_TELEPORT);
            Collection<Entity> entities = laneArrivals.get(level);
            if (entities == null) return;
            for (Entity entity : entities) {
                if (entity.level() == level && entity.isAlive()) level.tickNonPassenger(entity);
            }
        });
    }

    public static void recoverTeleports(MinecraftServer server, SereniteaPotDimension lane) {
        forExecutedLevel(server, lane, level -> {
            ((ServerWorldExtended) level).worldthreader$setTickPhase(WorldThreaderTickPhase.RECOVER_FAILED_TELEPORTS);
            ServerWorldTicking.recoverFailedTeleports(level);
        });
    }

    public static void finishTick(MinecraftServer server, SereniteaPotDimension lane) {
        forExecutedLevel(server, lane, level ->
            ((ServerWorldExtended) level).worldthreader$setTickPhase(WorldThreaderTickPhase.NONE)
        );
        arrivals.remove();
    }

    private static void forExecutedLevel(
        MinecraftServer server,
        SereniteaPotDimension lane,
        java.util.function.Consumer<ServerLevel> action
    ) {
        for (UUID owner : SereniteaPotScheduler.threadedExecutedOwners()) {
            ServerLevel level = SereniteaPotScheduler.activeLevel(server, owner, lane);
            if (level != null) action.accept(level);
        }
    }
}
