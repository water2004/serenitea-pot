package org.edtp.sereniteapot.compat.worldthreader;

import net.minecraft.server.level.ServerLevel;
import no2.worldthreader.common.thread.ThreadOwnedObject;

/** Runtime bridge implemented by the exact WorldThreader 3.1.0 manager mixin. */
public interface WorldThreaderGroupAccess {
    ThreadOwnedObject[] sereniteapot$ownedObjects(ServerLevel anchor, ThreadOwnedObject[] fallback);
}
