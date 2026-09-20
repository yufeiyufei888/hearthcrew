package io.github.yufeiyufei888.hearthcrew.entity;

import java.util.UUID;
import java.util.List;
import java.util.HashSet;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;

/** Immutable desired operation; all execution remains in BodyExecutor. */
public record BodyOrder(Kind kind, BlockPos position, UUID target, int count, ResourceLocation resource,
                        List<BuildStep> steps, List<BodyOrder> actions, List<ResourceLocation> candidates, int radius, int accessBudget, Preparation preparation) {
    public record Preparation(boolean enabled,int maxDepth,int maxSteps,int maxBreaks){
        public Preparation{if(maxDepth<1||maxDepth>6||maxSteps<1||maxSteps>16||maxBreaks<0||maxBreaks>64)throw new IllegalArgumentException("preparation bounds: depth 1..6, steps 1..16, breaks 0..64");}
        public static Preparation disabled(){return new Preparation(false,6,16,64);}
        public static Preparation standard(){return new Preparation(true,6,16,64);}
    }
    public BodyOrder(Kind kind,BlockPos position,UUID target,int count,ResourceLocation resource,List<BuildStep> steps,List<BodyOrder> actions,List<ResourceLocation> candidates,int radius,int accessBudget){
        this(kind,position,target,count,resource,steps,actions,candidates,radius,accessBudget,Preparation.disabled());
    }
    public BodyOrder(Kind kind,BlockPos position,UUID target,int count,ResourceLocation resource,List<BuildStep> steps,List<BodyOrder> actions,List<ResourceLocation> candidates,int radius) {
        this(kind,position,target,count,resource,steps,actions,candidates,radius,16);
    }
    public enum Kind { YIELD, SEQUENCE, COLLECT_RESOURCE, MOVE, FOLLOW, MINE, EXCAVATE, GATHER, PLACE, BUILD, EAT, SLEEP, ATTACK, GUARD, SELF_DEFENCE, BREATHE, PICKUP, PORTAL, WAIT, CRAFT, TRANSFER, SELECT, USE_ITEM, INTERACT, EQUIP, STORE, TAKE, PROCESS, COLLECT_PROCESS, SWIM, EXPLORE, LAUNCH_BOAT, BOARD_BOAT, SAIL, DISEMBARK }
    public record BuildStep(BlockPos position, ResourceLocation block) {
        public BuildStep {
            if (position == null || block == null) throw new IllegalArgumentException("build step position/block required");
            position = position.immutable();
            if (Math.abs((long) position.getX()) > 30_000_000L || Math.abs((long) position.getZ()) > 30_000_000L
                    || position.getY() < -2_048 || position.getY() > 2_048) {
                throw new IllegalArgumentException("build step position outside protocol bounds");
            }
        }
    }
    public BodyOrder(Kind kind,BlockPos position,UUID target,int count,ResourceLocation resource,List<BuildStep> steps){this(kind,position,target,count,resource,steps,List.of(),List.of(),32);}
    public BodyOrder(Kind kind, BlockPos position, UUID target, int count) { this(kind, position, target, count, null, List.of()); }
    public BodyOrder(Kind kind, BlockPos position, UUID target, int count, ResourceLocation resource) {
        this(kind, position, target, count, resource, List.of());
    }
    public BodyOrder {
        if(preparation==null)preparation=Preparation.disabled();
        if(preparation.enabled()&&!java.util.Set.of(Kind.COLLECT_RESOURCE,Kind.MINE,Kind.EXCAVATE,Kind.CRAFT,Kind.SEQUENCE).contains(kind))throw new IllegalArgumentException("preparation requires resource, mining, excavation, crafting or sequence");
        if(accessBudget<0||accessBudget>64)throw new IllegalArgumentException("accessBudget outside 0..64");
        actions=List.copyOf(actions==null?List.of():actions);candidates=List.copyOf(candidates==null?List.of():candidates);if(radius==0)radius=32;
        if(radius<1||radius>32)throw new IllegalArgumentException("radius outside 1..32");
        if(kind==Kind.SEQUENCE){if(actions.isEmpty()||actions.size()>8||actions.stream().anyMatch(a->java.util.Set.of(Kind.SEQUENCE,Kind.FOLLOW,Kind.GUARD,Kind.SELF_DEFENCE,Kind.BREATHE).contains(a.kind())))throw new IllegalArgumentException("invalid SEQUENCE steps");}
        else if(!actions.isEmpty())throw new IllegalArgumentException("actions only for SEQUENCE");
        if(kind==Kind.COLLECT_RESOURCE){if(resource==null||count<1||count>64||candidates.size()>16)throw new IllegalArgumentException("COLLECT_RESOURCE requires resource/count/candidates");}
        else if(!candidates.isEmpty())throw new IllegalArgumentException("candidates only for COLLECT_RESOURCE");
        if (kind == null || count < 0) throw new IllegalArgumentException("invalid body order");
        if (position != null) position = position.immutable();
        steps = List.copyOf(steps == null ? List.of() : steps);
        if (kind == Kind.MINE) { if(count==0)count=1; if(position==null||count>64||target!=null)throw new IllegalArgumentException("MINE requires position and count 1..64"); }
        if (kind == Kind.EXCAVATE && (position==null || count<1 || count>64 || target!=null || resource!=null)) throw new IllegalArgumentException("EXCAVATE requires position and count 1..64");
        if (kind == Kind.BUILD) {
            if (position != null || target != null || resource != null || count != 0) {
                throw new IllegalArgumentException("BUILD accepts only steps");
            }
            if (steps.isEmpty() || steps.size() > 16) throw new IllegalArgumentException("build requires 1..16 steps");
            var positions = new HashSet<BlockPos>();
            for (BuildStep step : steps) if (!positions.add(step.position())) throw new IllegalArgumentException("build step positions must be unique");
        } else if (kind == Kind.GATHER) {
            if (position != null || target != null || !steps.isEmpty() || resource == null || count < 1 || count > 64) {
                throw new IllegalArgumentException("GATHER requires only a resource and count 1..64");
            }
            if (!supportedGatherResource(resource)) throw new IllegalArgumentException("unsupported GATHER resource");
        } else if (!steps.isEmpty()) {
            throw new IllegalArgumentException("only BUILD may contain build steps");
        }
    }
    public static BodyOrder move(BlockPos position) { return new BodyOrder(Kind.MOVE, position, null, 0); }
    public static BodyOrder follow(UUID target) { return new BodyOrder(Kind.FOLLOW, null, target, 0); }
    public static BodyOrder mine(BlockPos position) { return new BodyOrder(Kind.MINE, position, null, 1); }
    public static BodyOrder gather(ResourceLocation resource, int count) { return new BodyOrder(Kind.GATHER, null, null, count, resource); }
    public static BodyOrder eat() { return new BodyOrder(Kind.EAT, null, null, 0); }

    /** P1 first-play scope: only unstripped oak/birch trunk blocks are accepted. */
    public static boolean supportedGatherResource(ResourceLocation resource) {
        return resource != null && "minecraft".equals(resource.getNamespace())
                && java.util.Set.of("oak_log","birch_log","spruce_log","jungle_log","acacia_log","dark_oak_log","mangrove_log","cherry_log").contains(resource.getPath());
    }
}
