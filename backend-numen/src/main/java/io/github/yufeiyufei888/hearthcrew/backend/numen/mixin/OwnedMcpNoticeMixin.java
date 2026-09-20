package io.github.yufeiyufei888.hearthcrew.backend.numen.mixin;

import io.github.yufeiyufei888.hearthcrew.backend.numen.NumenBackend;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Only suppress the legacy client's MCP notices for our local owned bodies.
 * No change to MCP server configuration, brain ownership or HearthCrew health reporting. */
@Mixin(targets="com.dwinovo.numen.mcp.server.McpMode",remap=false)
public abstract class OwnedMcpNoticeMixin {
    @Inject(method="clientTick",at=@At("HEAD"),cancellable=true,remap=false)
    private void hearthcrew$ownHealthOnly(CallbackInfo ci){if(NumenBackend.hasOwnedBodies())ci.cancel();}
}
