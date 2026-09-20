package io.github.yufeiyufei888.hearthcrew.backend.numen;

import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import java.util.*;

/** Host-provided protection; unknown host policy never permits autonomous mutation. */
public final class BackendProtection {
    @FunctionalInterface public interface Policy { Set<BlockPos> protectedPositions(ServerLevel level); }
    private static final Map<MinecraftServer,Policy> POLICIES=new IdentityHashMap<>();
    private BackendProtection() {}
    public static void configure(MinecraftServer server,Policy policy) {
        if(!server.isSameThread())throw new IllegalStateException("Server thread required");
        POLICIES.put(server,Objects.requireNonNull(policy));
    }
    static void requireConfigured(MinecraftServer server) {
        if(!POLICIES.containsKey(server))throw new IllegalStateException("BACKEND_PROTECTION_NOT_CONFIGURED");
    }
    static boolean configured(MinecraftServer server){return POLICIES.containsKey(server);}
    static Set<BlockPos> positions(ServerLevel level) {
        requireConfigured(level.getServer());
        return Set.copyOf(POLICIES.get(level.getServer()).protectedPositions(level));
    }
    static boolean isProtected(ServerLevel level,BlockPos p) {
        // Membership is checked on the server thread. Only worker snapshots need a copy;
        // copying the entire protection set for each of 4096 scan cells is quadratic work.
        var policy=POLICIES.get(level.getServer());return policy==null||policy.protectedPositions(level).contains(p);
    }
    static void release(MinecraftServer server){POLICIES.remove(server);}
}
