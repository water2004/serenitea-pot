package org.edtp.sereniteapot.adapters.worldthreader;

import net.minecraft.server.level.ServerLevel;
import no2.worldthreader.common.thread.ThreadOwnedObject;

import java.util.concurrent.Phaser;

/** Runtime bridge implemented by the exact WorldThreader 3.2.0 manager mixin. */
public interface WorldThreaderGroupAccess {
    ThreadOwnedObject[] sereniteapot$ownedObjects(ServerLevel anchor, ThreadOwnedObject[] fallback);

    /** Uses WT's yield/wait/reacquire protocol with the pot accounting barrier. */
    int sereniteapot$awaitBarrier(Phaser barrier);
}
