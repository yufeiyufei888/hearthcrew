package io.github.yufeiyufei888.hearthcrew.backend.numen.mixin;

import org.spongepowered.asm.mixin.extensibility.*;
import org.objectweb.asm.tree.ClassNode;
import java.util.*;

/** A comparison/legacy launch must not install a second ServerPlayer respawn redirect. */
public final class BackendMixinSelector implements IMixinConfigPlugin {
    public void onLoad(String mixinPackage) {}
    public String getRefMapperConfig(){return null;}
    public boolean shouldApplyMixin(String target,String mixin){
        var mode=System.getProperty("hearthcrew.executionBackend");
        return "numen".equals(io.github.yufeiyufei888.hearthcrew.backend.BackendSelection.mode())||mode==null&&Boolean.getBoolean("hearthcrew.backendLab");
    }
    public void acceptTargets(Set<String> mine,Set<String> other) {}
    public List<String> getMixins(){return null;}
    public void preApply(String target,ClassNode node,String mixin,IMixinInfo info) {}
    public void postApply(String target,ClassNode node,String mixin,IMixinInfo info) {}
}
