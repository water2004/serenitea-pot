package org.edtp.sereniteapot.mixin.accessor;

import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.entity.PersistentEntitySectionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Exposes Vanilla's entity load inbox so bounded server-thread tasks can pump completed loads. */
@Mixin(ServerLevel.class)
public interface ServerLevelEntityManagerAccessor {
    @Accessor("entityManager")
    PersistentEntitySectionManager<Entity> sereniteapot$getEntityManager();
}
