package io.github.yufeiyufei888.hearthcrew.mixin;

import org.spongepowered.asm.mixin.extensibility.*;
import org.objectweb.asm.tree.ClassNode;
import java.util.*;

/** Decide before Minecraft classes load: exactly one body implementation owns hooks. */
public final class LegacyMixinSelector implements IMixinConfigPlugin {
    public void onLoad(String mixinPackage) {}
    public String getRefMapperConfig(){return null;}
    public boolean shouldApplyMixin(String target,String mixin){return !"numen".equals(io.github.yufeiyufei888.hearthcrew.backend.BackendSelection.mode());}
    public void acceptTargets(Set<String> mine,Set<String> other) {}
    public List<String> getMixins(){return null;}
    public void preApply(String target,ClassNode node,String mixin,IMixinInfo info) {}
    public void postApply(String target,ClassNode node,String mixin,IMixinInfo info) {}
}
