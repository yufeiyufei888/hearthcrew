package io.github.yufeiyufei888.hearthcrew.backend.numen;

import com.dwinovo.numen.entity.NumenPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import java.util.*;

/** Experimental per-request gate for vanilla mutation events. Not a planning filter. */
final class MutationAllowance {
    final NumenPlayer body;
    final BlockPos origin;
    final net.minecraft.resources.ResourceKey<net.minecraft.world.level.Level> dimension;
    Set<Block> primary;
    Set<BlockPos> placements;
    final int accessBudget;
    final int preparationBudget,radius;
    private record BreakEvidence(BlockState state,boolean primary,boolean preparation) {}
    private final Map<BlockPos,BreakEvidence> pending=new HashMap<>();
    private boolean preparation,restrictPrimary;
    private int phaseLimit=64,phaseStart;
    Set<BlockPos> primaryPositions=Set.of();
    private final Set<BlockPos> placed=new HashSet<>();
    private final Map<BlockPos,BlockState> originalPlacements=new HashMap<>();
    private int accessSpent,primaryBroken,preparationBroken,denied;
    MutationAllowance(NumenPlayer body,Set<Block> primary,Set<BlockPos> placements,int accessBudget) {this(body,primary,placements,accessBudget,64,32);}
    MutationAllowance(NumenPlayer body,Set<Block> primary,Set<BlockPos> placements,int accessBudget,int preparationBudget,int radius) {
        if(preparationBudget<0||preparationBudget>64||radius<1||radius>32)throw new IllegalArgumentException("preparation bounds");
        this.preparationBudget=preparationBudget;this.radius=radius;
        if(accessBudget<0||accessBudget>64)throw new IllegalArgumentException("accessBudget");
        this.body=body;origin=body.blockPosition().immutable();dimension=body.level().dimension();this.primary=Set.copyOf(primary);
        this.placements=Set.copyOf(placements);this.accessBudget=accessBudget;
        for(var p:placements)originalPlacements.put(p,body.level().getBlockState(p));
    }
    boolean beforeBreak(BlockPos position) {
        var p=position.immutable();var state=body.level().getBlockState(p);
        boolean sameDimension=dimension.equals(body.level().dimension());
        if(!sameDimension||!inside(p)||BackendProtection.isProtected(body.serverLevel(),p)||body.level().getBlockEntity(p)!=null
                ||BackendJournal.get(body.server).structureAt(dimension,p)||p.equals(body.blockPosition().below())||!TerrainSafety.dryStableCell(body.level(),p)) {denied++;return false;}
        if(state.requiresCorrectToolForDrops()&&!body.getMainHandItem().isCorrectToolForDrops(state)){denied++;return false;}
        boolean main=primary.contains(state.getBlock());
        if(main&&restrictPrimary&&!primaryPositions.contains(p)){denied++;return false;}
        long pendingPreparation=pending.values().stream().filter(BreakEvidence::preparation).count();
        if(main&&preparation&&(preparationBroken+pendingPreparation>=preparationBudget||preparationBroken-phaseStart+pendingPreparation>=phaseLimit)){denied++;return false;}
        if(main&&restrictPrimary&&!preparation&&primaryBroken-phaseStart+pending.values().stream().filter(e->e.primary()&&!e.preparation()).count()>=phaseLimit){denied++;return false;}
        if(!main) {
            long reserved=pending.values().stream().filter(s->!s.primary()).count();
            if(p.equals(body.blockPosition().below())||!state.getFluidState().isEmpty()||accessSpent+reserved>=accessBudget) {denied++;return false;}
        }
        pending.putIfAbsent(p,new BreakEvidence(state,main,main&&preparation));return true;
    }
    boolean beforePlace(BlockPos p) {
        if(!dimension.equals(body.level().dimension())||!inside(p)||!placements.contains(p)||BackendProtection.isProtected(body.serverLevel(),p)||BackendJournal.get(body.server).structureAt(dimension,p)) {denied++;return false;}
        placed.add(p.immutable());return true;
    }
    boolean inside(BlockPos p) {return Math.max(Math.max(Math.abs(p.getX()-origin.getX()),Math.abs(p.getY()-origin.getY())),Math.abs(p.getZ()-origin.getZ()))<=radius;}
    void phase(Set<Block> nextPrimary,Set<BlockPos> nextPlacements) {
        reconcile();primary=Set.copyOf(nextPrimary);placements=Set.copyOf(nextPlacements);originalPlacements.clear();preparation=false;restrictPrimary=false;primaryPositions=Set.of();
        for(var p:placements)originalPlacements.put(p,body.level().getBlockState(p));
    }
    void preparationPhase(SourceProbe.Source source,int quantity) {phase(Set.of(source.block()),Set.of());preparation=true;restrictPrimary=true;primaryPositions=source.positions();phaseStart=preparationBroken;phaseLimit=quantity;}
    void collectionPhase(SourceProbe.Source source,int quantity) {phase(Set.of(source.block()),Set.of());restrictPrimary=true;primaryPositions=source.positions();phaseStart=primaryBroken;phaseLimit=quantity;}
    private boolean exhausted(){return restrictPrimary&&(preparation?preparationBroken>=preparationBudget||preparationBroken-phaseStart>=phaseLimit:primaryBroken-phaseStart>=phaseLimit);}
    boolean phaseExhausted(){return exhausted();}
    boolean candidateAllowed(BlockPos p){return (!restrictPrimary||primaryPositions.contains(p))&&!exhausted();}
    Set<BlockPos> currentTargetPositions(){return exhausted()?Set.of():primaryPositions;}
    boolean restricted(){return restrictPrimary;}
    int preparationBroken(){return preparationBroken;}
    int remaining(){return Math.max(0,accessBudget-accessSpent);}
    int accessSpent(){return accessSpent;}
    int primaryBroken(){return primaryBroken;}
    void reconcile() {
        // Native break is synchronous; count actual changed blocks after the executor tick.
        for(var entry:pending.entrySet())if(!body.level().getBlockState(entry.getKey()).is(entry.getValue().state().getBlock())) {
            if(entry.getValue().preparation())preparationBroken++;else if(entry.getValue().primary())primaryBroken++;else accessSpent++;
        }
        pending.clear();
        for(var p:placed)if(originalPlacements.containsKey(p)&&!body.level().getBlockState(p).is(originalPlacements.get(p).getBlock())) {
            BackendJournal.get(body.server).recordStructure(dimension,p);
            if(!FacilityAccess.isFacility(originalPlacements.get(p))&&FacilityAccess.isFacility(body.level().getBlockState(p)))BackendJournal.get(body.server).publish(dimension,p);
        }
        placed.clear();
    }
    Map<String,Object> evidence() {return Map.of("primaryBroken",primaryBroken,"preparationBroken",preparationBroken,"preparationBudget",preparationBudget,"accessSpent",accessSpent,"accessBudget",accessBudget,"deniedMutations",denied);}
}
