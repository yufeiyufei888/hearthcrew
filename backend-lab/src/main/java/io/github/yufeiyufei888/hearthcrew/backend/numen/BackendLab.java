package io.github.yufeiyufei888.hearthcrew.backend.numen;
import net.neoforged.fml.common.Mod;
import net.minecraft.core.BlockPos;
import java.util.*;
/** Test fixture policy only. Never included in the runtime artifact. */
@Mod("hearthcrew_backend_lab")
public final class BackendLab {
    static final Set<BlockPos> PROTECTED=new HashSet<>();
    public BackendLab() {
        if(!Boolean.getBoolean("hearthcrew.backendLab"))throw new IllegalStateException("Laboratory cannot run in gameplay");
        BackendRuntime.initialize();
    }
}
