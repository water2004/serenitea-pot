package org.edtp.sereniteapot.gametest.carpet;

import com.mojang.authlib.GameProfile;
import java.util.UUID;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

public final class CarpetTestProfiles {
    public static final Map<UUID, CompletableFuture<GameProfile>> pending = new ConcurrentHashMap<>();
    private CarpetTestProfiles() {}
}
