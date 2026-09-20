package io.github.yufeiyufei888.hearthcrew.entity;

import io.github.yufeiyufei888.hearthcrew.gameplay.TravelTerrain;
import io.github.yufeiyufei888.hearthcrew.gameplay.TargetInspection;

import com.mojang.authlib.GameProfile;
import io.github.yufeiyufei888.hearthcrew.HearthCrew;
import io.github.yufeiyufei888.hearthcrew.runtime.CrewWorldData;
import io.github.yufeiyufei888.hearthcrew.runtime.WorldEvents;
import io.github.yufeiyufei888.hearthcrew.kernel.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.entity.*;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.item.*;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.*;
import net.neoforged.neoforge.common.util.FakePlayer;

/** The only caller allowed to initiate companion movement/interaction/world effects. */
public final class BodyExecutor {
    /** One server-observed BUILD step; the block identity is part of the evidence. */
    public record BuildObservation(BlockPos position, ResourceLocation block, boolean existing, boolean placed) {
        public BuildObservation {
            if (position == null || block == null || existing == placed) {
                throw new IllegalArgumentException("invalid build observation");
            }
            position = position.immutable();
        }
    }
    /** Server-observed state needed to safely resume a preempted action. */
    public record Checkpoint(String dimension, Vec3 position, int selectedSlot,
                             ItemStack carriedItem, BlockPos miningTarget, BlockState miningState,
                             float miningProgress, boolean miningBroken, int miningBreakTick,
                             int actionTicks, Map<UUID, Integer> miningDrops,
                             Vec3 buildOrigin, List<BuildObservation> buildSteps,
                             Vec3 gatherOrigin, BlockPos gatherTarget,
                             ResourceLocation gatherResource, int gatherCollected,
                             List<BlockPos> gatherCandidates, int gatherCandidateIndex,
                             boolean gatherRequestedDropObserved) {
        public Checkpoint {
            if (dimension == null || position == null || carriedItem == null || miningDrops == null) {
                throw new IllegalArgumentException("checkpoint values must be present");
            }
            carriedItem = carriedItem.copy();
            miningTarget = miningTarget == null ? null : miningTarget.immutable();
            miningDrops = Map.copyOf(miningDrops);
            buildSteps = List.copyOf(buildSteps == null ? List.of() : buildSteps);
            if (!buildSteps.isEmpty() && buildOrigin == null) throw new IllegalArgumentException("build observations require an origin");
            gatherTarget = gatherTarget == null ? null : gatherTarget.immutable();
            if (gatherCollected < 0 || gatherCollected > 64) throw new IllegalArgumentException("invalid gather count");
            if (gatherOrigin != null && gatherResource == null) throw new IllegalArgumentException("gather checkpoint resource missing");
            gatherCandidates = List.copyOf(gatherCandidates == null ? List.of() : gatherCandidates);
            if (gatherCandidates.size() > 64 || gatherCandidateIndex < 0 || gatherCandidateIndex > gatherCandidates.size())
                throw new IllegalArgumentException("invalid gather candidate checkpoint");
            gatherCandidates = gatherCandidates.stream().map(BlockPos::immutable).toList();
        }
    }
    private final CompanionEntity body;
    private final ActionArbiter<BodyOrder, Checkpoint> arbiter;
    private final Deque<ActionId> suspended = new ArrayDeque<>();
    /** Drop obligations survive the live-action scratch state being reset. */
    private final Map<ActionId, Map<UUID, Integer>> suspendedMiningDrops = new HashMap<>();
    /** Gather totals survive a GUARD/SAFETY preemption with the drop ledger. */
    private final Map<ActionId, Integer> suspendedGatherCollected = new HashMap<>();
    private final Map<ActionId, ActionRequest<BodyOrder>> submissions = new HashMap<>();
    private ActionId executing;
    private Vec3 lastProgress;
    private int stalledTicks;
    private int actionTicks;
    private int attackCooldown;
    private TravelProgress travelProgress;
    private BlockPos travelDestination;
    private BlockPos miningTarget;
    private Vec3 travelStart;
    private int travelStartAir;
    private String travelPhase = "idle";
    private int arrivalTicks;
    private int nextAirSearch;
    private BlockPos breathTarget;
    private int breathingTicks;
    private int wetTicks, nextWaterRecovery;
    private BlockPos recoveryShore;
    private final java.util.Set<BlockPos> rejectedShores=new java.util.HashSet<>();
    private final java.util.Set<ActionId> waterInterrupted=new java.util.HashSet<>();
    private final Map<String, Map<String,Object>> executionReports = new LinkedHashMap<>();
    private static final class DigWork {
        final BlockPos origin; final Set<BlockPos> broken=new HashSet<>(); ExcavationSearch search; List<ExcavationSearch.Cell> route; int index;
        BlockPos mining,start,target; int retries; BlockState state; float progress;final Map<String,Integer> rejected=new LinkedHashMap<>();
        DigWork(BlockPos origin){this.origin=origin.immutable();}
    }
    private final Map<ActionId,DigWork> digWorks=new HashMap<>();
    private final Set<ActionId> buildPreflightDone=new HashSet<>();
    private static ExcavationSearch.Cell cell(BlockPos p){return new ExcavationSearch.Cell(p.getX(),p.getY(),p.getZ());}
    private static BlockPos block(ExcavationSearch.Cell p){return new BlockPos(p.x(),p.y(),p.z());}
    private String digRejection="unknown";
    private int rejectDig(String reason){digRejection=reason;return -1;}
    /** Full swept column includes the high entry headroom when descending a stair. */
    private List<BlockPos> passageCells(BlockPos from,BlockPos to){
        var cells=new ArrayList<BlockPos>();
        for(int y=Math.max(from.getY(),to.getY())+1;y>=to.getY();y--)cells.add(new BlockPos(to.getX(),y,to.getZ()));
        return cells;
    }
    private int digEdge(BlockPos from,BlockPos to,BlockPos reserved) {return digEdge(from,to,reserved,Set.of());}
    private int digEdge(BlockPos from,BlockPos to,BlockPos reserved,Set<BlockPos> removed) {
        if(!body.level().hasChunkAt(to))return rejectDig("unloaded_boundary");
        if(to.getY()>from.getY()&&!TravelTerrain.clearIgnoring(body,Vec3.atBottomCenterOf(from).add(0,1,0),removed))return rejectDig("unchecked_overhead");
        var floor=to.below();
        if(removed.contains(floor))return rejectDig("planned_support_removed");
        if(body.level().getBlockState(floor).getCollisionShape(body.level(),floor).isEmpty()||TravelTerrain.dangerous(body,floor)||!body.level().getFluidState(floor).isEmpty())return rejectDig("missing_or_hazardous_floor");
        int breaks=0;
        for(var pos:passageCells(from,to)){
            if(pos.equals(reserved))return rejectDig("target_reserved_for_MINE");
            if(removed.contains(pos))continue;
            if(!TargetInspection.excavationBlock(body,pos)){
                // Existing partial collision supports are traversed, never destroyed as unknown terrain.
                if(TravelTerrain.standable(body,to)&&TravelTerrain.transition(body,from,to))return 0;
                return rejectDig("protected_liquid_unsupported_terrain_or_missing_tool");
            }
            for(var direction:Direction.values()) {var adjacent=pos.relative(direction);if(!body.level().hasChunkAt(adjacent)||!body.level().getFluidState(adjacent).isEmpty()||body.level().getBlockState(adjacent).getBlock() instanceof FallingBlock)return rejectDig("adjacent_liquid_falling_block_or_unloaded");}
            if(!body.level().getBlockState(pos).isAir())breaks++;
        }return breaks;
    }
    private static final class AccessAccount {
        final BlockPos origin; final Set<BlockPos> broken=new LinkedHashSet<>();
        AccessAccount(BlockPos origin){this.origin=origin.immutable();}
    }
    private final Map<ActionId,AccessAccount> accessAccounts=new HashMap<>();
    private final Map<ActionId,DigWork> accessWorks=new HashMap<>();
    private int accessLimit(){var snapshot=arbiter.activeSnapshot().orElseThrow();var order=effectiveOrder(snapshot.id(),snapshot.payload());if(!Set.of(BodyOrder.Kind.MINE,BodyOrder.Kind.COLLECT_RESOURCE,BodyOrder.Kind.PICKUP).contains(order.kind()))return 0;return Math.min(snapshot.payload().accessBudget(),order.accessBudget());}
    private enum Passage { WORKING, READY, BLOCKED }
    private String accessFailure="";
    private boolean accessTo(BlockPos target,boolean drop){
        var account=accessAccounts.computeIfAbsent(executing,id->new AccessAccount(TravelTerrain.supportedStart(body)));
        var work=accessWorks.get(executing);
        if(work==null||!target.equals(work.target)){work=new DigWork(account.origin);work.target=target.immutable();accessWorks.put(executing,work);}
        var result=advancePassage(work,target,drop?null:target,accessLimit(),account.broken,true,drop);
        if(result==Passage.BLOCKED)finish(miningBroken?ActionState.PARTIAL:ActionState.FAILED,accessFailure);
        return result==Passage.READY;
    }
    private void excavate(BodyOrder order){
        var work=digWorks.computeIfAbsent(executing,id->new DigWork(TravelTerrain.supportedStart(body)));
        var result=advancePassage(work,order.position(),order.position(),order.count(),work.broken,false,false);
        if(result==Passage.READY)complete("excavation route physically traversed; broken="+work.broken.size()+"; ore not mined");
        else if(result==Passage.BLOCKED)fail(accessFailure);
    }
    private Passage blockedPassage(String reason){accessFailure=reason;body.getNavigation().stop();return Passage.BLOCKED;}
    private Passage advancePassage(DigWork work,BlockPos target,BlockPos reserved,int limit,Set<BlockPos> spent,boolean auxiliary,boolean drop){
        if(body.isInWater()||body.isUnderWater())return blockedPassage("ACCESS_WET_FOOTING: choose a dry route");
        if(target.distSqr(work.origin)>1024||body.blockPosition().distSqr(work.origin)>1024)return blockedPassage("ACCESS_ORIGIN_BOUND: 32 blocks");
        if(work.search==null){
            work.start=TravelTerrain.supportedStart(body);
            work.search=new ExcavationSearch(cell(work.start),cell(target),Math.max(0,limit-spent.size()),(from,to,removedCells)->{
                Set<BlockPos> removed=new HashSet<>();removedCells.forEach(c->removed.add(block(c)));
                int cost=passageCells(block(from),block(to)).stream().anyMatch(p0->p0.distSqr(work.origin)>1024)?rejectDig("origin_radius"):digEdge(block(from),block(to),reserved,removed);
                if(cost<0){work.rejected.merge(digRejection,1,Integer::sum);return null;}
                Set<ExcavationSearch.Cell> changes=new HashSet<>();for(var p0:passageCells(block(from),block(to)))if(!removed.contains(p0)&&!body.level().getBlockState(p0).isAir())changes.add(cell(p0));return changes;
            },(at,removedCells)->{var p0=block(at);int horizontal=Math.abs(p0.getX()-target.getX())+Math.abs(p0.getZ()-target.getZ());
                if(drop)return Math.abs(p0.getY()-target.getY())<=1&&horizontal<=1;
                if(horizontal<1||horizontal>3||Math.abs(p0.getY()-target.getY())>2)return false;
                var feet=TravelTerrain.feetPoint(body,p0);var eye=feet.add(0,body.getEyeHeight(),0);
                if(eye.distanceToSqr(Vec3.atCenterOf(target))>20.25)return false;
                var removed=new HashSet<BlockPos>();removedCells.forEach(c->removed.add(block(c)));
                return TravelTerrain.sightIgnoring(body,eye,target,removed);
            },at->block(at).equals(reserved));
        }
        if(work.route==null){
            travelPhase=auxiliary?"access_searching":"excavation_scanning";
            var result=work.search.step(body.getNavigation().searchAllowance(64));
            recordExecution(executing.value(),Map.of("phase",travelPhase,"expanded",work.search.expanded(),"accessBroken",spent.size(),"accessBudget",limit,"rejections",Map.copyOf(work.rejected)));
            if(result==ExcavationSearch.State.BUDGET_EXHAUSTED)return blockedPassage("ACCESS_SEARCH_BUDGET_EXHAUSTED: expanded="+work.search.expanded()+"; route unknown, not proof of no route");
            if(result==ExcavationSearch.State.BLOCKED)return blockedPassage("ACCESS_BLOCKED: no safe route within budget="+limit+"; spent="+spent.size()+"; expanded="+work.search.expanded()+"; rejected="+work.rejected);
            if(result!=ExcavationSearch.State.FOUND)return Passage.WORKING;
            work.route=work.search.path();
        }
        if(work.index>=work.route.size()){
            var end=work.route.isEmpty()?work.start:block(work.route.getLast());
            if(!TravelTerrain.landedAt(body,end,.3)){
                if(!TravelTerrain.standable(body,end)||++stalledTicks>100)return blockedPassage("ACCESS_ENDPOINT_NOT_REACHED: actual supported arrival required");
                body.drive(TravelTerrain.feetPoint(body,end),1,false);return Passage.WORKING;
            }
            if(!drop&&!reachable(target))return blockedPassage("ACCESS_INTERACTION_BLOCKED: target line still obstructed");
            body.getNavigation().stop();return Passage.READY;
        }
        var next=block(work.route.get(work.index));var from=work.index==0?work.start:block(work.route.get(work.index-1));
        if(passageCells(from,next).stream().anyMatch(p0->p0.distSqr(work.origin)>1024)||digEdge(from,next,reserved)<0)return blockedPassage("ACCESS_CHANGED: "+digRejection+" at "+next);
        BlockPos obstruction=null;
        if(!TravelTerrain.standable(body,next)||!TravelTerrain.transition(body,from,next))for(var pos:passageCells(from,next))if(!body.level().getBlockState(pos).isAir()){obstruction=pos;break;}
        if(obstruction!=null){
            travelPhase=auxiliary?"access_excavating":"excavating";
            if(spent.size()>=limit)return blockedPassage("ACCESS_BUDGET_EXHAUSTED: shared budget="+limit);
            if(obstruction.getX()==body.blockPosition().getX()&&obstruction.getZ()==body.blockPosition().getZ())return blockedPassage("ACCESS_OWN_COLUMN: no vertical digging below/above body");
            if(!reachable(obstruction))return blockedPassage("ACCESS_OBSTRUCTION_OUT_OF_REACH: "+obstruction);
            var state=body.level().getBlockState(obstruction);int slot=TargetInspection.bestTool(body,state);
            if(slot<0)return blockedPassage("ACCESS_TOOL_MISSING_OR_BROKEN");body.selectSlot(slot);
            if(!obstruction.equals(work.mining)||!state.equals(work.state)){work.mining=obstruction;work.state=state;work.progress=0;}
            body.getNavigation().stop();var context=beginContext();
            try{
                work.progress+=state.getDestroyProgress(context,body.level(),obstruction);body.swing(InteractionHand.MAIN_HAND);
                body.serverLevel().destroyBlockProgress(body.getId(),obstruction,Math.min(9,(int)(work.progress*10)));
                if(work.progress>=1){if(!preparationCanBreak())return Passage.BLOCKED;boolean changed=context.gameMode.destroyBlock(obstruction);body.serverLevel().destroyBlockProgress(body.getId(),obstruction,-1);
                    if(changed&&body.level().getBlockState(obstruction).isAir()){spent.add(obstruction.immutable());preparationBroke();body.getNavigation().invalidateProbes();work.mining=null;work.progress=0;}
                    else {finish(ActionState.RECONCILE_REQUIRED,"access destruction unconfirmed; do not replay");return Passage.WORKING;}
                }
            }catch(RuntimeException error){finish(ActionState.RECONCILE_REQUIRED,"access interaction exception; inspect actual blocks");}finally{endContext(context);}
            return Passage.WORKING;
        }
        if(!TravelTerrain.standable(body,next)||!TravelTerrain.transition(body,from,next))return blockedPassage("ACCESS_CLEARED_CORRIDOR_NOT_TRAVERSABLE: "+next);
        if(TravelTerrain.landedAt(body,next,.3)){work.index++;travelProgress=null;stalledTicks=0;return Passage.WORKING;}
        travelPhase=auxiliary?"access_approaching":"excavation_following";
        // Route is already verified; follow precisely, including its descent. Never finish at arm's reach.
        var point=TravelTerrain.feetPoint(body,next);
        body.getNavigation().stop();
        if(TravelTerrain.blockers(body,point).isEmpty())body.drive(point,1,false);
        if(lastProgress==null||body.position().distanceToSqr(point)<lastProgress.distanceToSqr(point)-.04){lastProgress=body.position();stalledTicks=0;}
        else if(++stalledTicks>=100){
            if(work.retries++==0){work.search=null;work.route=null;work.index=0;lastProgress=null;stalledTicks=0;return Passage.WORKING;}
            return blockedPassage("ACCESS_NO_PROGRESS: one replanning exhausted; target="+next+" blockers="+TravelTerrain.blockers(body,point));
        }
        return Passage.WORKING;
    }
    public Map<String,Object> travelStatus() {
        var active=arbiter.activeSnapshot();
        boolean travelling=active.isPresent() && (travelPhase.startsWith("access_") || travelPhase.equals("recovering_drops") || travelProgress != null || travelPhase.endsWith("searching") || active.get().payload().kind()==BodyOrder.Kind.BREATHE || active.get().payload().kind()==BodyOrder.Kind.EXCAVATE);
        var result=new LinkedHashMap<String,Object>(); result.put("active",travelling);result.put("phase",travelPhase);
        result.put("inWater",body.isInWater());result.put("air",body.getAirSupply());result.put("eyesUnderWater",body.isUnderWater());
        if(active.map(a->effectiveOrder(a.id(),a.payload()).kind()==BodyOrder.Kind.BREATHE).orElse(false)){
            result.put("waterRecoveryTicks",actionTicks);result.put("rejectedShoreCount",rejectedShores.size());
            if(recoveryShore!=null)result.put("shoreTarget",TravelTerrain.position(recoveryShore));
            if(breathTarget!=null)result.put("airTarget",TravelTerrain.position(breathTarget));
        }
        if(travelDestination!=null)result.put("destination",io.github.yufeiyufei888.hearthcrew.gameplay.TravelTerrain.position(travelDestination));
        if(travelStart!=null)result.put("start",Map.of("x",travelStart.x,"y",travelStart.y,"z",travelStart.z));
        result.put("position",Map.of("x",body.getX(),"y",body.getY(),"z",body.getZ()));
        result.put("movementType",body.getNavigation().movementType());if(travelDestination!=null)result.put("dynamicBlockers",TravelTerrain.blockers(body,TravelTerrain.feetPoint(body,travelDestination)).stream().map(Object::toString).toList());result.put("startAir",travelStartAir);result.put("replanned",travelProgress!=null&&travelProgress.replanned());return result;
    }
    public Map<String,Object> executionReport(String id) {var result=new LinkedHashMap<String,Object>(executionReports.getOrDefault(id,Map.of()));var child=resourceChildren.get(ActionId.of(id));if(child!=null){if(child.processing!=null)result.put("processingStation",TravelTerrain.position(child.processing));result.put("preparationSteps",child.completed);result.put("preparationBroken",child.broken);result.put("manufacturedItems",child.manufactured);result.put("checkpoints",List.copyOf(child.receipts));if(child.order!=null){result.put("childKind",child.order.kind().name());result.put("phase",child.preparation?"preparing_resource":"collecting_resource");}}var access=accessAccounts.get(ActionId.of(id));if(access!=null){result.put("accessBroken",access.broken.size());result.put("accessOrigin",TravelTerrain.position(access.origin));result.put("accessBrokenPositions",access.broken.stream().map(TravelTerrain::position).toList());}var sequence=sequences.get(ActionId.of(id));if(sequence!=null){result.put("sequenceIndex",sequence.index);result.put("sequenceSteps",List.copyOf(sequence.receipts));}var prepAccount=preparationAccounts.get(ActionId.of(id));if(prepAccount!=null){result.put("preparationSteps",prepAccount.steps);result.put("preparationBroken",prepAccount.broken);result.put("preparationReceipts",List.copyOf(prepAccount.receipts));}var prepWork=preparationWorks.get(ActionId.of(id));if(prepWork!=null&&prepWork.step!=null){result.put("childKind",prepWork.step.kind().name());result.put("phase",travelPhase);}var evidence=harvest.report(id);if(!evidence.isEmpty())result.putAll(evidence);var work=mineBatches.get(ActionId.of(id));if(work!=null){result.put("requested",work.requested);result.put("phase",arbiter.snapshot(ActionId.of(id)).map(s->s.state().terminal()?"mining_finished":travelPhase.startsWith("access_")?travelPhase:miningBroken?"recovering_drops":"mining").orElse("unknown"));}return result;}
    private void recordExecution(String id,Map<String,Object> result) {executionReports.put(id,Map.copyOf(result));if(executionReports.size()>128)executionReports.remove(executionReports.keySet().iterator().next());}

    private List<Mob> localThreats = List.of();
    private int nextThreatScan;
    private int lastThreatTick;
    private int retreatUntil;
    private int nextEscapePlan;
    private Vec3 escapeProgress;
    private int escapeProgressTick;
    private String safetyMode = "idle";
    private String safetyReason = "";
    private UUID safetyThreat;
    private Vec3 escapeTarget;
    private float safetyStartHealth;
    private int safetyStartTick;
    public Map<String, Object> localSafetyStatus() {
        boolean active = arbiter.activeSnapshot().map(a -> effectiveOrder(a.id(),a.payload()).kind() == BodyOrder.Kind.SELF_DEFENCE).orElse(false);
        return Map.of("active", active, "mode", active ? safetyMode : "idle", "reason", active ? safetyReason : "",
                "threatId", safetyThreat == null ? "" : safetyThreat.toString(), "threatCount", localThreats.size());
    }
    private UUID lastGuardAttacker;
    private final String guardNamespace = UUID.randomUUID().toString();
    private long lastGuardOwnerGeneration = -1;
    private int lastGuardHurtTimestamp = Integer.MIN_VALUE;
    private float miningProgress;
    private BlockState miningState;
    private boolean miningBroken;
    private int miningBreakTick;
    private final Map<UUID, Integer> miningDrops = new HashMap<>();
    private final HarvestLedger harvest=new HarvestLedger();
    public List<Map<String,Object>> harvestEvidence(){return harvest.observe(body);}
    private final Set<ActionId> harvestLoss=new HashSet<>();
    public void observedDropRemoval(UUID id,String reason){
        harvest.removed(id,reason);
        if(miningDrops.containsKey(id)&&executing!=null){harvestLoss.add(executing);miningDrops.remove(id);}
        for(var entry:suspendedMiningDrops.entrySet())if(entry.getValue().remove(id)!=null)harvestLoss.add(entry.getKey());
    }
    public int observedOtherPickup(UUID id,int count,ResourceLocation resource){return observedOtherPickup(id,count,resource,null);}
    public int observedOtherPickup(UUID id,int count,ResourceLocation resource,UUID collector){
        int accounted=harvest.acquired(id,count,resource.toString(),false,collector);
        decrementDropOwed(miningDrops,id,accounted);
        for(var owed:suspendedMiningDrops.values())decrementDropOwed(owed,id,accounted);
        return accounted;
    }
    public boolean ownsHarvestDrop(UUID id){return harvest.claims(id)||ownsMiningDrop(id);}
    private static final class BatchMine {
        final BlockPos origin,seed;final ResourceLocation resource; final int requested;
        final ArrayDeque<BlockPos> frontier=new ArrayDeque<>();final Set<BlockPos> visited=new HashSet<>(),broken=new HashSet<>();
        final List<BlockPos> candidates=new ArrayList<>();BlockPos current;boolean scanned;
        BatchMine(BlockPos origin,BlockPos seed,ResourceLocation resource,int count){this.origin=origin;this.seed=seed;this.resource=resource;requested=count;frontier.add(seed);}
    }
    private final Map<ActionId,BatchMine> mineBatches=new HashMap<>();
    private static final class SequenceWork {int index;final List<Map<String,Object>> receipts=new ArrayList<>();}
    private final Map<ActionId,SequenceWork> sequences=new HashMap<>();
    private static final class CollectionWork {
        final BlockPos origin,center;final Iterator<BlockPos> scan;final int baseline;
        final Set<BlockPos> attempted=new HashSet<>();final List<BlockPos> candidates=new ArrayList<>();BlockPos current;boolean scanned;
        CollectionWork(BlockPos origin,BlockPos center,int radius,int baseline){this.origin=origin;this.center=center;this.baseline=baseline;scan=BlockPos.withinManhattan(center,radius,radius,radius).iterator();}
    }
    private final Map<ActionId,CollectionWork> collections=new HashMap<>();
    private static final class ResourceChild {
        BodyOrder order; boolean preparation; int completed,broken,manufactured; BodyOrder root; BlockPos processing; final ArrayDeque<UUID> huntedDrops=new ArrayDeque<>(); final List<Map<String,Object>> receipts=new ArrayList<>();
        final io.github.yufeiyufei888.hearthcrew.gameplay.ResourcePreparation planner;
        ResourceChild(io.github.yufeiyufei888.hearthcrew.gameplay.ResourcePreparation planner){this.planner=planner;}
    }
    private final Map<ActionId,ResourceChild> resourceChildren=new HashMap<>();
    private final Map<ActionId,Map<BlockPos,String>> rejectedResourceTargets=new HashMap<>();
    private boolean finalizingResource;
    private void startResourceChild(ResourceChild child,BodyOrder order,boolean preparation){
        child.order=order;child.preparation=preparation;resetResourceStep();
        recordExecution(executing.value(),Map.of("phase",preparation?"preparing_resource":"collecting_resource","preparationSteps",child.completed,"preparationBroken",child.broken,"childKind",order.kind().name(),"checkpoints",List.copyOf(child.receipts)));
    }
    private void resetResourceStep(){
        body.getNavigation().stop();travelProgress=null;travelDestination=null;stalledTicks=0;placementWaitTicks=0;
        miningProgress=0;miningState=null;miningBroken=false;miningDrops.clear();mineBatches.remove(executing);accessWorks.remove(executing);
        gatherOrigin=null;gatherTarget=null;gatherResource=null;gatherDimension=null;gatherRequestedDropObserved=false;gatherCollected=0;gatherCandidates.clear();gatherCandidateIndex=0;gatherPlanFailure=null;gatherScanComplete=false;gatherScanCursor=gatherScanTotal=0;gatherPlanRoot=null;
    }
    private void resourceTerminal(ActionState state,String reason){finalizingResource=true;try{finish(state,reason);}finally{finalizingResource=false;}}

    private BodyOrder effectiveOrder(ActionId id,BodyOrder order){var prep=preparationWorks.get(id);if(prep!=null&&prep.step!=null)return prep.step;var child=resourceChildren.get(id);if(child!=null&&child.order!=null)return child.order;var sequence=sequences.get(id);return order.kind()==BodyOrder.Kind.SEQUENCE&&sequence!=null&&sequence.index<order.actions().size()?order.actions().get(sequence.index):order;}
    private int acquired(String id,ResourceLocation item){Object value=harvest.report(id).get("acquired");return value instanceof Map<?,?> map && map.get(item.toString()) instanceof Number n?n.intValue():0;}
    private int produced(String id,ResourceLocation item){Object value=harvest.report(id).get("acquiredByOthers");return acquired(id,item)+(value instanceof Map<?,?> map&&map.get(item.toString()) instanceof Number n?n.intValue():0);}
    private void sequence(BodyOrder order){
        var work=sequences.computeIfAbsent(executing,id->new SequenceWork());
        if(work.index>=order.actions().size()){complete("all sequence steps have observed terminal results");return;}
        accessAccounts.computeIfAbsent(executing,id->new AccessAccount(TravelTerrain.supportedStart(body)));
        perform(order.actions().get(work.index));
    }
    private void collectResource(BodyOrder order){
        var child=resourceChildren.computeIfAbsent(executing,id->new ResourceChild(new io.github.yufeiyufei888.hearthcrew.gameplay.ResourcePreparation(body,accessAccounts.get(id).origin,order.radius(),order.preparation())));
        child.root=order;child.planner.tickSurvey();
        if(child.order!=null){
            if(child.order.target()!=null&&entity(child.order.target())!=null&&entity(child.order.target()).blockPosition().distSqr(accessAccounts.get(executing).origin)>order.radius()*order.radius()){resourceTerminal(ActionState.PARTIAL,"RESOURCE_ENTITY_LEFT_AUTHORIZED_SCOPE");return;}
            perform(child.order);return;
        }
        if(child.processing!=null){startResourceChild(child,new BodyOrder(BodyOrder.Kind.COLLECT_PROCESS,child.processing,null,0),true);return;}
        while(!child.huntedDrops.isEmpty()){
            var id=child.huntedDrops.removeFirst();if(entity(id) instanceof ItemEntity item&&item.isAlive()){startResourceChild(child,new BodyOrder(BodyOrder.Kind.PICKUP,null,id,0),true);return;}
        }
        if(body.blockPosition().distSqr(accessAccounts.get(executing).origin)>order.radius()*order.radius()){resourceTerminal(ActionState.PARTIAL,"RESOURCE_SCOPE_EXCEEDED: return for a new authorized scope");return;}
        var work=collections.computeIfAbsent(executing,id->new CollectionWork(body.blockPosition(),order.position()==null?body.blockPosition():order.position(),order.radius(),produced(id.value(),order.resource())));
        int collected=produced(executing.value(),order.resource())-work.baseline+child.manufactured;
        recordExecution(executing.value(),Map.of("phase","collecting_resource","requestedItems",order.count(),"acquiredItems",collected,"item",order.resource().toString(),"attemptedBlocks",work.attempted.size(),"scanComplete",work.scanned));
        if(collected>=order.count()){resourceTerminal(ActionState.COMPLETED,"new produced items verified from native pickups/crafting; see pickupAttribution and checkpoints; requested="+order.count()+"; acquired="+collected);return;}
        if(body.blockPosition().distSqr(work.origin)>1024||work.center.distSqr(work.origin)>1024){fail("COLLECT_RESOURCE outside approved origin range");return;}
        if(work.current!=null){mine(work.current,false);return;}
        var requestedItem=BuiltInRegistries.ITEM.get(order.resource());
        var sourceBlocks=order.candidates().isEmpty()?io.github.yufeiyufei888.hearthcrew.gameplay.ResourcePreparation.sourceBlocks(requestedItem):order.candidates().stream().map(BuiltInRegistries.BLOCK::get).toList();
        if(sourceBlocks.stream().anyMatch(b->b.defaultBlockState().is(BlockTags.LOGS))){
            var log=child.planner.sources(requestedItem).stream().filter(p->!rejectedResourceTargets.getOrDefault(executing,Map.of()).containsKey(p)).findFirst();
            if(log.isPresent()){startResourceChild(child,BodyOrder.gather(BuiltInRegistries.BLOCK.getKey(body.level().getBlockState(log.get()).getBlock()),order.count()-collected),false);return;}
            if(!child.planner.scanned()){travelPhase="resource_searching";return;}
            resourceTerminal(collected>0?ActionState.PARTIAL:ActionState.FAILED,"RESOURCE_CONDITION: no validated log source in authorized range");return;
        }
        if(sourceBlocks.isEmpty()){
            if(order.preparation().enabled()&&preparationAccounts.computeIfAbsent(executing,id->new PreparationAccount()).steps<preparationStepLimit(order)){
                var d=child.planner.item(requestedItem,body.inventory().countItem(requestedItem)+order.count()-collected);
                if(d.step()!=null){startResourceChild(child,d.step(),true);return;}if(d.searching()){travelPhase="preparation_scanning";return;}
                resourceTerminal(ActionState.FAILED,d.reason());return;
            }
            resourceTerminal(ActionState.FAILED,"RESOURCE_CAPABILITY_UNAVAILABLE: no supported natural source or authorized preparation");return;
        }
        long deadline=System.nanoTime()+1_000_000;
        for(int checked=0;checked<1024&&!work.scanned&&System.nanoTime()<deadline;checked++){
            if(!work.scan.hasNext()){work.scanned=true;break;}if(!io.github.yufeiyufei888.hearthcrew.runtime.ScanBudget.claim(body.server,1))break;var pos=work.scan.next().immutable();
            if(pos.distSqr(work.center)>order.radius()*order.radius()||pos.distSqr(work.origin)>1024||!body.level().hasChunkAt(pos))continue;
            var state=body.level().getBlockState(pos);var resource=BuiltInRegistries.BLOCK.getKey(state.getBlock());
            if(!sourceBlocks.contains(state.getBlock())||state.hasBlockEntity()||CrewWorldData.get(body.server).playerBlock(body.level(),pos)||unsafeMiningPosition(pos))continue;
            if(!io.github.yufeiyufei888.hearthcrew.gameplay.ResourcePreparation.naturalSource(state))continue;
            // Logs still need independent natural-tree evidence, even in an item-count order.
            if(state.is(BlockTags.LOGS))continue; // Natural trees use GATHER's full protected-tree validation, never proximity alone.
            work.candidates.add(pos);
        }
        work.candidates.removeIf(p0->work.attempted.contains(p0)||!sourceBlocks.contains(body.level().getBlockState(p0).getBlock()));
        work.candidates.sort(Comparator.comparingDouble(p0->p0.distSqr(body.blockPosition())));
        for(var pos:work.candidates.stream().limit(8).toList()){
            var state=body.level().getBlockState(pos);if(TargetInspection.bestTool(body,state)<0){
                if(order.preparation().enabled()&&preparationAccounts.computeIfAbsent(executing,id->new PreparationAccount()).steps<preparationStepLimit(order)&&preparationAccounts.computeIfAbsent(executing,id->new PreparationAccount()).broken<preparationBreakLimit(order)){
                    var decision=child.planner.tool(state);
                    if(decision.step()!=null){startResourceChild(child,decision.step(),true);return;}
                    if(decision.searching()){travelPhase="preparation_scanning";return;}
                    resourceTerminal(ActionState.FAILED,decision.reason());return;
                }
                resourceTerminal(ActionState.FAILED,TargetInspection.missingTool(state)+"; preparation disabled or budget exhausted");return;
            }
            if(accessLimit()==0&&!reachable(pos)&&TargetInspection.accessibleStance(body,pos)==null){if(!body.getNavigation().pendingPaths())work.attempted.add(pos);continue;}
            work.current=pos;work.attempted.add(pos);miningBroken=false;miningProgress=0;miningState=null;miningDrops.clear();travelProgress=null;stalledTicks=0;mine(pos,false);return;
        }
        if(!work.scanned||body.getNavigation().pendingPaths()){travelPhase="resource_searching";return;}
        resourceTerminal(collected>0?ActionState.PARTIAL:ActionState.FAILED,"RESOURCE_CONDITION: no further qualified reachable candidate inside approved radius; acquired="+collected+"; inspect path or choose EXCAVATE/exploration; no implicit tunnelling");
    }
    private void batchMine(BodyOrder order){
        var seedState=body.level().getBlockState(order.position());
        if(!mineBatches.containsKey(executing)&&!seedState.isAir()&&TargetInspection.bestTool(body,seedState)<0){fail(TargetInspection.missingTool(seedState));return;}
        var work=mineBatches.computeIfAbsent(executing,k->new BatchMine(body.blockPosition(),order.position(),order.resource()!=null?order.resource():BuiltInRegistries.BLOCK.getKey(body.level().getBlockState(order.position()).getBlock()),order.count()));
        if(body.blockPosition().distSqr(work.origin)>1024){fail("MINE origin radius exceeded");return;}
        if(work.current!=null){mine(work.current,false);return;}
        if(work.broken.size()>=work.requested){complete("batch block removal and acquired drops verified");return;}
        if(!work.scanned){for(int i=0;i<64&&!work.frontier.isEmpty()&&work.visited.size()<4096;i++){
            var p=work.frontier.removeFirst();if(!work.visited.add(p)||p.distSqr(work.seed)>64||p.distSqr(work.origin)>1024||!body.level().hasChunkAt(p))continue;
            var s=body.level().getBlockState(p);if(!BuiltInRegistries.BLOCK.getKey(s.getBlock()).equals(work.resource))continue;
            if(work.requested>1 && !(TargetInspection.excavationBlock(body,p)||BuiltInRegistries.BLOCK.getKey(s.getBlock()).getPath().endsWith("_ore")||s.is(net.minecraft.world.level.block.Blocks.COBBLESTONE)||s.is(net.minecraft.world.level.block.Blocks.COBBLED_DEEPSLATE)))continue;
            if(s.hasBlockEntity()||CrewWorldData.get(body.server).playerBlock(body.level(),p))continue;
            if(p.getY()<Math.min(work.seed.getY(),work.origin.getY()))continue; // Preserve the approach floor unless a lower seed was explicitly selected.
            work.candidates.add(p);if(work.requested>1)for(var d:Direction.values())work.frontier.addLast(p.relative(d));
        }work.scanned=work.frontier.isEmpty()||work.visited.size()>=4096;travelPhase="mining_scanning";if(!work.scanned)return;}
        work.candidates.removeIf(p->work.broken.contains(p)||!BuiltInRegistries.BLOCK.getKey(body.level().getBlockState(p).getBlock()).equals(work.resource));
        work.candidates.sort(java.util.Comparator.comparingDouble(p->p.equals(work.seed)?-1:p.distSqr(body.blockPosition())));
        for(var p:work.candidates.stream().limit(4).toList()){
            if(unsafeMiningPosition(p))continue;
            if(accessLimit()>0||reachable(p)||TargetInspection.accessibleStance(body,p)!=null){work.current=p;miningBroken=false;miningProgress=0;miningState=null;miningDrops.clear();travelProgress=null;stalledTicks=0;mine(p,false);return;}
        }
        if(body.getNavigation().pendingPaths()){travelPhase="path_searching";return;}
        fail("MINE_BATCH_BLOCKED: no safe reachable matching target; inspect remaining blocks or plan EXCAVATE; candidates="+work.candidates.size());
    }
    private boolean unsafeMiningPosition(BlockPos p){
        if(p.getY()<body.getY()&&body.getBoundingBox().intersects(new AABB(p).expandTowards(0,2,0)))return true;
        // Explicit overhead targets still require the adjacent falling-block/fluid check below.
        for(var d:Direction.values()){var n=p.relative(d);if(!body.level().hasChunkAt(n)||!body.level().getFluidState(n).isEmpty()||body.level().getBlockState(n).getBlock() instanceof net.minecraft.world.level.block.FallingBlock)return true;}
        return false;
    }
    private void finishMineBlock(){
        var collection=collections.get(executing);if(collection!=null&&(resourceChildren.get(executing)==null||resourceChildren.get(executing).order==null)){collection.current=null;miningState=null;miningProgress=0;miningBroken=false;miningDrops.clear();travelProgress=null;stalledTicks=0;return;}
        var batch=mineBatches.get(executing);if(batch==null){complete("block removed and produced drops acquired");return;}
        batch.broken.add(batch.current);batch.current=null;miningState=null;miningProgress=0;miningBroken=false;miningDrops.clear();travelProgress=null;stalledTicks=0;
        if(batch.broken.size()>=batch.requested)complete(batch.requested==1?"block removed and produced drops acquired":"batch block removal and acquired drops verified");
    }

    private Vec3 buildOrigin;
    private final Map<BlockPos, BuildObservation> buildSteps = new LinkedHashMap<>();
    private Vec3 gatherOrigin;
    private BlockPos gatherTarget;
    private ResourceLocation gatherResource;
    private int gatherCollected;
    private final List<BlockPos> gatherCandidates = new ArrayList<>();
    private int gatherCandidateIndex;
    private String gatherPlanFailure;
    private String gatherDimension;
    private int gatherScanCursor;
    private int gatherScanTotal;
    private int gatherScanMinX;
    private int gatherScanMinY;
    private int gatherScanMinZ;
    private BlockPos gatherPlanRoot;
    private double gatherPlanRootDistance;
    private boolean gatherScanComplete;
    private int gatherResourceCandidates;
    private int gatherEligibleRoots;
    private final Map<String, Integer> gatherRejections = new LinkedHashMap<>();
    private final Map<String, BlockPos> gatherExamples = new LinkedHashMap<>();
    private final Map<String, Map<String, Object>> gatherReports = new LinkedHashMap<>();
    private void rejectGatherCandidate(String reason, BlockPos position) {
        gatherRejections.merge(reason, 1, Integer::sum);
        gatherExamples.putIfAbsent(reason, position.immutable());
    }
    public Map<String, Object> gatherDiagnostics(String actionId) {
        var active = arbiter.activeSnapshot();
        if (active.isPresent() && active.get().id().value().equals(actionId) && active.get().payload().kind() == BodyOrder.Kind.GATHER)
            return currentGatherDiagnostics(active.get().payload().count());
        return gatherReports.getOrDefault(actionId, Map.of());
    }
    private Map<String, Object> currentGatherDiagnostics(int requested) {
        var examples = new LinkedHashMap<String, Object>();
        gatherExamples.forEach((reason, pos) -> examples.put(reason, Map.of("x", pos.getX(), "y", pos.getY(), "z", pos.getZ())));
        var result = new LinkedHashMap<String, Object>();
        result.put("phase", gatherScanComplete ? "executing" : "scanning");
        result.put("scanned", gatherScanCursor); result.put("scanTotal", gatherScanTotal);
        result.put("candidates", gatherResourceCandidates); result.put("eligibleRoots", gatherEligibleRoots);
        result.put("rejections", Map.copyOf(gatherRejections)); result.put("examples", examples);
        result.put("requested", requested); result.put("collected", gatherCollected);
        return result;
    }
    private boolean gatherRequestedDropObserved;
    private boolean stopped;
    private boolean paused;
    private boolean recoveryInvalid;
    private net.minecraft.nbt.CompoundTag invalidRecoveryArchive;
    private boolean portalArrived;
    private BlockPos portalExit;


    public BodyExecutor(CompanionEntity body) {
        this.body = body;
        this.arbiter = new ActionArbiter<>(new WorldEpoch(1, 1, body.bodyGeneration()), 1200, 2048, ActionLedgerCodec.MAX_ACTIONS);
        this.arbiter.advanceTick(body.getServer().overworld().getGameTime(), false);
    }
    private BodyExecutor(CompanionEntity body, BodyExecutor previous) {
        this.body = body; this.arbiter = previous.arbiter;
        this.submissions.putAll(previous.submissions); this.stopped = previous.stopped;
        this.paused = previous.paused; this.recoveryInvalid = previous.recoveryInvalid;
        this.invalidRecoveryArchive = previous.invalidRecoveryArchive == null ? null : previous.invalidRecoveryArchive.copy();
    }
    /** Called only after vanilla portal transfer has created and registered the destination body. */
    public BodyExecutor afterDimensionChange(CompanionEntity destination, boolean portalContact) {
        requireServerThread();
        var active = arbiter.activeSnapshot();
        boolean arriving = active.isPresent() && active.get().payload().kind() == BodyOrder.Kind.PORTAL && portalContact
                && body.level().dimension() != destination.level().dimension();
        BodyExecutor next = new BodyExecutor(destination, this);
        if (arriving) { next.portalArrived = true; return next; }
        var oldEpoch = arbiter.epoch();
        arbiter.advanceEpoch(new WorldEpoch(oldEpoch.worldGeneration(), oldEpoch.sessionGeneration(), destination.bodyGeneration()),
                "dimension changed; remaining work requires re-observation");
        suspended.clear();
        waterInterrupted.clear();
        suspendedMiningDrops.clear();
        suspendedGatherCollected.clear();
        return next;
    }
    public ActionArbiter<BodyOrder, Checkpoint> arbiter() { return arbiter; }
    public boolean stopped() { return stopped; }
    public boolean paused() { return paused; }
    public boolean recoveryInvalid() { return recoveryInvalid; }

    /** Whether the active lease is the portal transition that must survive
     * vanilla's source/destination player replacement. */
    public boolean hasPortalWork() {
        return arbiter.activeSnapshot()
                .map(snapshot -> effectiveOrder(snapshot.id(), snapshot.payload()).kind() == BodyOrder.Kind.PORTAL)
                .orElse(false);
    }

    /** True only while the GUARD lease owns this body's execution slot. */
    public boolean isGuarding() {
        return arbiter.activeSnapshot().map(snapshot -> effectiveOrder(snapshot.id(),snapshot.payload()).kind() == BodyOrder.Kind.GUARD).orElse(false);
    }

    /** Submit at most one guard lease for one observed owner damage event. */
    public void ensureGuard(UUID ownerId, long ownerGeneration, UUID attackerId, int hurtTimestamp) {
        requireServerThread();
        if (ownerId == null || attackerId == null || stopped || paused) return;
        if (isGuarding()) return;
        if (arbiter.activeSnapshot().map(snapshot -> !ActionPriority.GUARD.outranks(snapshot.priority())).orElse(false)) return;
        if (ownerGeneration == lastGuardOwnerGeneration && attackerId.equals(lastGuardAttacker) && hurtTimestamp == lastGuardHurtTimestamp) return;
        ActionReceipt<BodyOrder> receipt = submit("guard-" + guardNamespace + "-" + ownerGeneration + "-" + hurtTimestamp,
                new BodyOrder(BodyOrder.Kind.GUARD, null, ownerId, 0), ActionPriority.GUARD);
        if (receipt.decision() == ReceiptDecision.ACCEPTED) {
            lastGuardAttacker = attackerId;
            lastGuardOwnerGeneration = ownerGeneration;
            lastGuardHurtTimestamp = hurtTimestamp;
        }
    }

    /** Finish a guard lease after its observed threat is gone; the normal tick resumes its checkpoint. */
    public void releaseGuard(String reason) {
        requireServerThread();
        if (isGuarding()) finish(ActionState.COMPLETED, reason == null ? "guard threat cleared" : reason);
    }

    public net.minecraft.nbt.CompoundTag saveLedger() {
        if (invalidRecoveryArchive != null) return invalidRecoveryArchive.copy();
        var saved = new net.minecraft.nbt.CompoundTag();
        saved.putInt("version", 2); saved.put("requests", ActionLedgerCodec.encode(submissions.values()));
        var outcomes = new net.minecraft.nbt.ListTag();
        for (var request : submissions.values()) arbiter.snapshot(request.id()).ifPresent(snapshot -> {
            var row = new net.minecraft.nbt.CompoundTag(); row.putString("id",request.id().value());
            row.putString("state",snapshot.state().name()); row.putString("message",snapshot.message()); outcomes.add(row);
        });
        saved.put("outcomes",outcomes);harvest.save(saved);var reports=new LinkedHashMap<String,Map<String,Object>>();submissions.keySet().stream().skip(Math.max(0,submissions.size()-128)).forEach(id->reports.put(id.value(),executionReport(id.value())));saved.putString("executionReports",new com.google.gson.Gson().toJson(reports));
        var accessList=new net.minecraft.nbt.ListTag();accessAccounts.forEach((id,a)->{var row=new net.minecraft.nbt.CompoundTag();row.putString("id",id.value());row.putLong("origin",a.origin.asLong());row.putLongArray("broken",a.broken.stream().mapToLong(BlockPos::asLong).toArray());accessList.add(row);});saved.put("accessAccounts",accessList);
        saved.putString("preparationAccounts",new com.google.gson.Gson().toJson(preparationAccounts.entrySet().stream().collect(java.util.stream.Collectors.toMap(e->e.getKey().value(),Map.Entry::getValue))));
        saved.putBoolean("stopped", stopped); saved.putBoolean("paused", paused);
        saved.putBoolean("invalid", recoveryInvalid); return saved;
    }
    public void restoreLedger(net.minecraft.nbt.CompoundTag saved) {
        requireServerThread();
        try {
            if ((saved.getInt("version") != 1 && saved.getInt("version") != 2) || saved.getBoolean("invalid") || !saved.contains("requests", net.minecraft.nbt.Tag.TAG_LIST))
                throw new IllegalArgumentException("unreadable action ledger");
            var requests = ActionLedgerCodec.decode(saved.getList("requests", net.minecraft.nbt.Tag.TAG_COMPOUND));
            var outcomes = new HashMap<ActionId, ActionArbiter.SavedOutcome>();
            if (saved.getInt("version") == 2) {
                if (!saved.contains("outcomes", net.minecraft.nbt.Tag.TAG_LIST)) throw new IllegalArgumentException("missing saved outcomes");
                for (var raw : saved.getList("outcomes",net.minecraft.nbt.Tag.TAG_COMPOUND)) {
                    var row = (net.minecraft.nbt.CompoundTag)raw;
                    if (outcomes.put(ActionId.of(row.getString("id")),new ActionArbiter.SavedOutcome(ActionState.valueOf(row.getString("state")),row.getString("message"))) != null)
                        throw new IllegalArgumentException("duplicate saved outcome");
                }
                if (outcomes.size() != requests.size()) throw new IllegalArgumentException("incomplete saved outcomes");
            }
            harvest.restore(saved);arbiter.restoreSaved(requests, outcomes);
            for(var raw:saved.getList("accessAccounts",net.minecraft.nbt.Tag.TAG_COMPOUND)){var row=(net.minecraft.nbt.CompoundTag)raw;var a=new AccessAccount(BlockPos.of(row.getLong("origin")));for(long packed:row.getLongArray("broken"))a.broken.add(BlockPos.of(packed));if(a.broken.size()>64)throw new IllegalArgumentException("invalid saved access budget");accessAccounts.put(ActionId.of(row.getString("id")),a);}
            if(saved.contains("executionReports")){var decoded=new com.google.gson.Gson().fromJson(saved.getString("executionReports"),new com.google.gson.reflect.TypeToken<Map<String,Map<String,Object>>>(){}.getType());if(decoded instanceof Map<?,?> reports)reports.forEach((id,value)->{if(id instanceof String key&&value instanceof Map<?,?> map){var safe=new LinkedHashMap<String,Object>();map.forEach((k,v)->{if(k instanceof String name)safe.put(name,v);});executionReports.put(key,safe);}});}
            if(saved.contains("preparationAccounts")){Map<String,PreparationAccount> accounts=new com.google.gson.Gson().fromJson(saved.getString("preparationAccounts"),new com.google.gson.reflect.TypeToken<Map<String,PreparationAccount>>(){}.getType());accounts.forEach((id,a)->{if(a.steps<0||a.broken<0||a.broken>64)throw new IllegalArgumentException("invalid preparation account");preparationAccounts.put(ActionId.of(id),a);});}
            requests.forEach(request -> submissions.put(request.id(), request));
            stopped = saved.getBoolean("stopped"); paused = saved.getBoolean("paused");
        } catch (RuntimeException invalid) {
            invalidRecoveryArchive = saved.copy(); invalidRecoveryArchive.putBoolean("invalid", true);
            recoveryInvalid = true; stopped = true; // Fail closed instead of forgetting identities.
        }
    }

    /** Restores receipt metadata only; never acquires an execution lease. */
    public boolean restoreHistoricalOutcome(String id, ActionState state, String message) {
        requireServerThread();
        var request=submissions.get(ActionId.of(id));
        return request!=null && arbiter.restoreHistoricalOutcome(request,new ActionArbiter.SavedOutcome(state,message));
    }

    public ActionReceipt<BodyOrder> submit(String id, BodyOrder order, ActionPriority priority) {
        requireServerThread();
        if (stopped) throw new IllegalStateException("companion stopped; resume first");
        ActionId actionId = ActionId.of(id);
        var original = submissions.get(actionId);
        if (original != null && original.payload().equals(order) && original.priority() == priority) return arbiter.submit(original);
        var previous = arbiter.activeSnapshot();
        if (previous.isPresent() && priority.outranks(previous.get().priority())) {
            // A higher-priority request can arrive before this body's first
            // tick. Start the accepted lease before recording its checkpoint;
            // no world mutation occurs in start(), and the checkpoint remains
            // a server-thread observation rather than a fabricated result.
            if (previous.get().state() == ActionState.ACCEPTED) arbiter.start(previous.get().id());
            Checkpoint checkpoint = makeCheckpoint(previous.get());
            arbiter.checkpoint(previous.get().id(), checkpoint);
            if (previous.get().payload().kind() == BodyOrder.Kind.MINE) {
                suspendedMiningDrops.put(previous.get().id(), new HashMap<>(checkpoint.miningDrops()));
            }
            if (previous.get().payload().kind() == BodyOrder.Kind.GATHER) {
                suspendedMiningDrops.put(previous.get().id(), new HashMap<>(checkpoint.miningDrops()));
                suspendedGatherCollected.put(previous.get().id(), checkpoint.gatherCollected());
            }
        }
        long now = arbiter.gameTick();
        long deadline = switch (order.kind()) {
            case EXCAVATE -> now + 4800;
            case MINE, COLLECT_RESOURCE -> now + Math.min(24000,1200 + order.count()*300);
            case SEQUENCE -> now+48000;
            case FOLLOW, GUARD, SELF_DEFENCE, BREATHE, SLEEP -> -1;
            default -> now + 2400;
        };
        var request = new ActionRequest<>(actionId, priority, order, arbiter.epoch(), now, deadline);
        var receipt = arbiter.submit(request);
        if (receipt.decision() == ReceiptDecision.ACCEPTED) {
            submissions.put(actionId, request);
            if (order.kind() == BodyOrder.Kind.BUILD) {
                // Anchor the 32-block radius when the lease is accepted,
                // before a later tick or external physics move.
                buildOrigin = body.position();
                buildSteps.clear();
            }
            if (order.kind() == BodyOrder.Kind.GATHER) {
                gatherOrigin = body.position();
                gatherTarget = null;
                gatherResource = order.resource();
                gatherCollected = 0;
                gatherCandidates.clear();
                gatherCandidateIndex = 0;
                gatherPlanFailure = null;
                gatherRequestedDropObserved = false;
                gatherDimension = body.level().dimension().location().toString();
                prepareGatherScan();
            }
        }
        if (receipt.decision() == ReceiptDecision.ACCEPTED && previous.isPresent()) suspended.push(previous.get().id());
        return receipt;
    }

    public void stop(String reason) {
        requireServerThread();
        stopped = true;
        arbiter.activeActionId().ifPresent(id -> arbiter.cancel(id, reason));
        suspended.forEach(id -> arbiter.cancel(id, reason));
        suspended.clear();
        suspendedMiningDrops.clear();
        suspendedGatherCollected.clear();
        stopMotion();
    }
    /** Death retires physical work, but must not create a persistent owner stop. */
    public void interruptForDeath() {
        releaseSpace();
        requireServerThread();
        boolean ownerStopped = stopped;
        stop("body died");
        // The dead entity cannot execute ticks. Persist only the control state
        // that existed before death so a new body can be reconciled and plan.
        // An explicit owner stop/pause (or invalid archive) remains in force.
        stopped = ownerStopped;
    }

    /**
     * Dimension changes are not deaths. Preserve a portal lease so
     * restoreFrom can attach it to the destination player; ordinary work is
     * retired because its world coordinates are no longer valid.
     */
    public void interruptForDimensionChange() {
        requireServerThread();
        if (hasPortalWork()) {
            releaseSpace();
            stopMotion();
            return;
        }
        interruptForDeath();
    }
    /** Freeze this body's action lease while allowing external game effects. */
    public void pause() {
        requireServerThread();
        if (stopped) return;
        paused = true;
        body.getNavigation().stop();
        body.stopUsingItem();
    }
    public void resume() {
        requireServerThread();
        if (recoveryInvalid) throw new IllegalStateException("saved action ledger unreadable; recovery required");
        stopped = false; paused = false;
    }
    /** Owner reassignment atomically retires all active and suspended work. */
    public void retask() { requireServerThread(); cancelThrough(ActionPriority.SAFETY, "owner reassigned companion"); resume(); }

    /** Retire ordinary work without disabling local survival and owner defence. */
    public void standby() { cancelBelow(ActionPriority.GUARD, "owner standby"); }
    /** Turning autonomy off must leave player work and local reflexes intact. */
    public void disableAutonomy() { cancelBelow(ActionPriority.OWNER, "autonomy disabled"); }
    public boolean hasOrdinaryWork() { return hasWorkBelow(ActionPriority.GUARD); }
    public boolean hasAutonomousWork() { return hasWorkBelow(ActionPriority.OWNER); }
    private boolean hasWorkBelow(ActionPriority threshold) {
        return arbiter.activeSnapshot().map(s -> threshold.outranks(s.priority())).orElse(false)
                || suspended.stream().anyMatch(id -> submissions.containsKey(id) && threshold.outranks(submissions.get(id).priority()));
    }
    private void cancelBelow(ActionPriority threshold, String reason) {
        requireServerThread();
        boolean cancelledActive = arbiter.activeSnapshot().filter(s -> threshold.outranks(s.priority()))
                .map(s -> { arbiter.cancel(s.id(), reason); return true; }).orElse(false);
        for (var iterator = suspended.iterator(); iterator.hasNext();) {
            ActionId id = iterator.next(); var submission = submissions.get(id);
            if (submission != null && threshold.outranks(submission.priority())) {
                arbiter.cancel(id, reason); iterator.remove(); suspendedMiningDrops.remove(id); suspendedGatherCollected.remove(id);
            }
        }
        if (cancelledActive) stopMotion();
    }

    /** Cancel work at or below a boundary; used only for an explicit owner retask. */
    private void cancelThrough(ActionPriority threshold, String reason) {
        requireServerThread();
        boolean cancelledActive = arbiter.activeSnapshot().filter(s -> threshold.outranks(s.priority()) || s.priority() == threshold)
                .map(s -> { arbiter.cancel(s.id(), reason); return true; }).orElse(false);
        for (var iterator = suspended.iterator(); iterator.hasNext();) {
            ActionId id = iterator.next(); var submission = submissions.get(id);
            if (submission != null && (threshold.outranks(submission.priority()) || submission.priority() == threshold)) {
                arbiter.cancel(id, reason); iterator.remove(); suspendedMiningDrops.remove(id); suspendedGatherCollected.remove(id);
            }
        }
        if (cancelledActive) stopMotion();
    }

    public void tick() {
        requireServerThread();
        for (var expired : arbiter.advanceTick(body.getServer().overworld().getGameTime(), paused))
            if (expired.id().equals(executing) && travelProgress!=null)recordExecution(expired.id().value(),travelStatus());
        if (stopped || paused || !body.isAlive()) return;
        if (attackCooldown > 0) attackCooldown--;
        safety();
        // Native Player.aiStep performs contact pickup; Post records its actual inventory delta.
        var active = arbiter.activeSnapshot();
        if (active.isEmpty()) {
            resumeSuspended();
            active = arbiter.activeSnapshot();
        }
        if (active.isEmpty()) { stopMotion();
            if(body.isInWater()&&!body.isPassenger()){body.getJumpControl().jump();travelPhase="surface_support";}
            return; }
        var action = active.get();
        if (!action.id().equals(executing)) {
            stopMotion();
            executing = action.id();
            accessAccounts.computeIfAbsent(executing,id->new AccessAccount(TravelTerrain.supportedStart(body)));
            travelProgress=null;travelDestination=null;travelStart=null;travelStartAir=body.getAirSupply();travelPhase="idle";
            actionTicks = stalledTicks = placementWaitTicks = 0;
            miningProgress = 0;
            miningState = null;
            miningBroken = false; miningDrops.clear();
            // An accepted BUILD already captured its immutable start origin in
            // submit(). Preserve it across the first executor tick; resetting
            // here would silently redefine the 32-block bound after physics or
            // another server callback moved the body.
            if (effectiveOrder(action.id(),action.payload()).kind() != BodyOrder.Kind.BUILD) {
                buildOrigin = null;
                buildSteps.clear();
            }
            if (effectiveOrder(action.id(),action.payload()).kind() != BodyOrder.Kind.GATHER) {
                gatherOrigin = null;
                gatherTarget = null;
                gatherResource = null;
                gatherCollected = 0;
                gatherCandidates.clear();
                gatherCandidateIndex = 0;
                gatherPlanFailure = null;
                gatherRequestedDropObserved = false;
                gatherDimension = null;
                gatherScanCursor = gatherScanTotal = 0;
                gatherPlanRoot = null;
                gatherScanComplete = false;
            }
            lastProgress = body.position();
        }
        if (action.state() == ActionState.ACCEPTED) arbiter.start(action.id());
        actionTicks++;
        try {perform(action.payload());}catch(RuntimeException exception){fail("interaction failed: "+exception.getClass().getSimpleName());}
    }
    private static final class PreparationAccount {
        int steps, broken; final List<Map<String,Object>> receipts=new ArrayList<>();
    }
    private final Map<ActionId,PreparationAccount> preparationAccounts=new HashMap<>();
    private final class PreparationWork {
        final BodyOrder root; final io.github.yufeiyufei888.hearthcrew.gameplay.ResourcePreparation planner;
        BodyOrder step; Checkpoint checkpoint; BatchMine batch; DigWork access; int desired, initial, produced; net.minecraft.world.item.Item output;
        PreparationWork(BodyOrder root){this.root=root;var origin=accessAccounts.get(executing).origin;
            planner=new io.github.yufeiyufei888.hearthcrew.gameplay.ResourcePreparation(body,origin,root.radius(),root.preparation());planner.surveyNearby();
            if(root.kind()==BodyOrder.Kind.CRAFT){var h=body.level().getRecipeManager().byKey(root.resource()).orElse(null);if(h!=null){var out=h.value().getResultItem(body.registryAccess());output=out.getItem();initial=body.inventory().countItem(output);desired=initial+out.getCount()*root.count();}}
        }
    }
    private final Map<ActionId,PreparationWork> preparationWorks=new HashMap<>();
    private boolean insidePreparation;
    private BodyOrder preparationStep(){var work=preparationWorks.get(executing);return work==null?null:work.step;}
    private int preparationStepLimit(BodyOrder order){return Math.min(order.preparation().maxSteps(),arbiter.activeSnapshot().orElseThrow().payload().preparation().maxSteps());}
    private int preparationBreakLimit(BodyOrder order){return Math.min(order.preparation().maxBreaks(),arbiter.activeSnapshot().orElseThrow().payload().preparation().maxBreaks());}
    private boolean prepareWork(BodyOrder order){
        if(insidePreparation||!order.preparation().enabled()||!Set.of(BodyOrder.Kind.CRAFT,BodyOrder.Kind.MINE,BodyOrder.Kind.EXCAVATE,BodyOrder.Kind.COLLECT_RESOURCE).contains(order.kind()))return false;
        var work=preparationWorks.get(executing);
        if(work==null||!work.root.equals(order)){work=new PreparationWork(order);preparationWorks.put(executing,work);}
        if(work.step!=null){insidePreparation=true;try{perform(work.step);}finally{insidePreparation=false;}return true;}
        var account=preparationAccounts.computeIfAbsent(executing,id->new PreparationAccount());
        if(order.kind()==BodyOrder.Kind.CRAFT&&work.output!=null&&work.produced>=work.desired-work.initial){complete("craft output verified after preparation; produced="+(work.desired-work.initial));return true;}
        work.planner.tickSurvey();
        io.github.yufeiyufei888.hearthcrew.gameplay.ResourcePreparation.Decision decision;
        BlockState toolState=null;
        if(order.kind()==BodyOrder.Kind.CRAFT){
            if(order.position()!=null&&!body.level().getBlockState(order.position()).is(net.minecraft.world.level.block.Blocks.CRAFTING_TABLE)){fail("CRAFT_STATION_CHANGED: explicit station unavailable");return true;}
            decision=work.planner.craft(order.resource(),body.inventory().countItem(work.output)+work.desired-work.initial-work.produced);
        }else{
            if(miningBroken)return false;
            if(order.kind()==BodyOrder.Kind.COLLECT_RESOURCE){var blocks=io.github.yufeiyufei888.hearthcrew.gameplay.ResourcePreparation.sourceBlocks(BuiltInRegistries.ITEM.get(order.resource()));if(blocks.isEmpty())return false;toolState=blocks.getFirst().defaultBlockState();}
            else if(order.kind()==BodyOrder.Kind.EXCAVATE)toolState=net.minecraft.world.level.block.Blocks.STONE.defaultBlockState();
            else {var batch=mineBatches.get(executing);if(batch!=null&&batch.broken.size()>=batch.requested)return false;toolState=batch==null?body.level().getBlockState(order.position()):BuiltInRegistries.BLOCK.get(batch.resource).defaultBlockState();}
            if(toolState.isAir()||!toolState.requiresCorrectToolForDrops())return false;
            int life=order.count()>0?Math.min(16,order.count())+2:16;
            if(work.planner.readyTool(toolState,life)>=0)return false;
            decision=work.planner.tool(toolState,life);
        }
        if(decision.step()==null){
            if(decision.searching()){travelPhase="preparation_scanning";body.haltInputs();return true;}
            // A worn tool is usable for a short safe step when no replacement can currently be prepared.
            if(toolState!=null&&TargetInspection.bestTool(body,toolState)>=0){recordExecution(executing.value(),Map.of("preparationCondition",decision.reason(),"usingWornTool",true));return false;}
            fail("PREPARATION_BLOCKED: "+decision.reason());return true;
        }
        if(account.steps>=preparationStepLimit(order)){fail("PREPARATION_STEP_BUDGET_EXHAUSTED");return true;}
        var step=decision.step();
        if(order.kind()==BodyOrder.Kind.MINE&&step.kind()==BodyOrder.Kind.MINE&&step.position().equals(order.position())){
            if(toolState!=null&&TargetInspection.bestTool(body,toolState)>=0)return false;
            fail("PREPARATION_DEPENDENCY_CYCLE: original target cannot also be an unqualified tool prerequisite");return true;
        }
        if(order.kind()==BodyOrder.Kind.CRAFT&&step.kind()==BodyOrder.Kind.CRAFT&&step.resource().equals(order.resource())&&order.position()!=null)step=new BodyOrder(step.kind(),order.position(),null,step.count(),step.resource());
        work.checkpoint=makeCheckpoint(arbiter.activeSnapshot().orElseThrow());work.batch=mineBatches.remove(executing);work.access=accessWorks.remove(executing);
        resetResourceStep();work.step=step;
        travelPhase=step.kind()==BodyOrder.Kind.PLACE?"placing_preparation_station":step.kind()==BodyOrder.Kind.CRAFT?"preparing_tool":"preparing_resource";
        return true;
    }
    private boolean preparationCanBreak(){
        var work=preparationWorks.get(executing);var child=resourceChildren.get(executing);
        var root=work!=null&&work.step!=null?work.root:child!=null&&child.order!=null&&child.preparation?child.root:null;
        if(root==null)return true;
        if(preparationAccounts.computeIfAbsent(executing,id->new PreparationAccount()).broken<preparationBreakLimit(root))return true;
        fail("PREPARATION_BREAK_BUDGET_EXHAUSTED");return false;
    }
    private void preparationBroke(){if(preparationStep()!=null||resourceChildren.containsKey(executing)&&resourceChildren.get(executing).order!=null&&resourceChildren.get(executing).preparation)preparationAccounts.computeIfAbsent(executing,id->new PreparationAccount()).broken++;}
    private void perform(BodyOrder order){
            if(prepareWork(order))return;

            switch (order.kind()) {
                case YIELD -> yieldSpace();
                case SEQUENCE -> sequence(order);
                case COLLECT_RESOURCE -> collectResource(order);
                case MOVE -> {body.getNavigation().allowWater(false);moveTo(order.position(), 1.0, true);}
                case FOLLOW -> follow(order.target(), false);
                case MINE -> batchMine(order);
                case EXCAVATE -> excavate(order);
                case GATHER -> gather(order);
                case PLACE -> place(order);
                case EAT -> eat();
                case SLEEP -> sleep(order.position());
                case ATTACK -> attack(order.target());
                case GUARD -> follow(order.target(), true);
                case SELF_DEFENCE -> defendLocally();
                case BREATHE -> breathe();
                case PICKUP -> pickup(order.target());
                case PORTAL -> enterPortal(order.position());
                case WAIT -> { if (actionTicks >= Math.max(1, order.count())) complete("wait elapsed"); }
                case SELECT -> {
                    int requested = order.count(); body.selectSlot(requested);
                    recordExecution(executing.value(),Map.of("requestedSlot",requested,"selectedSlot",body.selectedSlot(),"swappedFromInventory",requested>8,
                        "heldItem",BuiltInRegistries.ITEM.getKey(body.getMainHandItem().getItem()).toString(),"heldCount",body.getMainHandItem().getCount()));
                    complete("selectedSlot=" + body.selectedSlot() + "; requestedSlot=" + requested + "; swappedFromInventory=" + (requested > 8)
                            + "; heldItem=" + BuiltInRegistries.ITEM.getKey(body.getMainHandItem().getItem()) + "; heldCount=" + body.getMainHandItem().getCount());
                }
                case CRAFT -> craft(order);
                case TRANSFER -> transfer(order);
                case BUILD -> build(order);
                case SWIM -> {body.getNavigation().allowWater(true);moveTo(order.position(),1.25,true);}
                case EXPLORE -> explore(order.position());
                case USE_ITEM, INTERACT, EQUIP, STORE, TAKE, PROCESS, COLLECT_PROCESS, LAUNCH_BOAT, BOARD_BOAT, SAIL, DISEMBARK -> nativeAction(order);
            }
    }

    private static final Map<String,SpaceLease> spaceLeases=new HashMap<>();
    private record SpaceLease(UUID bot,String action,long expiry,BodyExecutor owner){
        boolean live(){return owner.body.isAlive()&&!owner.body.isRemoved()&&owner.arbiter.gameTick()<expiry;}
    }
    public static void clearSpaceLeases(){spaceLeases.clear();}
    private BlockPos yieldTarget;
    private String spaceKey(BlockPos p){return System.identityHashCode(body.server)+":"+body.level().dimension().location()+":"+p;}
    private void releaseSpace(){spaceLeases.entrySet().removeIf(e->e.getValue().bot().equals(body.companionId()));yieldTarget=null;}
    private void yieldSpace(){
        long now=arbiter.gameTick();
        if(actionTicks==1)yieldTarget=null;
        if(yieldTarget==null){
            List<BlockPos> candidates=new ArrayList<>();var origin=TravelTerrain.supportedStart(body);
            for(var p0:BlockPos.betweenClosed(origin.offset(-4,-1,-4),origin.offset(4,1,4))){var p=p0.immutable();double distance=p.distSqr(origin);if(distance<4||distance>16||!TravelTerrain.standable(body,p)||!TravelTerrain.blockers(body,TravelTerrain.feetPoint(body,p)).isEmpty())continue;
                var lease=spaceLeases.get(spaceKey(p));if(lease!=null&&!lease.bot().equals(body.companionId())&&lease.live())continue;candidates.add(p);}
            // Stable identity-dependent ordering prevents two opposite blockers selecting the same side.
            candidates.sort(Comparator.comparingLong(p->Objects.hash(body.companionId(),p)));
            for(var p:candidates){var path=body.getNavigation().createPath(p,0);if(path==null)continue;yieldTarget=p;spaceLeases.put(spaceKey(p),new SpaceLease(body.companionId(),executing.value(),now+300,this));break;}
            if(yieldTarget==null){if(actionTicks<120){travelPhase="yield_path_searching";return;}fail("YIELD_BLOCKED: no unoccupied reachable stance 2..4 blocks away");return;}
        }
        moveTo(yieldTarget,1.0,true);
    }
    private BlockPos explorationStart,explorationWaypoint;private Set<String> explorationKnown=Set.of();private long explorationStarted;private List<BlockPos> explorationCandidates=List.of();private BlockPos explorationCandidateOrigin;private long explorationCandidateTick;
    private void explore(BlockPos target){
        if(target==null){fail("EXPLORE requires a direction waypoint");return;}
        if(actionTicks==1){explorationStart=body.blockPosition();explorationWaypoint=null;explorationCandidates=List.of();explorationCandidateOrigin=null;explorationKnown=io.github.yufeiyufei888.hearthcrew.runtime.ExplorationRecord.get(body.server).observedRegions();explorationStarted=body.serverLevel().getGameTime();}
        if(explorationStart.distSqr(target)>256*256){fail("EXPLORE segment exceeds 256 blocks");return;}
        if(TravelTerrain.landedAt(body,target,.5)){
            body.getNavigation().stop();if(++arrivalTicks<5)return;
            var records=io.github.yufeiyufei888.hearthcrew.runtime.ExplorationRecord.get(body.server);
            io.github.yufeiyufei888.hearthcrew.runtime.NearbyObservation.scan(body,32);travelPhase="exploration_observing";
            if(!records.observedAt(body,target,explorationStarted))return;
            var newly=new HashSet<>(records.observedRegions());newly.removeAll(explorationKnown);
            recordExecution(executing.value(),Map.of("phase","exploration_finished","newObservedRegions",List.copyOf(newly),"destination",TravelTerrain.position(target),"actualPosition",Map.of("x",body.getX(),"y",body.getY(),"z",body.getZ())));
            if(newly.isEmpty())finish(ActionState.PARTIAL,"EXPLORE_NO_NEW_AREA: endpoint reached but region already observed; choose genuinely unknown frontier");else complete("selected exploration endpoint reached and new region observation completed");return;
        }else arrivalTicks=0;
        if(body.blockPosition().distSqr(target)<=24*24&&body.level().hasChunkAt(target)){body.getNavigation().allowWater(false);moveTo(target,1.15,false);return;}
        if(explorationWaypoint==null||body.blockPosition().distSqr(explorationWaypoint)<4){
            var delta=Vec3.atBottomCenterOf(target).subtract(body.position());var desired=body.position().add(delta.normalize().scale(Math.min(20,delta.length())));
            WorldEvents.requestExplorationChunks(body,BlockPos.containing(desired));
            if(explorationCandidateOrigin==null||body.blockPosition().distSqr(explorationCandidateOrigin)>16||explorationCandidates.isEmpty()&&actionTicks-explorationCandidateTick>=20){explorationCandidates=TravelTerrain.frontier(body,Vec3.atBottomCenterOf(target),24);explorationCandidateOrigin=body.blockPosition();explorationCandidateTick=actionTicks;}
            var candidates=explorationCandidates;
            var path=body.getNavigation().pathToAny("explore-frontier",candidates);
            explorationWaypoint=path==null?null:path.destination();
            if(path==null&&body.getNavigation().pendingPaths()){travelPhase="exploration_path_searching";return;}
            if(explorationWaypoint==null){if(actionTicks<200){travelPhase="exploration_loading_or_searching";return;}fail("EXPLORATION_FRONTIER_UNCONFIRMED: directional loaded candidates or path search insufficient; inspect loading/search status, not proof that no land exists");return;}
        }
        body.getNavigation().allowWater(false);moveTo(explorationWaypoint,1.15,false);
    }
    private ItemStack nativeUseBefore=ItemStack.EMPTY;
    private InteractionHand nativeUseHand=InteractionHand.MAIN_HAND;
    private int nativeUseFood;
    private boolean nativeUseStarted;
    private void nativeAction(BodyOrder order){
        usingNative(order);
    }
    private void usingNative(BodyOrder order){
        var kind=order.kind();var pos=order.position();
        if(pos!=null&&kind!=BodyOrder.Kind.SAIL&&kind!=BodyOrder.Kind.DISEMBARK&&kind!=BodyOrder.Kind.LAUNCH_BOAT&&!reachable(pos)){approach(pos);return;}
        if(kind==BodyOrder.Kind.BOARD_BOAT||kind==BodyOrder.Kind.INTERACT&&order.target()!=null){var target=entity(order.target());if(target!=null&&body.distanceToSqr(target)>9){moveTo(target.blockPosition(),1,false);return;}}
        io.github.yufeiyufei888.hearthcrew.gameplay.PlayerInteractions.Result result;
        switch(kind){
            case EQUIP -> result=io.github.yufeiyufei888.hearthcrew.gameplay.PlayerInteractions.equip(body,order.resource());
            case INTERACT -> result=io.github.yufeiyufei888.hearthcrew.gameplay.PlayerInteractions.interact(body,pos,order.target(),order.resource());
            case STORE,TAKE -> result=io.github.yufeiyufei888.hearthcrew.gameplay.PlayerInteractions.transferContainer(body,pos,order.resource(),order.count(),kind==BodyOrder.Kind.TAKE);
            case PROCESS -> {result=io.github.yufeiyufei888.hearthcrew.gameplay.FurnaceOrders.start(body,pos,order.resource(),order.count(),executing.value());var child=resourceChildren.get(executing);if(result.completed()&&child!=null&&child.order!=null)child.processing=pos; }
            case COLLECT_PROCESS -> {
                var child=resourceChildren.get(executing);var block=body.level().getBlockEntity(pos);
                String output=block==null?"":block.getPersistentData().getCompound("HearthCrewOrder").getString("output");
                result=io.github.yufeiyufei888.hearthcrew.gameplay.FurnaceOrders.collect(body,pos);
                if(child!=null&&child.order!=null){
                    if(result.reason().startsWith("WAITING_PROCESS")){travelPhase="waiting_processing";return;}
                    if(child.root.resource().toString().equals(output))child.manufactured+=result.changed();
                    if(result.completed())child.processing=null;
                }
            }
            case LAUNCH_BOAT -> {
                result=io.github.yufeiyufei888.hearthcrew.gameplay.BoatActions.launch(body,pos,order.resource());
                if(!result.completed()&&result.changed()==0&&!result.reason().startsWith("MISSING")&&!result.reason().startsWith("LAUNCH_NO_EFFECT")){
                    var stance=TargetInspection.stances(body,pos).stream().limit(8).filter(p->{var path=body.getNavigation().createPath(p,0);return path!=null&&path.canReach();}).filter(p->p.distSqr(body.blockPosition())>0).findFirst();
                    if(stance.isPresent()&&actionTicks<200){moveTo(stance.get(),1,false);return;}
                    if(body.getNavigation().pendingPaths()&&actionTicks<200){travelPhase="boat_stance_searching";return;}
                }
            }
            case BOARD_BOAT -> result=io.github.yufeiyufei888.hearthcrew.gameplay.BoatActions.board(body,order.target());
            case SAIL -> {
                if(travelProgress==null)travelProgress=new TravelProgress(arbiter.gameTick(),body.position().distanceTo(Vec3.atCenterOf(pos)),0);
                var progress=travelProgress.update(arbiter.gameTick(),body.position().distanceTo(Vec3.atCenterOf(pos)),0,0);
                if(progress==TravelProgress.Result.BLOCKED){fail("SAIL_NO_PROGRESS: choose another water waypoint");return;}
                result=io.github.yufeiyufei888.hearthcrew.gameplay.BoatActions.sail(body,pos);
            }
            case DISEMBARK -> result=io.github.yufeiyufei888.hearthcrew.gameplay.BoatActions.disembark(body,pos);
            case USE_ITEM -> {
                if(actionTicks==1){
                    nativeUseHand=order.resource()!=null&&BuiltInRegistries.ITEM.getKey(body.getOffhandItem().getItem()).equals(order.resource())?InteractionHand.OFF_HAND:InteractionHand.MAIN_HAND;
                    if(nativeUseHand==InteractionHand.MAIN_HAND)io.github.yufeiyufei888.hearthcrew.gameplay.PlayerInteractions.select(body,order.resource());
                    nativeUseBefore=body.getItemInHand(nativeUseHand).copy();nativeUseFood=body.foodLevel();nativeUseStarted=false;
                    body.gameMode.useItem(body,body.level(),body.getItemInHand(nativeUseHand),nativeUseHand);
                }
                nativeUseStarted|=body.isUsingItem();
                if(body.isUsingItem()&&actionTicks<Math.max(1,order.count()))return;
                boolean blocking=body.isBlocking();
                if(body.isUsingItem())body.releaseUsingItem();
                boolean changed=!ItemStack.matches(nativeUseBefore,body.getItemInHand(nativeUseHand))||nativeUseFood!=body.foodLevel();
                result=new io.github.yufeiyufei888.hearthcrew.gameplay.PlayerInteractions.Result(changed||blocking,changed?1:0,
                    changed?"native held item or food state changed":blocking?"shield held through requested game ticks; no damage claim":"NO_OBSERVED_USE_EFFECT: inspect item requirements; targeted actions use INTERACT or ATTACK");
            }
            default -> throw new IllegalArgumentException("unhandled native action");
        }
        if(result==null)return;
        recordExecution(executing.value(),Map.of("actualChanged",result.changed(),"reason",result.reason()));
        finish(result.completed()?ActionState.COMPLETED:result.reason().startsWith("RECONCILE_REQUIRED")?ActionState.RECONCILE_REQUIRED:result.changed()>0?ActionState.PARTIAL:ActionState.FAILED,result.reason());
    }

    private void safety() {
        wetTicks=body.isInWater()&&!body.isPassenger()?wetTicks+1:0;
        var waterWork=arbiter.activeSnapshot();
        boolean deliberateWater=waterWork.map(a->java.util.Set.of(BodyOrder.Kind.SWIM,BodyOrder.Kind.SAIL,BodyOrder.Kind.BOARD_BOAT,BodyOrder.Kind.DISEMBARK,BodyOrder.Kind.BREATHE).contains(effectiveOrder(a.id(),a.payload()).kind())).orElse(false);
        boolean lowAir=body.isUnderWater()&&body.getAirSupply()<180;
        if (lowAir || wetTicks>=20&&!deliberateWater&&body.tickCount>=nextWaterRecovery) {
            var current=arbiter.activeSnapshot();
            if (current.isPresent() && current.get().priority()==ActionPriority.SAFETY && effectiveOrder(current.get().id(),current.get().payload()).kind()!=BodyOrder.Kind.BREATHE)
                finish(ActionState.CANCELLED,"immediate oxygen recovery replaces local reflex; ordinary checkpoint retained");
            current=arbiter.activeSnapshot();
            if (current.isEmpty() || ActionPriority.SAFETY.outranks(current.get().priority())) {
                var prior=current;
                var accepted=submit("breathe-"+UUID.randomUUID(),new BodyOrder(BodyOrder.Kind.BREATHE,null,null,0),ActionPriority.SAFETY);
                if(accepted.decision()==ReceiptDecision.ACCEPTED){
                    prior.ifPresent(a->waterInterrupted.add(a.id()));
                    breathTarget=null;recoveryShore=null;rejectedShores.clear();nextAirSearch=0;breathingTicks=0;
                }
            }
        }
        if (body.tickCount >= nextThreatScan) {
            localThreats = io.github.yufeiyufei888.hearthcrew.gameplay.LocalThreats.nearby(body);
            nextThreatScan = body.tickCount + 5;
        }
        if (!localThreats.isEmpty()) {
            lastThreatTick = body.tickCount;
            var current = arbiter.activeSnapshot();
            if (current.isPresent() && effectiveOrder(current.get().id(),current.get().payload()).kind() == BodyOrder.Kind.EAT
                    && current.get().priority() == ActionPriority.SAFETY) {
                finish(ActionState.CANCELLED, "local meal interrupted by immediate threat");
                current = arbiter.activeSnapshot();
            }
            if (current.isEmpty() || ActionPriority.SAFETY.outranks(current.get().priority())) {
                var receipt = submit("self-defence-" + UUID.randomUUID(),
                        new BodyOrder(BodyOrder.Kind.SELF_DEFENCE, null, null, 0), ActionPriority.SAFETY);
                if (receipt.decision() == ReceiptDecision.ACCEPTED) {
                    safetyStartHealth = body.getHealth(); safetyStartTick = body.tickCount;
                    retreatUntil = 0; nextEscapePlan = 0; escapeTarget = null;
                    safetyMode = "defending"; safetyReason = "附近敌人威胁自己或队友，立即自卫";
                }
            }
        }
        if (body.isInWater() && body.getAirSupply() < 100) body.getJumpControl().jump();
        if (body.foodLevel() <= 14 && hasFood()) {
            var current = arbiter.activeSnapshot();
            if (current.isEmpty() || current.get().priority().ordinal() < ActionPriority.SAFETY.ordinal()) {
                submit(UUID.randomUUID().toString(), BodyOrder.eat(), ActionPriority.SAFETY);
            }
        }
    }

    private void defendLocally() {
        localThreats = localThreats.stream().filter(m -> m.isAlive() && !m.isRemoved()
                && m.level() == body.level() && !body.isAlliedTo(m)).toList();
        if (localThreats.isEmpty()) {
            body.getNavigation().stop(); body.setSprinting(false);
            if (body.tickCount - lastThreatTick >= 100 && body.tickCount-body.getLastHurtByMobTimestamp()>=100 && body.onGround() && !body.isInWater()) complete("local threat no longer nearby; checkpoint will be revalidated (no kill claimed)");
            else { safetyMode = "safety_watch"; safetyReason = "已拉开距离，确认附近安全后恢复工作"; }
            return;
        }
        Mob nearest = localThreats.getFirst(); safetyThreat = nearest.getUUID();
        selectBestWeapon();
        double weapon = weaponScore(body.getMainHandItem());
        boolean explosive = localThreats.stream().anyMatch(m -> m instanceof net.minecraft.world.entity.monster.Creeper c
                && body.distanceToSqr(c) < 64 && (c.getSwellDir() > 0 || c.isIgnited()));
        long close = localThreats.stream().filter(m -> body.distanceToSqr(m) < 100).count();
        boolean wounded = body.getHealth() <= Math.max(8, body.getMaxHealth() * 0.45F);
        boolean losing = body.getHealth() <= safetyStartHealth - 6 || (body.tickCount - safetyStartTick > 160 && body.getHealth() < safetyStartHealth);
        boolean overwhelmed = close >= 3 || (close >= 2 && weapon < 4) || nearest.getMaxHealth() >= 40;
        if (wounded || explosive || overwhelmed || losing) {
            retreatUntil = body.tickCount + 100;
            safetyReason = explosive ? "苦力怕即将爆炸，撤离" : wounded ? "血量危险，撤离" : overwhelmed ? "敌众或装备不足，撤离" : "持续受伤，停止硬拼并撤离";
        }
        if (body.tickCount < retreatUntil) {
            safetyMode = "retreating"; retreat(nearest); return;
        }
        body.setSprinting(false); safetyMode = "defending"; safetyReason = "本地迎敌；受伤或被围攻时切换撤退";
        attack(nearest.getUUID());
    }

    private static double weaponScore(ItemStack stack) {
        final double[] damage = {1};
        stack.forEachModifier(EquipmentSlot.MAINHAND, (attribute, modifier) -> {
            if (attribute.equals(net.minecraft.world.entity.ai.attributes.Attributes.ATTACK_DAMAGE)
                    && modifier.operation() == net.minecraft.world.entity.ai.attributes.AttributeModifier.Operation.ADD_VALUE)
                damage[0] += modifier.amount();
        });
        return damage[0];
    }
    private void selectBestWeapon() {
        int best = body.selectedSlot(); double score = weaponScore(body.getMainHandItem());
        for (int slot = 0; slot < body.inventory().getContainerSize(); slot++) {
            double candidate = weaponScore(body.inventory().getItem(slot));
            if (candidate > score) { best = slot; score = candidate; }
        }
        if (best != body.selectedSlot()) body.selectSlot(best);
    }
    private boolean escapeFloor(BlockPos feet) { return TravelTerrain.standable(body,feet); }
    private double escapeScore(BlockPos pos) {
        Vec3 point = Vec3.atBottomCenterOf(pos);
        return localThreats.stream().mapToDouble(m -> m.position().distanceToSqr(point)).min().orElse(0)
                - Math.abs(pos.getY() - body.getY()) * 4;
    }
    private void retreat(Mob nearest) {
        body.setSprinting(body.foodLevel() > 6);
        if(escapeProgress==null||body.position().distanceToSqr(escapeProgress)>1){escapeProgress=body.position();escapeProgressTick=body.tickCount;}
        boolean invalid=escapeTarget!=null&&(!escapeFloor(BlockPos.containing(escapeTarget.x,Math.ceil(escapeTarget.y),escapeTarget.z))||body.tickCount-escapeProgressTick>=100);
        if(invalid){escapeTarget=null;body.getNavigation().stop();escapeProgressTick=body.tickCount;}
        if (body.tickCount >= nextEscapePlan && (escapeTarget==null || body.getNavigation().isDone())) {
            nextEscapePlan = body.tickCount + 20;
            List<BlockPos> candidates = new ArrayList<>();
            for (int radius : new int[] {3, 6, 10}) for (int angle = 0; angle < 8; angle++) {
                double radians = angle * Math.PI / 4;
                for (int dy = 2; dy >= -2; dy--) {
                    BlockPos pos = BlockPos.containing(body.getX() + Math.cos(radians) * radius, body.getY() + dy, body.getZ() + Math.sin(radians) * radius);
                    if (escapeFloor(pos)) { candidates.add(pos); break; }
                }
            }
            candidates.sort(Comparator.comparingDouble(this::escapeScore).reversed());
            boolean planned = false;
            // Reserve attempts for short escapes too, rather than spending the whole budget on unreachable distant points.
            List<BlockPos> attempts = new ArrayList<>();
            for (int radius : new int[] {3, 6, 10}) candidates.stream()
                    .filter(pos -> Math.abs(Math.sqrt(pos.distToCenterSqr(body.position())) - radius) < 2)
                    .limit(2).forEach(attempts::add);
            for (BlockPos pos : attempts) {
                if (escapeScore(pos) <= escapeScore(body.blockPosition()) + 4) continue;
                var path = body.getNavigation().createPath(pos, 0);
                if (path == null || !path.canReach()) continue;
                boolean safe = true;
                for (int n = 1; n < path.getNodeCount(); n++) {
                    var node = path.getNode(n);
                    var step = new BlockPos(node.x, node.y, node.z);
                    if (!escapeFloor(step) || (n > 0 && path.getNode(n - 1).y - node.y > 2)
                            || localThreats.stream().anyMatch(m -> m.position().distanceToSqr(Vec3.atBottomCenterOf(step)) < Math.min(4, body.distanceToSqr(m)))) { safe = false; break; }
                }
                if (safe && body.getNavigation().moveTo(path, body.foodLevel() > 6 ? 1.45 : 1.15)) {
                    escapeTarget = TravelTerrain.feetPoint(body,pos); escapeProgress=body.position();escapeProgressTick=body.tickCount;planned = true; break;
                }
            }
            if (!planned && body.getNavigation().isDone()) {
                escapeTarget = null;
                safetyReason = "撤退路径受阻，继续寻找安全出口并近身反击";
            }
        }
        if (escapeTarget == null && body.getNavigation().isDone()) safetyReason = "撤退路径受阻，继续寻找安全出口并近身反击";
        // Keep navigating while striking a pursuer already in reach; never chase during retreat.
        if (meleeReachable(nearest) && !(nearest instanceof net.minecraft.world.entity.monster.Creeper)) attack(nearest.getUUID(), false);
        // The typed navigation step owns jump input, including during retreat.
    }

    private void breathe() {
        // Air recovery is not land recovery. Keep one local lease through the shore exit.
        if(!body.isInWater()&&body.onGround()&&!body.isUnderWater()) {
            body.getNavigation().stop();
            if(++breathingTicks>=20){wetTicks=0;complete("WATER_RECOVERED: stable dry ground; replan interrupted work from actual position");}
            travelPhase="shore_settling";return;
        }
        breathingTicks=0;
        if(actionTicks>1200){nextWaterRecovery=body.tickCount+200;fail("WATER_EXIT_BLOCKED: no verified shore reached in 1200 game ticks; surface support remains active, inspect another route or boat");return;}
        if(body.isUnderWater()) {
            if(breathTarget==null||body.tickCount>=nextAirSearch){
                nextAirSearch=body.tickCount+40;
                breathTarget=TravelTerrain.air(body,32).stream().filter(p->swimSegmentClear(Vec3.atBottomCenterOf(p))).findFirst().orElse(null);
            }
            if(breathTarget!=null)swimToward(breathTarget,1.2);
            else body.getNavigation().stop();
            body.getJumpControl().jump();travelPhase=breathTarget==null?"surfacing_blocked":"surfacing";return;
        }
        if(recoveryShore!=null&&!TravelTerrain.standable(body,recoveryShore)){rejectedShores.add(recoveryShore);recoveryShore=null;}
        if(recoveryShore==null&&body.tickCount>=nextAirSearch){
            nextAirSearch=body.tickCount+40;
            recoveryShore=TravelTerrain.shores(body,32).stream().filter(p->!rejectedShores.contains(p)).findFirst().orElse(null);
            travelProgress=null;
        }
        if(recoveryShore==null){body.getNavigation().stop();body.getJumpControl().jump();travelPhase="shore_searching";return;}
        moveTo(recoveryShore,1.2,false);
        if(travelPhase.equals("path_blocked")){rejectedShores.add(recoveryShore);recoveryShore=null;travelProgress=null;nextAirSearch=0;}
        if(body.isInWater())body.getJumpControl().jump();
    }
    private boolean swimSegmentClear(Vec3 point) {
        Vec3 delta=point.subtract(body.position());int steps=Math.max(1,(int)Math.ceil(delta.length()*2));
        if(delta.length()>32)return false;
        for(int i=1;i<=steps;i++) {
            Vec3 pos=body.position().add(delta.scale((double)i/steps));var block=BlockPos.containing(pos);
            if(!body.level().hasChunkAt(block)||io.github.yufeiyufei888.hearthcrew.gameplay.TravelTerrain.dangerous(body,block)
                ||!body.level().noCollision(body,body.getBoundingBox().move(pos.subtract(body.position()))))return false;
        }
        return true;
    }
    private void swimToward(BlockPos target,double speed) {
        body.getNavigation().stop();
        Vec3 wanted=Vec3.atBottomCenterOf(target);
        if(!TravelTerrain.standable(body,target)) {
            var surface=body.blockPosition();for(int up=0;up<12&&body.level().hasChunkAt(surface)&&body.level().getFluidState(surface).is(net.minecraft.tags.FluidTags.WATER);up++)surface=surface.above();
            wanted=new Vec3(wanted.x,Math.max(wanted.y,surface.getY()-.5),wanted.z);
        }
        boolean shore=io.github.yufeiyufei888.hearthcrew.gameplay.TravelTerrain.standable(body,target);
        travelPhase=shore?"seeking_shore":wanted.y>body.getY()+0.5?"surfacing":"swimming";
        if(!swimSegmentClear(wanted)) {
            Vec3 best=null;double score=Double.POSITIVE_INFINITY;
            for(int dy: new int[]{0,1,-1})for(int angle=0;angle<8;angle++) {
                Vec3 point=body.position().add(Math.cos(angle*Math.PI/4)*2,dy,Math.sin(angle*Math.PI/4)*2);
                var feet=BlockPos.containing(point);
                if(body.level().getFluidState(feet).isEmpty()&&!io.github.yufeiyufei888.hearthcrew.gameplay.TravelTerrain.standable(body,feet))continue;
                double candidate=point.distanceToSqr(wanted);
                if(candidate<score&&swimSegmentClear(point)){best=point;score=candidate;}
            }
            if(best==null){travelPhase="path_blocked";if(body.isUnderWater())body.getJumpControl().jump();return;}
            wanted=best;
        }
        body.getLookControl().setLookAt(wanted.x,wanted.y+body.getEyeHeight(),wanted.z,30,30);
        body.getMoveControl().setWantedPosition(wanted.x,wanted.y,wanted.z,speed);
        if(wanted.y>body.getY()+0.1 || !body.isUnderWater() && target.getY()>=body.getY()-0.5)body.getJumpControl().jump();
    }
    private boolean travelLandDestination;
    private void moveTo(BlockPos target, double speed, boolean finish) {
        if (target == null) { fail("missing destination"); return; }
        boolean distantUnknown=!body.level().hasChunkAt(target)&&target.distSqr(body.blockPosition())>28*28;
        Vec3 destination = TravelTerrain.standable(body,target)?TravelTerrain.feetPoint(body,target):Vec3.atBottomCenterOf(target);
        if(travelProgress==null||!target.equals(travelDestination)) {
            travelDestination=target.immutable();travelStart=body.position();travelStartAir=body.getAirSupply();arrivalTicks=0;
            travelLandDestination=body.level().getFluidState(target).isEmpty();
            travelProgress=new TravelProgress(arbiter.gameTick(),body.position().distanceTo(destination),Math.abs(body.getY()-target.getY()));
        }
        boolean digging=arbiter.activeSnapshot().map(a->!java.util.Set.of(BodyOrder.Kind.MOVE,BodyOrder.Kind.SWIM,BodyOrder.Kind.EXPLORE,BodyOrder.Kind.FOLLOW,BodyOrder.Kind.GUARD,BodyOrder.Kind.SELF_DEFENCE,BodyOrder.Kind.BREATHE).contains(effectiveOrder(a.id(),a.payload()).kind())).orElse(false);
        boolean aquatic=body.isInWater()||body.getNavigation().allowsWater();
        boolean landDestination=travelLandDestination;
        boolean arrived=aquatic&&!landDestination?body.position().distanceToSqr(destination)<.64&&!body.isUnderWater():TravelTerrain.landedAt(body,target,digging?.25:.6);
        if(!distantUnknown&&(!aquatic||landDestination)&&!TravelTerrain.standable(body,target)&&!body.level().getBlockState(target).is(net.minecraft.tags.BlockTags.CLIMBABLE)){fail("INVALID_LAND_STANCE: inspect support, collision, flooding and height at "+target);return;}
        if(arrived){if(++arrivalTicks<5){body.getNavigation().stop();return;}}else arrivalTicks=0;
        if(arbiter.activeSnapshot().map(a->effectiveOrder(a.id(),a.payload()).kind()==BodyOrder.Kind.BREATHE).orElse(false))arrived &= !body.isUnderWater();
        if (arrived) {
            body.getNavigation().stop();travelPhase="arrived";
            if (finish) complete("position and height verified");return;
        }
        if (!body.level().hasChunkAt(target)&&!distantUnknown) {WorldEvents.requestExplorationChunks(body,target);travelPhase="awaiting_chunk";return;}
        var path=body.getNavigation().getPath();
        var progress=travelProgress.update(arbiter.gameTick(),body.position().distanceTo(destination),Math.abs(body.getY()-target.getY()),path==null?0:path.getNextNodeIndex());
        if(progress==TravelProgress.Result.REPLAN){body.getNavigation().stop();travelPhase="replanning";}
        if(progress==TravelProgress.Result.BLOCKED) {
            travelPhase="path_blocked";
            if(arbiter.activeSnapshot().map(a->effectiveOrder(a.id(),a.payload()).kind()==BodyOrder.Kind.BREATHE).orElse(false)) {
                travelProgress=null;body.getJumpControl().jump();return;
            }
            if(arbiter.activeSnapshot().map(a->effectiveOrder(a.id(),a.payload()).kind()==BodyOrder.Kind.SELF_DEFENCE).orElse(false)) {
                retreatUntil=body.tickCount+100;nextEscapePlan=0;safetyReason="接敌路径受阻，改为撤离";return;
            }
            fail("NO_GOAL_PROGRESS: two 100-tick windows without approach, height or path progress; phase="+travelPhase);return;
        }
        if(body.isInWater()){swimToward(target,speed);return;}
        travelPhase="walking";
        if(actionTicks%20==1||body.getNavigation().isDone())body.getNavigation().moveTo(target.getX()+.5,target.getY(),target.getZ()+.5,speed);
    }

    private void follow(UUID uuid, boolean guard) {
        Entity target = entity(uuid);
        if (target == null || !target.isAlive()) { fail("follow target unavailable"); return; }
        if (guard && target instanceof LivingEntity living) {
            LivingEntity attacker = living.getLastHurtByMob();
            if (attacker instanceof net.minecraft.world.entity.monster.Enemy
                    && attacker.isAlive() && !attacker.isRemoved()
                    && attacker.level() == body.level()
                    && attacker.distanceToSqr(living) < 1024
                    && !body.isAlliedTo(attacker)) {
                attack(attacker.getUUID()); return;
            }
        }
        if (body.distanceToSqr(target) > 9) moveTo(target.blockPosition(), 1.05, false);
        else body.getNavigation().stop();
    }

    private void mine(BlockPos target, boolean partOfGather) {
        miningTarget=target;
        if (target == null) { failGatherOrMine(partOfGather, "missing block"); return; }
        var resourceTask=resourceChildren.get(executing);
        if(resourceTask!=null&&resourceTask.root!=null&&(target.distSqr(accessAccounts.get(executing).origin)>resourceTask.root.radius()*resourceTask.root.radius()||CrewWorldData.get(body.server).playerBlock(body.level(),target))){failGatherOrMine(partOfGather,"RESOURCE_SCOPE_OR_PROTECTION: preparation cannot extend or alter protected land");return;}
        if (miningBroken) {
            if(harvestLoss.contains(executing)){finish(ActionState.PARTIAL,"confirmed produced drop loss; recovery closed, no pickup credited");return;}
            if (miningDrops.isEmpty()) {
                if (partOfGather) finishGatherBlock();
                else finishMineBlock();
                return;
            }
            if (actionTicks - miningBreakTick > 400) {
                if (partOfGather) reconcileGather("block was destroyed but drop acquisition remained uncertain");
                else finish(ActionState.PARTIAL, "block removed but not all produced drops acquired");
                return;
            }
            ItemEntity next = miningDrops.keySet().stream().map(this::entity)
                    .filter(ItemEntity.class::isInstance).map(ItemEntity.class::cast)
                    .filter(Entity::isAlive).min(Comparator.comparingDouble(body::distanceToSqr)).orElse(null);
            if (next == null) {
                if (partOfGather) reconcileGather("produced drop no longer observable; acquisition unconfirmed");
                else finish(ActionState.PARTIAL, "produced drops no longer observable; acquisition unconfirmed");
                return;
            }
            if (!body.inventory().canAddItem(next.getItem())) {
                if (partOfGather) reconcileGather("block removed; inventory full before acquisition");
                else finishGatherOrMine(false, "block removed; inventory full before acquisition");
                return;
            }
            moveToDrop(next); return;
        }
        BlockState state = body.level().getBlockState(target);

        if (state.isAir()) {
            if (partOfGather) {
                reconcileGather("recorded target became air before the executor broke it");
            } else fail(harvest.pendingAt(body.level().dimension().location().toString(),target)?"RECOVERY_REQUIRED: block already removed; inspect harvestEvidence.pendingDrops and PICKUP, do not MINE again":"target absent before mining; no acquisition evidence");
            return;
        }
        if (partOfGather && !gatherResource.equals(BuiltInRegistries.BLOCK.getKey(state.getBlock()))) {
            reconcileGather("recorded target changed before mining"); return;
        }
        if (partOfGather && !gatherExecutionWithinBounds(target)) {
            reconcileGather("GATHER target or executor left its 32 block origin bound"); return;
        }
        if (partOfGather && !WorldEvents.team().ownsGatherBlock(body, executing.value(), target)) {
            reconcileGather("GATHER candidate reservation is missing or belongs to another action"); return;
        }
        boolean capacity=false;for(int slot=0;slot<36;slot++){var item=body.inventory().getItem(slot);if(item.isEmpty()||item.getCount()<item.getMaxStackSize()){capacity=true;break;}}
        if(!capacity){failGatherOrMine(partOfGather,"inventory has no free capacity before mining; arrange storage first");return;}
        int harvestSlot=TargetInspection.bestTool(body,state);
        if(harvestSlot<0){failGatherOrMine(partOfGather,TargetInspection.missingTool(state));return;}
        body.selectSlot(harvestSlot);
        if(!partOfGather&&(target.getY()<body.getY()-.4||!reachable(target))){
            if(!accessTo(target,false))return;
        }
        if (!reachable(target)) { approach(target); return; }
        if(!partOfGather&&unsafeMiningPosition(target)){fail("UNSAFE_MINING_POSITION: own support, overhead or adjacent liquid/falling block");return;}
        var prep=resourceChildren.get(executing);
        if(prep!=null&&prep.order!=null&&prep.preparation){
            var root=arbiter.activeSnapshot().orElseThrow().payload();
            if(prep.broken>=prep.root.preparation().maxBreaks()){resourceTerminal(ActionState.PARTIAL,"PREPARATION_BREAK_BUDGET_EXHAUSTED");return;}
        }
        if (state.getDestroySpeed(body.level(), target) < 0) { failGatherOrMine(partOfGather, "unbreakable block"); return; }
        if (state.requiresCorrectToolForDrops() && !body.getMainHandItem().isCorrectToolForDrops(state)) {
            failGatherOrMine(partOfGather, "appropriate harvest tool required"); return;
        }
        if (body.level().getBlockEntity(target) != null) { failGatherOrMine(partOfGather, "container or block entity protected"); return; }
        if (CrewWorldData.get(body.getServer()).playerBlock(body.level(), target)
                && (partOfGather || arbiter.activeSnapshot().map(action -> action.priority() != ActionPriority.OWNER).orElse(true))) {
            if (partOfGather) reconcileGather("recorded target became player protected");
            else fail("placement protected: " + CrewWorldData.get(body.getServer()).protectionReason(body.level(),target));
            return;
        }
        if (miningState != null && !state.equals(miningState)) {
            if (partOfGather) reconcileGather("recorded target changed during mining");
            else fail("block changed during mining");
            return;
        }
        miningState = state;
        // Keep a reached mining stance; a leftover movement input otherwise causes airborne penalties.
        body.getNavigation().stop();
        body.getLookControl().setLookAt(target.getX()+.5,target.getY()+.5,target.getZ()+.5,35,35);
        net.minecraft.server.level.ServerPlayer context = beginContext();
        try {
            miningProgress += state.getDestroyProgress(context, body.level(), target);
            body.swing(InteractionHand.MAIN_HAND);
            ((ServerLevel) body.level()).destroyBlockProgress(body.getId(), target, Math.min(9, (int)(miningProgress * 10)));
            if (miningProgress >= 1) {
                Set<UUID> earlier = new HashSet<>();
                for (ItemEntity drop : body.level().getEntitiesOfClass(ItemEntity.class, new AABB(target).inflate(2))) earlier.add(drop.getUUID());
                if(!preparationCanBreak())return;
                boolean broken = context.gameMode.destroyBlock(target);
                ((ServerLevel) body.level()).destroyBlockProgress(body.getId(), target, -1);
                if (broken && !body.level().getBlockState(target).equals(state)) {
                    body.addExhaustion(0.005F);preparationBroke();
                    miningBroken = true; miningBreakTick = actionTicks;
                    if(prep!=null&&prep.order!=null&&prep.preparation)prep.broken++;
                    for (ItemEntity drop : body.level().getEntitiesOfClass(ItemEntity.class, new AABB(target).inflate(2)))
                        if (!earlier.contains(drop.getUUID())) miningDrops.put(drop.getUUID(), drop.getItem().getCount());
                    harvest.broken(executing.value(),body.level().dimension().location().toString(),target,miningDrops);
                    if (partOfGather) {
                        gatherRequestedDropObserved = miningDrops.keySet().stream()
                                .anyMatch(dropId -> gatherResourceDrop(dropId, gatherResource));
                    }
                    if (miningDrops.isEmpty()) {
                        if (partOfGather) reconcileGather("block was destroyed but its drop could not be observed");
                        else finish(ActionState.PARTIAL, "block removed without observable material drops");
                    } else if (partOfGather && !gatherRequestedDropObserved) {
                        reconcileGather("block was destroyed but requested wood drop was not observed");
                    }
                } else failGatherOrMine(partOfGather, "block interaction rejected");
            }
        } finally { endContext(context); }
    }

    private void mine(BlockPos target) { mine(target, false); }

    private void gather(BodyOrder order) {
        if (!BodyOrder.supportedGatherResource(order.resource()) || order.count() < 1 || order.count() > 64) {
            fail("unsupported GATHER resource or count"); return;
        }
        if (gatherOrigin == null) gatherOrigin = body.position();
        if (gatherResource == null) gatherResource = order.resource();
        if (!order.resource().equals(gatherResource)) { fail("GATHER resource changed during execution"); return; }
        if (gatherCollected >= order.count()) { completeGather("requested resource count acquired"); return; }
        if (!gatherScanComplete) {
            scanGatherPlan(order.resource(), order.count());
            if (!gatherScanComplete) return;
            if (gatherCandidates.isEmpty()) {
                gatherPlanFailure = "no approved natural tree candidates in inspected loaded radius; inspect gather diagnostics, not proof of no trees";
            } else {
                if (!WorldEvents.team().tryClaimGather(body, executing.value(), gatherCandidates)) {
                    // Another body can reserve the tree while our scan is in progress.
                    // No world effect has occurred: rescan with the new reservations.
                    gatherCandidates.clear(); prepareGatherScan(); return;
                }
            }
        }
        if (gatherPlanFailure != null) {
            finish(gatherCollected > 0 ? ActionState.PARTIAL : ActionState.FAILED,
                    gatherPlanFailure + "; collected=" + gatherCollected);
            return;
        }
        if (gatherTarget == null) {
            if (gatherCandidateIndex >= gatherCandidates.size()) {
                finish(gatherCollected > 0 ? ActionState.PARTIAL : ActionState.FAILED,
                        "approved tree candidates exhausted; collected=" + gatherCollected);
                return;
            }
            BlockPos candidate = gatherCandidates.get(gatherCandidateIndex);
            BlockState state = body.level().getBlockState(candidate);
            if (state.isAir() || !order.resource().equals(BuiltInRegistries.BLOCK.getKey(state.getBlock()))) {
                reconcileGather("recorded tree candidate changed before mining: " + candidate);
                return;
            }
            if (CrewWorldData.get(body.getServer()).playerBlock(body.level(),candidate)||!order.resource().equals(BuiltInRegistries.BLOCK.getKey(body.level().getBlockState(candidate).getBlock()))) {
                reconcileGather("recorded tree candidate became protected or invalid: " + candidate);
                return;
            }
            gatherTarget = candidate;
        }
        mine(gatherTarget, true);
    }

    private void prepareGatherScan() {
        treeReadCache.clear();gatherResourceCandidates = gatherEligibleRoots = 0; gatherRejections.clear(); gatherExamples.clear();
        if (gatherOrigin == null) { gatherScanComplete = true; return; }
        BlockPos center = BlockPos.containing(gatherOrigin);
        gatherScanMinX = center.getX() - 32;
        gatherScanMinY = Math.max(body.level().getMinBuildHeight(), center.getY() - 32);
        gatherScanMinZ = center.getZ() - 32;
        int maxY = Math.min(body.level().getMaxBuildHeight() - 1, center.getY() + 32);
        gatherScanTotal = io.github.yufeiyufei888.hearthcrew.runtime.NearbyObservation.ordered(32).size();
        gatherScanCursor = 0;
        gatherPlanRoot = null;
        gatherPlanRootDistance = Double.MAX_VALUE;
        gatherScanComplete = gatherScanTotal == 0;
    }

    private static final class ScanDeferred extends RuntimeException {}
    private final Map<BlockPos,BlockState> treeReadCache=new HashMap<>();
    private int treeCacheCursor=-1;
    private BlockState treeState(BlockPos position){
        // Execution revalidates recorded blocks against the live world; this cache only bounds planning reads.
        var old=treeReadCache.get(position);if(old!=null)return old;
        if(!io.github.yufeiyufei888.hearthcrew.runtime.ScanBudget.claim(body.getServer(),1))throw new ScanDeferred();
        var state=body.level().getBlockState(position);if(treeReadCache.size()>=32768)throw new IllegalArgumentException("TREE_PLANNING_BOUND_EXCEEDED");treeReadCache.put(position.immutable(),state);return state;
    }
    /** Scan a bounded number of blocks per tick so order acceptance cannot stall the server. */
    private void scanGatherPlan(ResourceLocation resource, int requested) {
        final int budget = 1024;
        int checked = 0;
        while (checked++ < budget && gatherScanCursor < gatherScanTotal) {
            if(!io.github.yufeiyufei888.hearthcrew.runtime.ScanBudget.claim(body.getServer(),1))return;
            int index = gatherScanCursor++;
            if(treeCacheCursor!=index){treeReadCache.clear();treeCacheCursor=index;}
            try {
            BlockPos candidate=BlockPos.containing(gatherOrigin).offset(io.github.yufeiyufei888.hearthcrew.runtime.NearbyObservation.ordered(32).get(index));
            if (gatherOrigin.distanceToSqr(Vec3.atCenterOf(candidate)) > 32 * 32 || !body.level().hasChunkAt(candidate)) {
                rejectGatherCandidate("scan_boundary_or_unloaded", candidate); continue;
            }
            if (!resource.equals(BuiltInRegistries.BLOCK.getKey(body.level().getBlockState(candidate).getBlock()))) continue;
            gatherResourceCandidates++;
            if (CrewWorldData.get(body.getServer()).playerBlock(body.level(), candidate)) { rejectGatherCandidate("protected", candidate); continue; }
            if (!isRecordedGatherBlockAllowed(candidate, resource)) { rejectGatherCandidate("unsupported_block", candidate); continue; }
            if (!body.level().getBlockState(candidate.below()).is(BlockTags.DIRT) || !hasNaturalCanopy(candidate)) {
                rejectGatherCandidate("tree_shape_or_canopy", candidate); continue;
            }
            if (hasNearbyConstruction(candidate, resource)) { rejectGatherCandidate("construction_or_terrain_nearby", candidate); continue; }
            List<BlockPos> plan = buildStrictVerticalPlan(candidate, resource, requested).stream().filter(p->!gatherCandidates.contains(p)).toList();
            if (plan.isEmpty()) { rejectGatherCandidate("no_remaining_approved_logs", candidate); continue; }
            if (!WorldEvents.team().canClaimGather(body, executing.value(), plan)) { rejectGatherCandidate("resource_conflict", candidate); continue; }
            gatherEligibleRoots++;
            double distance = gatherOrigin.distanceToSqr(Vec3.atCenterOf(candidate));
            if (distance < gatherPlanRootDistance) {
                gatherPlanRoot = candidate.immutable();
                gatherPlanRootDistance = distance;
            }
            for(var p:plan)if(gatherCandidates.size()<requested)gatherCandidates.add(p);
            if(gatherCandidates.size()>=requested){gatherScanComplete=true;gatherScanCursor=gatherScanTotal;}
            treeReadCache.clear();
            } catch(ScanDeferred exhausted){gatherScanCursor--;return;}
        }
        if (gatherScanCursor >= gatherScanTotal) gatherScanComplete = true;
    }

    private List<BlockPos> buildStrictVerticalPlan(BlockPos root,ResourceLocation resource,int requested){
        var result=new ArrayList<BlockPos>();var queue=new ArrayDeque<BlockPos>();var seen=new HashSet<BlockPos>();queue.add(root);
        while(!queue.isEmpty()&&result.size()<Math.min(64,requested)&&seen.size()<512){var at=queue.removeFirst();if(!seen.add(at)||at.getY()<root.getY()||at.getY()>root.getY()+24||at.distSqr(root)>24*24)continue;
            if(!isRecordedGatherBlockAllowed(at,resource)||hasNearbyConstruction(at,resource))continue;result.add(at);
            for(int dy=0;dy<=1;dy++)for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++)if(dx!=0||dy!=0||dz!=0)queue.add(at.offset(dx,dy,dz));
        }return List.copyOf(result);
    }
    /** Strictly accepts a vertical single-trunk root with non-persistent leaves. */
    private boolean isStrictNaturalTreeRoot(BlockPos position, ResourceLocation resource) {
        if (!isRecordedGatherBlockAllowed(position, resource)
                || !treeState(position.below()).is(BlockTags.DIRT)) return false;
        return hasNaturalCanopy(position)
                && !hasNearbyConstruction(position, resource);
    }

    private boolean isRecordedGatherBlockAllowed(BlockPos position, ResourceLocation resource) {
        if (!body.level().hasChunkAt(position) || gatherOrigin == null
                || gatherOrigin.distanceToSqr(Vec3.atCenterOf(position)) > 32 * 32) return false;
        BlockState state = treeState(position);
        return body.level().hasChunkAt(position)
                && resource.equals(BuiltInRegistries.BLOCK.getKey(state.getBlock()))
                && state.is(BlockTags.LOGS) && !state.isAir()
                && state.isCollisionShapeFullBlock(body.level(), position)
                && state.getFluidState().isEmpty()
                && body.level().getBlockEntity(position) == null
                && !CrewWorldData.get(body.getServer()).playerBlock(body.level(), position);
    }

    private boolean hasNearbyConstruction(BlockPos position, ResourceLocation resource) {
        for (int dx = -2; dx <= 2; dx++) for (int dz = -2; dz <= 2; dz++) {
            for (int dy = -1; dy <= 10; dy++) {
                BlockPos nearby = position.offset(dx, dy, dz);
                if (gatherOrigin.distanceToSqr(Vec3.atCenterOf(nearby)) > 32 * 32) return true;
                if (!body.level().hasChunkAt(nearby)) continue;
                BlockState state = treeState(nearby);
                if (dy < 0) continue; // terrain below the root is not a building-side signal
                if (dx == 0 && dz == 0 && resource.equals(BuiltInRegistries.BLOCK.getKey(state.getBlock()))) continue;
                if (state.isAir() || state.is(BlockTags.LEAVES) || state.is(BlockTags.DIRT)) continue;
                if(state.is(BlockTags.PLANKS)||state.hasBlockEntity()||state.getBlock() instanceof net.minecraft.world.level.block.StairBlock||state.getBlock() instanceof net.minecraft.world.level.block.SlabBlock||state.getBlock() instanceof net.minecraft.world.level.block.DoorBlock||state.getBlock() instanceof net.minecraft.world.level.block.FenceBlock||state.is(net.minecraft.world.level.block.Blocks.GLASS)||state.is(net.minecraft.world.level.block.Blocks.COBBLESTONE)||state.is(net.minecraft.world.level.block.Blocks.STONE_BRICKS))return true;
            }
        }
        return false;
    }

    private boolean hasNaturalCanopy(BlockPos root) {
        int leaves = 0;
        for (int dx = -4; dx <= 4; dx++) for (int dy = 1; dy <= 24; dy++) for (int dz = -4; dz <= 4; dz++) {
            if (Math.abs(dx)+Math.abs(dz)>6)continue;
            BlockPos nearby = root.offset(dx, dy, dz);
            if (gatherOrigin.distanceToSqr(Vec3.atCenterOf(nearby)) > 32 * 32) continue;
            if (!body.level().hasChunkAt(nearby)) continue;
            BlockState state = treeState(nearby);
            if (state.getBlock() instanceof LeavesBlock && state.hasProperty(LeavesBlock.PERSISTENT)
                    && state.getValue(LeavesBlock.PERSISTENT)) return false;
            if (state.getBlock() instanceof LeavesBlock && state.hasProperty(LeavesBlock.PERSISTENT)
                    && !state.getValue(LeavesBlock.PERSISTENT)) leaves++;
        }
        return leaves >= 2;
    }

    private void finishGatherBlock() {
        if (!gatherRequestedDropObserved) {
            reconcileGather("destroyed block had no confirmed requested wood drop");
            return;
        }
        if (gatherTarget != null && gatherCandidateIndex < gatherCandidates.size()
                && gatherCandidates.get(gatherCandidateIndex).equals(gatherTarget)) gatherCandidateIndex++;
        gatherTarget = null; miningState = null; miningProgress = 0.0F; miningBreakTick = 0;
        miningBroken = false; miningDrops.clear(); gatherRequestedDropObserved = false;
        if (gatherCollected >= gatherPartOfGatherOrder().count()) completeGather("requested resource count acquired");
    }

    private BodyOrder gatherPartOfGatherOrder() {
        var active = arbiter.activeSnapshot();
        if (active.isEmpty() || effectiveOrder(active.get().id(),active.get().payload()).kind() != BodyOrder.Kind.GATHER)
            throw new IllegalStateException("GATHER target lost its active order");
        return effectiveOrder(active.get().id(),active.get().payload());
    }

    private void completeGather(String reason) {
        finish(ActionState.COMPLETED, reason + "; collected=" + gatherCollected);
    }

    private void failGatherOrMine(boolean gather, String reason) {
        if (gather) finish(gatherCollected > 0 ? ActionState.PARTIAL : ActionState.FAILED,
                reason + "; collected=" + gatherCollected);
        else fail(reason);
    }

    private void finishGatherOrMine(boolean gather, String reason) {
        if (gather) finish(gatherCollected > 0 ? ActionState.PARTIAL : ActionState.FAILED,
                reason + "; collected=" + gatherCollected);
        else finish(ActionState.PARTIAL, reason);
    }

    private void reconcileGather(String reason) {
        finish(ActionState.RECONCILE_REQUIRED, reason + "; collected=" + gatherCollected);
    }

    private boolean gatherExecutionWithinBounds(BlockPos target) {
        return gatherOrigin != null && gatherDimension != null
                && gatherDimension.equals(body.level().dimension().location().toString())
                && gatherOrigin.distanceToSqr(Vec3.atCenterOf(target)) <= 32 * 32
                && gatherOrigin.distanceToSqr(body.position()) <= 32 * 32;
    }

    private void place(BodyOrder order) {
        BlockPos target = order.position();
        if (order.resource() != null) {
            int slot = -1;
            for (int i=0;i<body.inventory().getContainerSize();i++) {
                var stack = body.inventory().getItem(i);
                if (!stack.isEmpty() && stack.getItem() instanceof BlockItem && order.resource().equals(BuiltInRegistries.ITEM.getKey(stack.getItem()))) {slot=i;break;}
            }
            if (slot < 0) { fail("requested block item missing: " + order.resource()); return; }
            body.selectSlot(slot);
        }
        if (target == null) { fail("missing placement"); return; }
        if (!(body.getMainHandItem().getItem() instanceof BlockItem)) { fail("main hand is not a block"); return; }
        if (CrewWorldData.get(body.getServer()).playerBlock(body.level(), target)) { fail("placement protected: " + CrewWorldData.get(body.getServer()).protectionReason(body.level(),target)); return; }
        if (body.level().getBlockEntity(target) != null) { fail("block entity placement position protected"); return; }
        if (!body.level().getBlockState(target).canBeReplaced()) { fail("placement would overwrite a block"); return; }
        if (!reachable(target)) { approach(target); return; }
        BlockPos support = target.below();
        if (io.github.yufeiyufei888.hearthcrew.gameplay.PlayerInteractions.placementFace(body,target)==null) { approachPlacement(target); return; }
        net.minecraft.server.level.ServerPlayer context = beginContext();
        int before = body.getMainHandItem().getCount();
        var expectedBlock = ((BlockItem)body.getMainHandItem().getItem()).getBlock();
        var beforeState=body.level().getBlockState(target);
        String interactionFailure=null;
        try {
            var hit = new BlockHitResult(Vec3.atCenterOf(support).add(0, 0.5, 0), Direction.UP, support, false);
            io.github.yufeiyufei888.hearthcrew.gameplay.PlayerInteractions.place(body,target);
        } catch (RuntimeException error) { interactionFailure=error.getMessage()==null?error.getClass().getSimpleName():error.getMessage(); } finally { endContext(context); }
        if(interactionFailure!=null&&interactionFailure.contains("ENTITY_COLLISION")&&body.level().getBlockState(target).equals(beforeState)&&body.getMainHandItem().getCount()==before&&waitForPlacementClearance(target))return;
        if (placedVariantMatches(body.level().getBlockState(target),expectedBlock) && body.getMainHandItem().getCount() == before - 1) complete("placement and consumption verified: " + BuiltInRegistries.BLOCK.getKey(expectedBlock));
        else if(body.level().getBlockState(target).equals(beforeState) && body.getMainHandItem().getCount()==before)fail("placement produced no observed effect"+(interactionFailure==null?"":": "+interactionFailure));
        else finish(ActionState.RECONCILE_REQUIRED,"placement changed world/inventory without exact postconditions; do not replay");
    }

    /**
     * Executes a bounded, ordered build plan. A build step is either observed
     * already present or placed exactly once; no step is considered complete
     * without an exact block-id observation after the interaction.
     */
    private void build(BodyOrder order) {
        if(!buildPreflightDone.contains(executing)) {
            var planned=new HashSet<BlockPos>();var required=new HashMap<ResourceLocation,Integer>();
            for(var step:order.steps()){
                var target=step.position();if(!body.level().hasChunkAt(target)){fail("BUILD preflight: unloaded target "+target);return;}
                var expected=BuiltInRegistries.BLOCK.getOptional(step.block()).orElse(null);
                if(expected==null||!buildBlockSupported(step.block(),expected,target)){fail("BUILD preflight: unsupported blueprint block "+step.block());return;}
                if(!buildBlockMatches(target,step.block())){
                    if(!body.level().getFluidState(target).isEmpty()||!body.level().getBlockState(target).isAir()){fail("BUILD preflight: target not air "+target);return;}
                    if(CrewWorldData.get(body.getServer()).playerBlock(body.level(),target)){fail("BUILD preflight: protected target "+target);return;}
                    boolean support=false;for(var face:Direction.values()){var neighbor=target.relative(face);if(planned.contains(neighbor)||!body.level().getBlockState(neighbor).getCollisionShape(body.level(),neighbor).isEmpty()){support=true;break;}}
                    if(!support){fail("BUILD preflight: missing adjacent support "+target);return;}
                    required.merge(step.block(),1,Integer::sum);
                    // Later steps may gain a stance from earlier blueprint blocks; recheck at execution.
                }planned.add(target);
            }
            for(var entry:required.entrySet()) {int available=0;for(int slot=0;slot<36;slot++){var item=body.inventory().getItem(slot);if(item.getItem() instanceof BlockItem bi && entry.getKey().equals(BuiltInRegistries.BLOCK.getKey(bi.getBlock())))available+=item.getCount();}
                if(available<entry.getValue()){fail("BUILD preflight: missing material "+entry.getKey()+" required="+entry.getValue()+" available="+available);return;}}
            buildPreflightDone.add(executing);
        }
        if (order.steps().isEmpty()) { fail("build has no steps"); return; }
        if (buildOrigin == null) buildOrigin = body.position();
        // Recheck on every execution tick: submit() normally sets the origin,
        // so keeping this outside the null branch is part of the invariant.
        for (BodyOrder.BuildStep step : order.steps()) {
            if (buildOrigin.distanceToSqr(Vec3.atCenterOf(step.position())) > 32 * 32) {
                fail("build target exceeds 32 block start radius"); return;
            }
        }
        for (BodyOrder.BuildStep step : order.steps()) {
            BlockPos target = step.position();
            BuildObservation observed = buildSteps.get(target);
            if (observed != null) {
                if (!buildBlockMatches(target, observed.block())) {
                    finish(ActionState.RECONCILE_REQUIRED, "confirmed build step changed before resume");
                    return;
                }
                continue;
            }
            if (!body.level().hasChunkAt(target)) { fail("build target chunk unavailable"); return; }
            ResourceLocation expected = step.block();
            var expectedBlock = BuiltInRegistries.BLOCK.getOptional(expected).orElse(null);
            if (expectedBlock == null) { fail("build block is not registered"); return; }
            if (!buildBlockSupported(expected, expectedBlock, target)) {
                fail("build requires a registered minecraft non-falling BlockItem; placement rules still apply");
                return;
            }
            BlockState current = body.level().getBlockState(target);
            ResourceLocation actual = BuiltInRegistries.BLOCK.getKey(current.getBlock());
            if (expected.equals(actual)) {
                buildSteps.put(target, new BuildObservation(target, expected, true, false));
                continue;
            }
            if (!body.level().getFluidState(target).isEmpty()) { fail("UNSUPPORTED_MEDIUM: BUILD requires air, water replacement unsupported; placed=" + buildSteps.values().stream().filter(BuildObservation::placed).count()); return; }
            if (!current.isAir()) { fail("build refuses to overwrite unknown block"); return; }
            if (CrewWorldData.get(body.getServer()).playerBlock(body.level(), target)) { fail("player placed build target protected"); return; }
            if (body.level().getBlockEntity(target) != null) { fail("build target block entity protected"); return; }
            if (io.github.yufeiyufei888.hearthcrew.gameplay.PlayerInteractions.placementFace(body,target)==null) { approachPlacement(target); return; }
            int slot = findBlockSlot(expected);
            if (slot < 0) {
                boolean placedAny = buildSteps.values().stream().anyMatch(BuildObservation::placed);
                finish(placedAny ? ActionState.PARTIAL : ActionState.FAILED, "matching building material unavailable");
                return;
            }
            body.selectSlot(slot);
            ItemStack hand = body.getMainHandItem();
            int before = hand.getCount();
            if (!(hand.getItem() instanceof BlockItem)) { fail("selected build material is not a block"); return; }
            BlockPos support = target.below();
            if(io.github.yufeiyufei888.hearthcrew.gameplay.PlayerInteractions.placementFace(body,target)==null){fail("build target has no reachable supporting face");return;}
            net.minecraft.server.level.ServerPlayer context = beginContext();
            RuntimeException interactionFailure = null;
            try {
                var hit = new BlockHitResult(Vec3.atCenterOf(support).add(0, 0.5, 0), Direction.UP, support, false);
                io.github.yufeiyufei888.hearthcrew.gameplay.PlayerInteractions.place(body,target);
            } catch (RuntimeException failure) {
                interactionFailure = failure;
            }
            try {
                endContext(context);
            } catch (RuntimeException failure) {
                if (interactionFailure == null) interactionFailure = failure;
            }
            if (interactionFailure != null) {
                if(String.valueOf(interactionFailure.getMessage()).contains("ENTITY_COLLISION")&&body.level().getBlockState(target).equals(current)&&body.getMainHandItem().getCount()==before&&waitForPlacementClearance(target))return;
                observeBuildInteraction(target, expected, before,
                        "build interaction rejected: " + interactionFailure.getMessage());
                return;
            }
            final BlockState after;
            final int afterCount;
            try {
                after = body.level().getBlockState(target);
                afterCount = body.getMainHandItem().getCount();
            } catch (RuntimeException observationFailure) {
                finish(ActionState.RECONCILE_REQUIRED,
                        "build postcondition observation failed: " + observationFailure.getClass().getSimpleName());
                return;
            }
            boolean blockPlaced = placedVariantMatches(after,BuiltInRegistries.BLOCK.get(expected));
            if (blockPlaced && afterCount == before - 1) {
                placementWaitTicks=0;
                buildSteps.put(target, new BuildObservation(target, expected, false, true));
                return; // at most one world interaction per server tick
            }
            if (blockPlaced || afterCount != before) {
                finish(ActionState.RECONCILE_REQUIRED, "build interaction result is ambiguous");
            } else {
                fail("build placement postcondition absent");
            }
            return;
        }
        complete("all build steps observed");
    }

    /** Resolve an interaction exception only from fresh world and inventory observations. */
    private void observeBuildInteraction(BlockPos target, ResourceLocation expected, int before, String reason) {
        final BlockState after;
        final int afterCount;
        try {
            after = body.level().getBlockState(target);
            afterCount = body.getMainHandItem().getCount();
        } catch (RuntimeException observationFailure) {
            finish(ActionState.RECONCILE_REQUIRED, reason + "; postcondition observation failed");
            return;
        }
        boolean blockPlaced = placedVariantMatches(after,BuiltInRegistries.BLOCK.get(expected));
        boolean exact = blockPlaced && afterCount == before - 1;
        if (exact) {
            buildSteps.put(target, new BuildObservation(target, expected, false, true));
            return;
        }
        if (after.isAir() && afterCount == before) {
            fail(reason + "; no world or inventory change observed");
            return;
        }
        finish(ActionState.RECONCILE_REQUIRED, reason + "; world/inventory result is ambiguous");
    }

    private int findBlockSlot(ResourceLocation expected) {
        for (int slot = 0; slot < body.inventory().getContainerSize(); slot++) {
            ItemStack stack = body.inventory().getItem(slot);
            if (stack.getItem() instanceof BlockItem blockItem
                    && expected.equals(BuiltInRegistries.BLOCK.getKey(blockItem.getBlock()))) return slot;
        }
        return -1;
    }

    private static boolean placedVariantMatches(BlockState actual,net.minecraft.world.level.block.Block expected){
        // Only the vanilla standing/wall attachment pair changes the requested block ID.
        // Inventory decrement and target occupancy are checked separately by the caller.
        return actual.is(expected)||(expected instanceof net.minecraft.world.level.block.TorchBlock&&actual.getBlock() instanceof net.minecraft.world.level.block.WallTorchBlock&&actual.getBlock().asItem()==expected.asItem());
    }
    private boolean buildBlockMatches(BlockPos position, ResourceLocation expected) {
        return placedVariantMatches(body.level().getBlockState(position),BuiltInRegistries.BLOCK.get(expected));
    }

    private boolean buildBlockSupported(ResourceLocation id, net.minecraft.world.level.block.Block block, BlockPos target) {
        if (!"minecraft".equals(id.getNamespace()) || block instanceof FallingBlock) return false;
        BlockState state = block.defaultBlockState();
        return block.asItem() instanceof BlockItem;
    }

    private void eat() {
        body.getNavigation().stop();
        if (body.foodLevel() >= 20) { complete("food restored"); return; }
        if (!body.isUsingItem()) {
            int slot = foodSlot();
            if (slot < 0) { finish(ActionState.PARTIAL, "food exhausted"); return; }
            body.selectSlot(slot);
            body.startUsingItem(InteractionHand.MAIN_HAND);
        }
    }

    private int orderRadius(ActionId id){return arbiter.snapshot(id).orElseThrow().payload().radius();}
    private final Map<ActionId,io.github.yufeiyufei888.hearthcrew.gameplay.ResourcePreparation> stationSearches=new HashMap<>();
    private void craft(BodyOrder order) {
        if(order.position()==null&&order.resource()!=null){
            var holder=body.level().getRecipeManager().byKey(order.resource()).orElse(null);
            if(holder!=null&&holder.value() instanceof net.minecraft.world.item.crafting.CraftingRecipe recipe&&!recipe.canCraftInDimensions(2,2)){
                var scan=stationSearches.computeIfAbsent(executing,id->{var p=new io.github.yufeiyufei888.hearthcrew.gameplay.ResourcePreparation(body,accessAccounts.get(id).origin,orderRadius(id),BodyOrder.Preparation.disabled());p.surveyNearby();return p;});
                scan.changed();scan.tickSurvey();var station=scan.knownWorkbench();
                if(station==null){if(!scan.scanned()){travelPhase="searching_workstation";body.haltInputs();return;}fail("CRAFT_STATION_UNAVAILABLE: no public workbench in authorized area; preparation disabled or no legal site");return;}
                order=new BodyOrder(order.kind(),station,null,order.count(),order.resource());
            }
        }
        if (order.resource() == null || order.count() < 1 || order.count() > 64) { fail("recipe and 1..64 repetitions required"); return; }
        if(order.position()!=null&&!io.github.yufeiyufei888.hearthcrew.gameplay.RecipeActions.canReachStation(body,order.position())){
            if(!body.level().getBlockState(order.position()).is(net.minecraft.world.level.block.Blocks.CRAFTING_TABLE)){fail("CRAFT_STATION_CHANGED: observed table no longer present");return;}
            final var stationTarget=order.position();
            var candidates=TargetInspection.stances(body,stationTarget).stream().filter(p->io.github.yufeiyufei888.hearthcrew.gameplay.RecipeActions.canReachStationFrom(body,stationTarget,TravelTerrain.feetPoint(body,p).add(0,body.getEyeHeight(),0))).toList();
            var path=body.getNavigation().pathToAny("craft-station",candidates);
            if(path==null){if(body.getNavigation().groupStatus("craft-station").equals("searching")){travelPhase="path_searching";return;}fail("CRAFT_STATION_INACCESSIBLE: no verified interaction stance");return;}
            moveTo(path.destination(),1,false);return;
        }
        body.getNavigation().stop();
        var result = io.github.yufeiyufei888.hearthcrew.gameplay.RecipeActions.craft(body, order.resource(), order.count(), order.position());
        var preparation=preparationWorks.get(executing);
        if(preparation!=null&&preparation.step!=null&&preparation.root.kind()==BodyOrder.Kind.CRAFT&&order.resource().equals(preparation.root.resource()))preparation.produced+=result.producedCount();
        var resource=resourceChildren.get(executing);
        if(resource!=null&&resource.order!=null&&resource.root!=null&&resource.root.resource().equals(result.outputId()))resource.manufactured+=result.producedCount();
        finish(result.completed() ? ActionState.COMPLETED : result.producedCount() > 0 ? ActionState.PARTIAL : ActionState.FAILED,
                result.reason() + "; crafted=" + result.completedRepetitions() + "; output=" + result.outputId() + "; actual produced=" + result.producedCount());
    }
    private void transfer(BodyOrder order) {
        Entity recipient = entity(order.target());
        if (recipient == null || !recipient.isAlive() || order.resource() == null || order.count() < 1) { fail("recipient or requested item unavailable"); return; }
        if (body.distanceToSqr(recipient) > 9 || !body.hasLineOfSight(recipient)) { moveTo(recipient.blockPosition(), 1.0, false); return; }
        body.getNavigation().stop();
        var result = io.github.yufeiyufei888.hearthcrew.gameplay.RecipeActions.transfer((ServerLevel)body.level(), body.getUUID(), recipient.getUUID(), order.resource(), order.count());
        finish(result.completed() ? ActionState.COMPLETED : result.movedCount() > 0 ? ActionState.PARTIAL : ActionState.FAILED,
                result.reason() + "; actual transferred=" + result.movedCount());
    }

    private void sleep(BlockPos position) {
        if (position == null) { fail("missing bed"); return; }
        BlockState state = body.level().getBlockState(position);
        if (!(state.getBlock() instanceof net.minecraft.world.level.block.BedBlock)
                || !net.minecraft.world.level.block.BedBlock.canSetSpawn(body.level())) { fail("bed unavailable in this dimension"); return; }
        BlockPos head = state.getValue(net.minecraft.world.level.block.BedBlock.PART) == net.minecraft.world.level.block.state.properties.BedPart.FOOT
                ? position.relative(state.getValue(net.minecraft.world.level.block.BedBlock.FACING)) : position;
        if (body.isSleeping()) {
            if (body.level().isDay()) { body.stopSleeping(); complete("rest ended at dawn"); }
            return;
        }
        if (body.distanceToSqr(Vec3.atCenterOf(head)) > 9) { approach(head); return; }
        body.setRespawnPoint(body.level().dimension(), head);
        if (body.level().isDay() && !body.level().isThundering()) { finish(ActionState.PARTIAL, "respawn point set; daytime prevents sleep"); return; }
        if (body.level().getBlockState(head).getValue(net.minecraft.world.level.block.BedBlock.OCCUPIED)) { fail("bed occupied"); return; }
        if (!body.level().getEntitiesOfClass(Mob.class, body.getBoundingBox().inflate(8, 5, 8), mob -> mob.getTarget() == body).isEmpty()) { fail("hostile threat prevents sleep"); return; }
        body.getNavigation().stop();
        var result=body.startSleepInBed(head);
        if(result.left().isPresent()) fail("native sleep refused: "+result.left().get());
    }

    private void attack(UUID uuid) { attack(uuid, true); }
    private void attack(UUID uuid, boolean chase) {
        Entity enemy = entity(uuid);
        if (enemy == null) { fail("target unavailable; defeat unconfirmed"); return; }
        if (!enemy.isAlive()) {
            if (enemy instanceof LivingEntity living && living.isDeadOrDying()) complete("target death observed");
            else fail("target removed; defeat unconfirmed");
            return;
        }
        if (body.isAlliedTo(enemy)) { fail("friendly target"); return; }
        Entity hitTarget = enemy;
        if (enemy instanceof net.minecraft.world.entity.boss.enderdragon.EnderDragon dragon) {
            hitTarget = Arrays.stream(dragon.getSubEntities()).min(Comparator.comparingDouble(part ->
                    closestPoint(part.getBoundingBox(), body.getEyePosition()).distanceToSqr(body.getEyePosition()))).orElse(dragon.head);
            if (meleeReachable(dragon.head)) hitTarget = dragon.head;
        }
        Vec3 aim = closestPoint(hitTarget.getBoundingBox(), body.getEyePosition());
        if(!resourceChildren.containsKey(executing) && body.getMainHandItem().getItem() instanceof net.minecraft.world.item.BowItem && !body.getProjectile(body.getMainHandItem()).isEmpty() && body.distanceToSqr(enemy)>9 && body.distanceToSqr(enemy)<24*24 && body.hasLineOfSight(enemy)) {
            body.getNavigation().stop();
            double distance=body.distanceTo(enemy);body.getLookControl().setLookAt(enemy.getX(),enemy.getY()+enemy.getBbHeight()*.55+distance*.06,enemy.getZ(),35,35);
            if(!body.isUsingItem())body.gameMode.useItem(body,body.level(),body.getMainHandItem(),InteractionHand.MAIN_HAND);
            else if(body.getTicksUsingItem()>=20)body.releaseUsingItem();
            return;
        }
        body.getLookControl().setLookAt(aim.x, aim.y, aim.z, 35, 35);
        if (!meleeReachable(hitTarget)) {
            if (chase) approachCombatTarget(hitTarget); return;
        }
        if (chase) body.getNavigation().stop();
        if (body.getAttackStrengthScale(0.5F) < .9F) {
            if(body.getOffhandItem().getItem() instanceof net.minecraft.world.item.ShieldItem&&!body.isUsingItem())body.gameMode.useItem(body,body.level(),body.getOffhandItem(),InteractionHand.OFF_HAND);
            return;
        }
        if(body.isUsingItem())body.stopUsingItem();
        body.swing(InteractionHand.MAIN_HAND);
        var child=resourceChildren.get(executing);boolean hunting=child!=null&&child.order!=null&&child.order.kind()==BodyOrder.Kind.ATTACK;
        Set<UUID> beforeDrops=new HashSet<>();if(hunting)body.level().getEntitiesOfClass(ItemEntity.class,enemy.getBoundingBox().inflate(2)).forEach(i->beforeDrops.add(i.getUUID()));
        body.attack(hitTarget); // Native cooldown, enchantments, durability and exhaustion.
        if(hunting&&!enemy.isAlive()){
            var drops=new LinkedHashMap<UUID,Integer>();for(var item:body.level().getEntitiesOfClass(ItemEntity.class,enemy.getBoundingBox().inflate(2)))if(!beforeDrops.contains(item.getUUID())){drops.put(item.getUUID(),item.getItem().getCount());child.huntedDrops.add(item.getUUID());}
            harvest.animalDrops(executing.value(),body.level().dimension().location().toString(),drops);
        }

    }

    private BlockPos combatStance;
    private int nextCombatPlan;
    private boolean attackFrom(BlockPos feet,Entity target){
        Vec3 eye=Vec3.atBottomCenterOf(feet).add(0,body.getEyeHeight(),0);
        Vec3 point=closestPoint(target.getBoundingBox(),eye);
        return eye.distanceToSqr(point)<=6.25&&body.level().clip(new ClipContext(eye,point,ClipContext.Block.COLLIDER,ClipContext.Fluid.NONE,body)).getType()==HitResult.Type.MISS;
    }
    private void approachCombatTarget(Entity target){
        if(combatStance!=null&&(!TravelTerrain.standable(body,combatStance)||!attackFrom(combatStance,target))){combatStance=null;body.getNavigation().stop();}
        if(combatStance==null&&(body.tickCount>=nextCombatPlan||!body.getNavigation().pendingPaths())){
            nextCombatPlan=body.tickCount+10;
            var candidates=new ArrayList<BlockPos>();
            for(int x=-3;x<=3;x++)for(int z=-3;z<=3;z++)for(int y=-2;y<=1;y++){
                var p=target.blockPosition().offset(x,y,z);
                if(p.distSqr(body.blockPosition())<=32*32&&TravelTerrain.standable(body,p)&&TravelTerrain.blockers(body,TravelTerrain.feetPoint(body,p)).isEmpty()&&attackFrom(p,target))candidates.add(p);
            }
            candidates.sort(Comparator.comparingDouble(p->p.distToCenterSqr(body.position())));
            for(var p:candidates.stream().limit(8).toList()){
                var path=body.getNavigation().createPath(p,0);
                if(path!=null&&path.canReach()){combatStance=p;travelProgress=null;break;}
            }
        }
        if(combatStance!=null){moveTo(combatStance,1.1,false);return;}
        body.getNavigation().stop();travelPhase="combat_path_searching";
        boolean reflex=arbiter.activeSnapshot().map(a->effectiveOrder(a.id(),a.payload()).kind()==BodyOrder.Kind.SELF_DEFENCE).orElse(false);
        if(reflex){safetyReason="敌人位置不可站立，寻找侧方攻击位置；受阻时撤离";if(!body.getNavigation().pendingPaths()&&target instanceof Mob mob){retreatUntil=body.tickCount+100;retreat(mob);}}
        else if(actionTicks>=200&&!body.getNavigation().pendingPaths())fail("COMBAT_APPROACH_BLOCKED: no safe reachable stance beside target; choose another strategy");
    }
    private boolean meleeReachable(Entity target) {
        Vec3 eye = body.getEyePosition();
        Vec3 contact = closestPoint(target.getBoundingBox(), eye);
        if (eye.distanceToSqr(contact) > 9) return false;
        return body.level().clip(new ClipContext(eye, contact, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, body)).getType() == HitResult.Type.MISS;
    }
    private static Vec3 closestPoint(AABB box, Vec3 point) {
        return new Vec3(Math.clamp(point.x, box.minX, box.maxX), Math.clamp(point.y, box.minY, box.maxY), Math.clamp(point.z, box.minZ, box.maxZ));
    }

    private void pickup(UUID uuid) {
        String key=pickupReceiptKey(executing);
        var received=pickupReceipts.get(key);
        if(received!=null&&uuid.equals(received.target())){complete("native pickup event verified; count="+received.count()+"; collector="+body.getUUID());return;}
        Entity target = entity(uuid);
        if (!(target instanceof ItemEntity item)) { fail("drop unavailable; acquisition not assumed"); return; }
        // Teammates may collect naturally; ownership is accounted by the pickup event.
        if (!body.inventory().canAddItem(item.getItem())) { finish(ActionState.PARTIAL, "inventory full"); return; }
        moveToDrop(item);
    }

    private void enterPortal(BlockPos position) {
        if (portalArrived) {
            if (!touchesPortal() && body.onGround()) { complete("native dimension transfer and physical portal exit verified"); return; }
            leavePortal(); return;
        }
        if (position == null || !(body.level().getBlockState(position).getBlock() instanceof net.minecraft.world.level.block.Portal)) {
            fail("active portal block unavailable"); return;
        }
        if (body.isOnPortalCooldown()) {
            if (touchesPortal()) leavePortal(); else body.getNavigation().stop();
            return;
        }
        Vec3 entrance = Vec3.atBottomCenterOf(position);
        if (body.position().distanceToSqr(entrance) > 9) { moveTo(position, 1.0, false); return; }
        // Physical contact invokes vanilla PortalProcessor. This never calls changeDimension itself.
        body.getNavigation().stop();
        if (body.position().distanceToSqr(entrance) > 0.04)
            body.getMoveControl().setWantedPosition(entrance.x, entrance.y, entrance.z, 1.0);
        if (actionTicks > 600) fail("portal did not transfer body within game-time limit");
    }

    private boolean touchesPortal() {
        AABB bounds = body.getBoundingBox().deflate(0.001);
        return BlockPos.betweenClosedStream(BlockPos.containing(bounds.minX, bounds.minY, bounds.minZ), BlockPos.containing(bounds.maxX, bounds.maxY, bounds.maxZ))
                .anyMatch(position -> body.level().getBlockState(position).getBlock() instanceof net.minecraft.world.level.block.Portal);
    }
    private void leavePortal() {
        if (portalExit == null) {
            for (int distance = 2; distance <= 3 && portalExit == null; distance++) for (Direction direction : Direction.Plane.HORIZONTAL) {
                for (int dy : new int[] {0, -1, 1}) {
                    BlockPos candidate = body.blockPosition().relative(direction, distance).offset(0, dy, 0);
                    var feet = body.level().getBlockState(candidate);
                    if (!(feet.getBlock() instanceof net.minecraft.world.level.block.Portal)
                            && feet.getCollisionShape(body.level(), candidate).isEmpty() && feet.getFluidState().isEmpty()
                            && body.level().getBlockState(candidate.above()).isAir()
                            && body.level().getBlockState(candidate.below()).isCollisionShapeFullBlock(body.level(), candidate.below())) {
                        portalExit = candidate; break;
                    }
                }
                if (portalExit != null) break;
            }
        }
        if (portalExit == null) { finish(ActionState.PARTIAL, "dimension reached; no safe physical portal exit found"); return; }
        movePrecisely(Vec3.atBottomCenterOf(portalExit), 1.0);
        if (++stalledTicks > 100) finish(ActionState.PARTIAL, "dimension reached; physical portal exit blocked");
    }

    /** Block arrival tolerance is unsuitable for drops: only inventory changes prove pickup. */
    private UUID pickupTarget;private BlockPos pickupAnchor,pickupStand;private int pickupReplans;
    private void moveToDrop(ItemEntity item) {
        if(!body.level().hasChunkAt(item.blockPosition())){finish(ActionState.PARTIAL,"DROP_UNOBSERVED: unloaded; no body lock");return;}
        if(!body.serverLevel().isPositionEntityTicking(item.blockPosition())){WorldEvents.requestExplorationChunks(body,item.blockPosition());travelPhase="awaiting_chunk_tick";return;}
        var nav=body.getNavigation();String key="pickup";
        if(!item.getUUID().equals(pickupTarget)||pickupAnchor==null||pickupAnchor.distSqr(item.blockPosition())>2){pickupTarget=item.getUUID();pickupAnchor=item.blockPosition();pickupStand=null;pickupReplans=0;nav.invalidateGroup(key);lastProgress=null;stalledTicks=0;}
        var candidates=new ArrayList<BlockPos>();
        for(var mutable:BlockPos.betweenClosed(item.blockPosition().offset(-2,-3,-2),item.blockPosition().offset(2,1,2))){
            var pos=mutable.immutable();if(!TravelTerrain.standable(body,pos))continue;
            var box=body.getBoundingBox().move(TravelTerrain.feetPoint(body,pos).subtract(body.position())).inflate(.6,.15,.6);
            if(box.intersects(item.getBoundingBox()))candidates.add(pos);
        }
        if(pickupStand!=null&&(!candidates.contains(pickupStand))) {pickupStand=null;nav.invalidateGroup(key);}
        if(pickupStand==null){
            var path=nav.pathToAny(key,candidates);
            if(path!=null){pickupStand=path.destination();lastProgress=null;stalledTicks=0;}
            else if(nav.groupStatus(key).equals("searching")){travelPhase="pickup_path_searching";return;}
            else {
                if(item.hasPickUpDelay()&&actionTicks-miningBreakTick<40){travelPhase="pickup_delayed";return;}
                if(accessTo(item.blockPosition(),true)){nav.invalidateGroup(key);accessWorks.remove(executing);}
                return;
            }
        }
        var point=TravelTerrain.feetPoint(body,pickupStand);travelPhase="recovering_drops";
        if(!TravelTerrain.landedAt(body,pickupStand,.2))nav.moveTo(point.x,point.y,point.z,1);else nav.stop();
        if(lastProgress==null||body.position().distanceToSqr(point)<lastProgress.distanceToSqr(point)-.04){lastProgress=body.position();stalledTicks=0;}
        else if(++stalledTicks>=100){
            if(pickupReplans++==0){nav.invalidateGroup(key);nav.stop();pickupStand=null;lastProgress=null;stalledTicks=0;}
            else finish(ActionState.PARTIAL,"PICKUP_BLOCKED: no approach/pickup progress after one replan; inspect delay, capacity, owner and blockers; drop="+item.getUUID()+"; dropPos="+item.position()+"; feet="+body.position()+"; stance="+point+"; contact="+body.getBoundingBox().inflate(1,.5,1).intersects(item.getBoundingBox())+"; delay="+item.hasPickUpDelay());
        }
    }

    private int placementWaitTicks;
    private boolean waitForPlacementClearance(BlockPos target) {
        if(++placementWaitTicks>100)return false;
        var stance=TargetInspection.stances(body,target).stream().filter(p->!new AABB(target).intersects(body.getBoundingBox().move(TravelTerrain.feetPoint(body,p).subtract(body.position())))).filter(p->{var path=body.getNavigation().createPath(p,0);return path!=null&&path.canReach();}).findFirst();
        if(stance.isPresent())moveTo(stance.get(),1,false);
        travelPhase="waiting_placement_clearance";return true;
    }
    private void movePrecisely(Vec3 destination, double speed) {
        if (body.position().distanceToSqr(destination) <= 4
                && TravelTerrain.transition(body,TravelTerrain.supportedStart(body),BlockPos.containing(destination.x,Math.ceil(destination.y-1e-4),destination.z))
                && body.level().clip(new ClipContext(body.getEyePosition(), destination.add(0, 0.1, 0), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, body)).getType() == HitResult.Type.MISS) {
            body.getNavigation().stop();
            body.getMoveControl().setWantedPosition(destination.x, destination.y, destination.z, speed);
        } else if (actionTicks % 10 == 1 || body.getNavigation().isDone()) {
            body.getNavigation().moveTo(destination.x, destination.y, destination.z, speed);
        }
    }

    private void collectTouchingDrops() {
        for(ItemEntity item:body.level().getEntitiesOfClass(ItemEntity.class,body.getBoundingBox().inflate(1,.5,1)))
            if(canPickup(item.getUUID()))item.playerTouch(body);
    }
    public String pickupDiagnostics(){return "owed="+miningDrops+" drops="+body.level().getEntitiesOfClass(ItemEntity.class,body.getBoundingBox().inflate(8)).stream().map(i->i.getUUID()+" "+i.getItem()+" "+i.position()+" age="+i.tickCount+" ticking="+body.serverLevel().isPositionEntityTicking(i.blockPosition())+" delay="+i.hasPickUpDelay()+" allowed="+canPickup(i.getUUID())).toList()+" inventory="+body.inventory().createTag(body.registryAccess());}
    private final Map<UUID,ResourceLocation> pickupEvidence=new HashMap<>();
    private record PickupReceipt(UUID target,int count) {}
    private final Map<String,PickupReceipt> pickupReceipts=new HashMap<>();
    private String pickupReceiptKey(ActionId id){var sequence=sequences.get(id);var child=resourceChildren.get(id);return id.value()+"#"+(sequence==null?0:sequence.index)+"#"+(child==null?0:child.receipts.size());}
    public boolean canPickup(UUID id){return body.isAlive();}
    public void nativePickup(UUID id,int count,ResourceLocation item){
        if(count<=0)return;int remaining=count-harvest.acquired(id,count,item.toString(),true,body.getUUID());for(var other:CrewWorldData.liveCompanions(body.server))if(remaining>0&&other!=body&&other.level()==body.level())remaining-=other.executor().observedOtherPickup(id,remaining,item,body.getUUID());pickupEvidence.put(id,item);
        try{recordDropAcquisition(id,count);}finally{pickupEvidence.remove(id);}
        var current=arbiter.activeSnapshot();
        if(current.isPresent()){
            var snapshot=current.get();var order=effectiveOrder(snapshot.id(),snapshot.payload());
            if(order.kind()==BodyOrder.Kind.SEQUENCE)order=order.actions().getFirst();
            if(order.kind()==BodyOrder.Kind.PICKUP&&id.equals(order.target())){
                var key=pickupReceiptKey(snapshot.id());var prior=pickupReceipts.get(key);
                pickupReceipts.put(key,new PickupReceipt(id,count+(prior==null?0:prior.count())));
                recordExecution(snapshot.id().value(),Map.of("pickupEntityId",id.toString(),"pickupCollector",body.getUUID().toString(),"pickupItem",item.toString(),"pickupCount",count));
                // The next executor tick settles the step. Post may arrive before
                // SEQUENCE has started; never finish its whole root from inside vanilla pickup.
            }
        }
    }

    /** Respect another executor's observed mining obligation; never change vanilla player pickup rules. */
    private boolean claimedMiningDropByOther(UUID dropId) {
        for (CompanionEntity other : CrewWorldData.liveCompanions(body.getServer())) {
            if (other != body && other.level() == body.level() && other.executor().ownsMiningDrop(dropId)) return true;
        }
        return false;
    }
    public boolean ownsMiningDrop(UUID dropId) {
        if (stopped || !body.isAlive()) return false;
        if (executing != null && miningDrops.containsKey(dropId)
                && arbiter.snapshot(executing).map(action -> !action.state().terminal()
                && (effectiveOrder(action.id(),action.payload()).kind() == BodyOrder.Kind.MINE || effectiveOrder(action.id(),action.payload()).kind() == BodyOrder.Kind.COLLECT_RESOURCE || effectiveOrder(action.id(),action.payload()).kind() == BodyOrder.Kind.GATHER)).orElse(false)) return true;
        for (var entry : suspendedMiningDrops.entrySet()) {
            if (entry.getValue().containsKey(dropId) && arbiter.snapshot(entry.getKey()).map(action -> !action.state().terminal()).orElse(false)) return true;
        }
        return false;
    }

    public int nativeDropMerged(UUID source,UUID destination,int moved){
        int accounted=harvest.merged(source,destination,moved);
        int left=accounted-transferDropClaim(miningDrops,source,destination,accounted);
        for(var claims:suspendedMiningDrops.values())left-=transferDropClaim(claims,source,destination,left);
        return accounted;
    }
    private static int transferDropClaim(Map<UUID,Integer> claims,UUID source,UUID destination,int moved){
        int owed=claims.getOrDefault(source,0),shift=Math.min(owed,moved);if(shift<=0)return 0;
        if(shift==owed)claims.remove(source);else claims.put(source,owed-shift);claims.merge(destination,shift,Integer::sum);
        return shift;
    }
    private void recordDropAcquisition(UUID dropId, int acquired) {
        var current = arbiter.activeSnapshot();
        if (current.isPresent() && effectiveOrder(current.get().id(),current.get().payload()).kind() == BodyOrder.Kind.GATHER
                && current.get().id().equals(executing)
                && miningDrops.containsKey(dropId)
                && gatherResourceDrop(dropId, effectiveOrder(current.get().id(),current.get().payload()).resource())) {
            gatherCollected = Math.min(effectiveOrder(current.get().id(),current.get().payload()).count(), gatherCollected + Math.min(acquired,miningDrops.get(dropId)));
        }
        decrementDropOwed(miningDrops, dropId, acquired);
        // This can run after a higher-priority action has already suspended a
        // mining action. Keep that action's real remaining obligation current
        // even though the live mining scratch map is cleared on action switch.
        for (Map.Entry<ActionId, Integer> entry : suspendedGatherCollected.entrySet()) {
            var snapshot = arbiter.snapshot(entry.getKey());
            if (suspendedMiningDrops.getOrDefault(entry.getKey(), Map.of()).containsKey(dropId)
                    && snapshot.isPresent() && snapshot.get().payload().kind() == BodyOrder.Kind.GATHER
                    && gatherResourceDrop(dropId, snapshot.get().payload().resource())) {
                entry.setValue(Math.min(snapshot.get().payload().count(), entry.getValue() + Math.min(acquired,suspendedMiningDrops.get(entry.getKey()).get(dropId))));
            }
        }
        for (Map<UUID, Integer> owed : suspendedMiningDrops.values()) {
            decrementDropOwed(owed, dropId, acquired);
        }
    }

    private boolean gatherResourceDrop(UUID dropId, ResourceLocation resource) {
        if(resource.equals(pickupEvidence.get(dropId)))return true;
        Entity entity = entity(dropId);
        return entity instanceof ItemEntity item
                && resource.equals(BuiltInRegistries.ITEM.getKey(item.getItem().getItem()));
    }

    private static void decrementDropOwed(Map<UUID, Integer> owed, UUID dropId, int acquired) {
        Integer count = owed.get(dropId);
        if (count == null) return;
        int remaining = count - acquired;
        if (remaining <= 0) owed.remove(dropId); else owed.put(dropId, remaining);
    }

    private void approachPlacement(BlockPos target){
        for(var stance:TargetInspection.stances(body,target).stream().filter(p->io.github.yufeiyufei888.hearthcrew.gameplay.PlayerInteractions.placementFaceFrom(body,target,TravelTerrain.feetPoint(body,p).add(0,body.getEyeHeight(),0))!=null).limit(12).toList()){
            if(!TravelTerrain.blockers(body,TravelTerrain.feetPoint(body,stance)).isEmpty())continue;
            var path=body.getNavigation().createPath(stance,0);if(path!=null){moveTo(stance,1.0,false);return;}
        }
        if(body.getNavigation().pendingPaths()){travelPhase="placement_path_searching";return;}
        fail("NO_PLACEMENT_STANCE: support face missing/occluded or no reachable body space");
    }
    private void approach(BlockPos target) {
        var stance=TargetInspection.accessibleStance(body,target);
        if(stance==null){if(body.getNavigation().pendingPaths()){travelPhase="path_searching";return;}fail("no accessible interaction stance; inspect target or plan EXCAVATE, do not repeat MOVE into solid blocks");return;}
        moveTo(stance,1.0,false);
    }

    private boolean reachable(BlockPos block) {
        return TargetInspection.sight(body,body.getEyePosition(),block);
    }

    public boolean ownsInteractionContext(Entity entity) { return entity == body; }
    private net.minecraft.server.level.ServerPlayer beginContext() { return body; }
    private void endContext(net.minecraft.server.level.ServerPlayer context) { }
    private int foodSlot() {
        for (int i = 0; i < body.inventory().getContainerSize(); i++)
            if (body.inventory().getItem(i).getFoodProperties(body) != null) return i;
        return -1;
    }
    private boolean hasFood() { return foodSlot() >= 0; }
    private Entity entity(UUID uuid) { return uuid == null ? null : ((ServerLevel) body.level()).getEntity(uuid); }
    private Checkpoint makeCheckpoint(ActionSnapshot<BodyOrder, Checkpoint> previous) {
        var previousOrder=effectiveOrder(previous.id(),previous.payload());
        boolean mining = (previousOrder.kind() == BodyOrder.Kind.MINE || previousOrder.kind() == BodyOrder.Kind.COLLECT_RESOURCE)
                && previous.id().equals(executing);
        boolean gathering = previousOrder.kind() == BodyOrder.Kind.GATHER;
        boolean building = previousOrder.kind() == BodyOrder.Kind.BUILD;
        Vec3 checkpointBuildOrigin = building ? (buildOrigin == null ? body.position() : buildOrigin) : null;
        Vec3 checkpointGatherOrigin = gathering ? (gatherOrigin == null ? body.position() : gatherOrigin) : null;
        return new Checkpoint(body.level().dimension().location().toString(), body.position(), body.selectedSlot(),
                body.getMainHandItem().copy(),
                (mining || gathering) ? (gathering ? gatherTarget : miningTarget) : null,
                (mining || gathering) ? miningState : null,
                (mining || gathering) ? miningProgress : 0.0F,
                (mining || gathering) && miningBroken,
                (mining || gathering) ? miningBreakTick : 0,
                previous.id().equals(executing) ? actionTicks : 0,
                (mining || gathering) ? miningDrops : Map.of(), checkpointBuildOrigin,
                building ? List.copyOf(buildSteps.values()) : List.of(), checkpointGatherOrigin,
                gathering ? gatherTarget : null, gathering ? gatherResource : null,
                gathering ? gatherCollected : 0,
                gathering ? List.copyOf(gatherCandidates) : List.of(),
                gathering ? gatherCandidateIndex : 0,
                gathering && gatherRequestedDropObserved);
    }
    private void resumeSuspended() {
        while (!suspended.isEmpty() && arbiter.activeActionId().isEmpty()) {
            ActionId id = suspended.pop();
            var suspendedSnapshot = arbiter.snapshot(id);
            if(waterInterrupted.remove(id)) {
                var evidence=harvest.report(id.value());
                boolean changed=evidence.get("broken") instanceof Number n && n.intValue()>0;
                arbiter.finish(id,changed?ActionState.PARTIAL:ActionState.CANCELLED,"WATER_INTERRUPTED: inspect actual position and recorded effects, choose dry route, explicit SWIM or boat; no automatic return to underwater target");
                suspendedMiningDrops.remove(id);suspendedGatherCollected.remove(id);continue;
            }
            if (suspendedSnapshot.isPresent() && effectiveOrder(suspendedSnapshot.get().id(),suspendedSnapshot.get().payload()).kind() == BodyOrder.Kind.BUILD
                    && (suspendedSnapshot.get().checkpoint() == null
                    || !checkpointWorldStillMatches(suspendedSnapshot.get().checkpoint()))) {
                String message = suspendedSnapshot.get().checkpoint() == null
                        ? "BUILD checkpoint is missing"
                        : "confirmed BUILD step no longer matches the world";
                arbiter.finish(id, ActionState.RECONCILE_REQUIRED,
                        buildResultMessage(message, suspendedSnapshot.get().checkpoint() == null
                                ? List.of() : suspendedSnapshot.get().checkpoint().buildSteps()));
                continue;
            }
            if (suspendedSnapshot.isPresent() && effectiveOrder(suspendedSnapshot.get().id(),suspendedSnapshot.get().payload()).kind() == BodyOrder.Kind.GATHER
                    && (suspendedSnapshot.get().checkpoint() == null
                    || !checkpointWorldStillMatches(suspendedSnapshot.get().checkpoint()))) {
                String message = suspendedSnapshot.get().checkpoint() == null
                        ? "GATHER checkpoint is missing"
                        : "gather target or confirmed world state changed before resume";
                arbiter.finish(id, ActionState.RECONCILE_REQUIRED, message);
                suspendedMiningDrops.remove(id); suspendedGatherCollected.remove(id);
                continue;
            }
            var receipt = arbiter.resume(id, checkpoint -> checkpoint != null
                    && checkpoint.dimension().equals(body.level().dimension().location().toString())
                    && body.isAlive()
                    && checkpointWorldStillMatches(checkpoint));
            if (receipt.decision() == ReceiptDecision.RESUMED) {
                arbiter.snapshot(id).ifPresent(snapshot -> {
                    restoreCheckpoint(snapshot.checkpoint(), snapshot.payload(), id);
                    Map<UUID, Integer> owed = suspendedMiningDrops.remove(id);
                    if (owed != null && (effectiveOrder(snapshot.id(),snapshot.payload()).kind() == BodyOrder.Kind.MINE
                            || effectiveOrder(snapshot.id(),snapshot.payload()).kind() == BodyOrder.Kind.GATHER)) {
                        miningDrops.clear();
                        miningDrops.putAll(owed);
                    }
                    if (effectiveOrder(snapshot.id(),snapshot.payload()).kind() == BodyOrder.Kind.GATHER) {
                        gatherCollected = suspendedGatherCollected.getOrDefault(id, snapshot.checkpoint().gatherCollected());
                    }
                    suspendedGatherCollected.remove(id);
                    executing = id;
                });
            } else {
                suspendedMiningDrops.remove(id);
                suspendedGatherCollected.remove(id);
            }
        }
    }
    private boolean checkpointWorldStillMatches(Checkpoint checkpoint) {
        if (checkpoint.miningTarget() != null) {
            BlockState current = body.level().getBlockState(checkpoint.miningTarget());
            if (!(checkpoint.miningBroken() ? current.isAir()
                    : checkpoint.miningState() == null || current.equals(checkpoint.miningState()))) return false;
        }
        for (BuildObservation step : checkpoint.buildSteps()) {
            if (!body.level().hasChunkAt(step.position()) || !buildBlockMatches(step.position(), step.block())) return false;
        }
        if (!gatherCheckpointWorldMatches(checkpoint)) return false;
        return true;
    }

    private boolean gatherCheckpointWorldMatches(Checkpoint checkpoint) {
        if (checkpoint.gatherResource() == null) return checkpoint.gatherCandidates().isEmpty();
        for (int index = 0; index < checkpoint.gatherCandidates().size(); index++) {
            BlockPos candidate = checkpoint.gatherCandidates().get(index);
            if (!body.level().hasChunkAt(candidate)) return false;
            BlockState state = body.level().getBlockState(candidate);
            if (index < checkpoint.gatherCandidateIndex()) {
                if (!state.isAir()) return false;
            } else if (checkpoint.miningBroken() && candidate.equals(checkpoint.gatherTarget())) {
                if (!state.isAir()) return false;
            } else if (!checkpoint.gatherResource().equals(BuiltInRegistries.BLOCK.getKey(state.getBlock()))) {
                return false;
            }
        }
        return checkpoint.gatherTarget() == null
                || checkpoint.gatherCandidates().contains(checkpoint.gatherTarget());
    }
    private void restoreCheckpoint(Checkpoint checkpoint, BodyOrder order, ActionId resumedId) {
        order=effectiveOrder(resumedId,order);
        buildPreflightDone.remove(resumedId);
        var dig=digWorks.get(resumedId);if(dig!=null){dig.search=null;dig.route=null;dig.index=0;}
        travelProgress=null;travelDestination=null;travelStart=null;
        restoreCarriedItem(checkpoint);
        actionTicks = checkpoint.actionTicks();
        buildOrigin = order.kind() == BodyOrder.Kind.BUILD ? checkpoint.buildOrigin() : null;
        buildSteps.clear();
        if (order.kind() == BodyOrder.Kind.BUILD) {
            for (BuildObservation step : checkpoint.buildSteps()) buildSteps.put(step.position(), step);
        }
        gatherOrigin = order.kind() == BodyOrder.Kind.GATHER ? checkpoint.gatherOrigin() : null;
        gatherTarget = order.kind() == BodyOrder.Kind.GATHER ? checkpoint.gatherTarget() : null;
        gatherResource = order.kind() == BodyOrder.Kind.GATHER ? checkpoint.gatherResource() : null;
        gatherCollected = order.kind() == BodyOrder.Kind.GATHER ? checkpoint.gatherCollected() : 0;
        gatherRequestedDropObserved = order.kind() == BodyOrder.Kind.GATHER && checkpoint.gatherRequestedDropObserved();
        gatherDimension = order.kind() == BodyOrder.Kind.GATHER ? checkpoint.dimension() : null;
        gatherCandidates.clear();
        if (order.kind() == BodyOrder.Kind.GATHER) gatherCandidates.addAll(checkpoint.gatherCandidates());
        gatherCandidateIndex = order.kind() == BodyOrder.Kind.GATHER ? checkpoint.gatherCandidateIndex() : 0;
        gatherPlanFailure = null;
        gatherScanComplete = order.kind() == BodyOrder.Kind.GATHER && !gatherCandidates.isEmpty();
        gatherScanCursor = gatherScanTotal = 0;
        gatherPlanRoot = gatherCandidates.isEmpty() ? null : gatherCandidates.getFirst();
        if (order.kind() == BodyOrder.Kind.GATHER && gatherCandidates.isEmpty()) prepareGatherScan();
        if ((order.kind() != BodyOrder.Kind.MINE && order.kind() != BodyOrder.Kind.COLLECT_RESOURCE && order.kind() != BodyOrder.Kind.GATHER) || checkpoint.miningTarget() == null) {
            miningState = null;
            miningProgress = 0.0F;
            miningBroken = false;
            miningBreakTick = 0;
            miningDrops.clear();
            lastProgress = body.position();
            stalledTicks = 0;
            return;
        }
        miningState = checkpoint.miningState();
        miningProgress = checkpoint.miningProgress();
        miningBroken = checkpoint.miningBroken();
        miningBreakTick = checkpoint.miningBreakTick();
        miningDrops.clear();
        miningDrops.putAll(checkpoint.miningDrops());
        stalledTicks = 0;
        lastProgress = body.position();
    }
    private void restoreCarriedItem(Checkpoint checkpoint) {
        int selected = Math.clamp(checkpoint.selectedSlot(), 0, 8);
        ItemStack expected = checkpoint.carriedItem();
        ItemStack current = body.inventory().getItem(selected);
        if (!ItemStack.isSameItemSameComponents(current, expected)) {
            for (int slot = 0; slot < body.inventory().getContainerSize(); slot++) {
                if (slot != selected && ItemStack.isSameItemSameComponents(body.inventory().getItem(slot), expected)) {
                    body.inventory().setItem(selected, body.inventory().getItem(slot));
                    body.inventory().setItem(slot, current);
                    break;
                }
            }
        }
        // Food actions may have changed the selected hotbar index while the
        // old action was suspended; restore the original carried slot too.
        body.selectSlot(selected);
    }
    private void complete(String message) { finish(ActionState.COMPLETED, message); }
    private void fail(String message) {
        if (miningBroken && arbiter.activeSnapshot().map(action -> effectiveOrder(action.id(),action.payload()).kind() == BodyOrder.Kind.GATHER).orElse(false)) {
            reconcileGather(message); return;
        }
        boolean partialWork = arbiter.activeSnapshot()
                .map(action -> (effectiveOrder(action.id(),action.payload()).kind() == BodyOrder.Kind.BUILD
                        && buildSteps.values().stream().anyMatch(BuildObservation::placed))
                        || (effectiveOrder(action.id(),action.payload()).kind() == BodyOrder.Kind.MINE && !harvest.report(action.id().value()).isEmpty())
                        || (effectiveOrder(action.id(),action.payload()).kind() == BodyOrder.Kind.GATHER && gatherCollected > 0)
                        || (effectiveOrder(action.id(),action.payload()).kind() == BodyOrder.Kind.EXCAVATE && digWorks.containsKey(action.id()) && !digWorks.get(action.id()).broken.isEmpty()))
                .orElse(false);
        finish(partialWork ? ActionState.PARTIAL : ActionState.FAILED, message);
    }
    private void finish(ActionState state, String message) {
        var currentAction=arbiter.activeSnapshot();
        var prepared=preparationWorks.get(executing);
        if(prepared!=null&&prepared.step!=null){
            var account=preparationAccounts.computeIfAbsent(executing,id->new PreparationAccount());account.steps++;
            account.receipts.add(Map.of("step",account.steps,"kind",prepared.step.kind().name(),"state",state.name(),"message",message==null?"":message));
            prepared.step=null;resetResourceStep();if(prepared.batch!=null)mineBatches.put(executing,prepared.batch);if(prepared.access!=null)accessWorks.put(executing,prepared.access);
            if(prepared.checkpoint!=null)restoreCheckpoint(prepared.checkpoint,prepared.root,executing);
            prepared.planner.changed();travelPhase="resuming_task";
            if(state==ActionState.COMPLETED)return;
            message="PREPARATION_BLOCKED: "+message;
        }
        var child=resourceChildren.get(executing);
        if(!finalizingResource && child!=null&&child.order!=null){
            var prepAccount=preparationAccounts.computeIfAbsent(executing,id->new PreparationAccount());if(child.preparation){prepAccount.steps++;prepAccount.receipts.add(Map.of("kind",child.order.kind().name(),"state",state.name(),"message",message==null?"":message));}
            child.receipts.add(Map.of("step",child.completed,"kind",child.order.kind().name(),"state",state.name(),"reason",message==null?"":message));
            recordExecution(executing.value(),Map.of("phase","preparing_resource","checkpoints",List.copyOf(child.receipts),"preparationSteps",child.completed,"preparationBroken",child.broken));
            if(state==ActionState.COMPLETED){child.completed++;child.order=null;child.planner.changed();resetResourceStep();return;}
            if((state==ActionState.FAILED||state==ActionState.PARTIAL)&&child.order.kind()==BodyOrder.Kind.MINE&&child.receipts.size()<child.root.preparation().maxSteps()&&!message.contains("capacity")&&!message.contains("inventory full")){
                child.planner.reject(child.order.position());child.order=null;resetResourceStep();return;
            }
            child.order=null;resourceTerminal(state,"RESOURCE_CHILD: "+message);return;
        }
        if(!finalizingResource&&child!=null&&collections.containsKey(executing)&&(state==ActionState.FAILED||state==ActionState.PARTIAL)&&message!=null
            &&!message.contains("inventory full")&&!message.contains("MISSING_HARVEST_TOOL")&&!message.contains("capacity")){
            var collection=collections.get(executing);
            if(collection.current!=null){rejectedResourceTargets.computeIfAbsent(executing,k->new LinkedHashMap<>()).put(collection.current,message);collection.current=null;resetResourceStep();return;}
        }
        if(!finalizingResource&&currentAction.isPresent()&&effectiveOrder(currentAction.get().id(),currentAction.get().payload()).kind()==BodyOrder.Kind.COLLECT_RESOURCE
                &&state==ActionState.PARTIAL&&miningBroken&&message!=null&&!message.contains("inventory full")){
            var work=collections.get(currentAction.get().id());
            if(work!=null){work.current=null;miningBroken=false;miningDrops.clear();miningState=null;miningProgress=0;travelProgress=null;stalledTicks=0;stopMotion();return;}
        }
        if(currentAction.isPresent()&&currentAction.get().payload().kind()==BodyOrder.Kind.SEQUENCE){
            var work=sequences.get(currentAction.get().id());
            if(work!=null&&work.index<currentAction.get().payload().actions().size()){
                work.receipts.add(Map.of("step",work.index,"kind",currentAction.get().payload().actions().get(work.index).kind().name(),"state",state.name(),"message",message==null?"":message));
                recordExecution(executing.value(),Map.of("sequenceIndex",work.index,"steps",List.copyOf(work.receipts)));
                if(state==ActionState.COMPLETED){
                    work.index++;stopMotion();actionTicks=0;miningState=null;miningTarget=null;miningProgress=0;miningBroken=false;miningDrops.clear();travelProgress=null;
                    mineBatches.remove(executing);collections.remove(executing);resourceChildren.remove(executing);rejectedResourceTargets.remove(executing);digWorks.remove(executing);accessWorks.remove(executing);buildPreflightDone.remove(executing);buildSteps.clear();buildOrigin=null;gatherOrigin=null;gatherTarget=null;gatherCandidates.clear();gatherCollected=0;gatherResource=null;gatherDimension=null;gatherScanComplete=false;gatherScanCursor=gatherScanTotal=0;
                    preparationWorks.remove(executing);if(work.index<currentAction.get().payload().actions().size())return;
                    message="SEQUENCE completed "+work.index+" confirmed steps";
                }else if(work.index>0&&state!=ActionState.RECONCILE_REQUIRED)state=ActionState.PARTIAL;
            }
        }
        final ActionState finalState=state;final String resultMessage=message;
        arbiter.activeActionId().ifPresent(id -> {
            String finalMessage = resultMessage;
            var current = arbiter.snapshot(id);
            if (current.isPresent() && effectiveOrder(current.get().id(),current.get().payload()).kind() == BodyOrder.Kind.BUILD) {
                finalMessage = buildResultMessage(resultMessage, buildSteps.values());
            } else if (current.isPresent() && effectiveOrder(current.get().id(),current.get().payload()).kind() == BodyOrder.Kind.GATHER) {
                var report = new LinkedHashMap<String, Object>(currentGatherDiagnostics(effectiveOrder(current.get().id(),current.get().payload()).count()));
                report.put("phase", "finished"); report.put("state", finalState.name());
                gatherReports.put(id.value(), Map.copyOf(report));
                if (gatherReports.size() > 128) gatherReports.remove(gatherReports.keySet().iterator().next());
                finalMessage = (resultMessage == null ? "gather finished" : resultMessage) + "; collected=" + gatherCollected
                        + "; requested=" + effectiveOrder(current.get().id(),current.get().payload()).count();
            }
            if(current.isPresent()&&effectiveOrder(current.get().id(),current.get().payload()).kind()==BodyOrder.Kind.EXCAVATE){var dig=digWorks.get(id);if(dig!=null){finalMessage += "; broken="+dig.broken.size();recordExecution(id.value(),Map.of("phase","excavation_finished","broken",dig.broken.size(),"origin",TravelTerrain.position(dig.origin)));}}
            else if(travelProgress!=null)recordExecution(id.value(),travelStatus());
            arbiter.finish(id, finalState, finalMessage);
            suspendedMiningDrops.remove(id);
            suspendedGatherCollected.remove(id);
            digWorks.remove(id);buildPreflightDone.remove(id);
        });
        if (portalArrived) {
            portalArrived = false; portalExit = null;
            var epoch = arbiter.epoch();
            arbiter.advanceEpoch(new WorldEpoch(epoch.worldGeneration(), epoch.sessionGeneration(), body.bodyGeneration()),
                    "dimension changed; remaining tasks need new observations");
            suspended.clear();
            suspendedMiningDrops.clear();
            suspendedGatherCollected.clear();
        }
        releaseSpace();stopMotion();
    }
    private static String buildResultMessage(String reason, Collection<BuildObservation> observations) {
        long placed = observations.stream().filter(BuildObservation::placed).count();
        long existing = observations.stream().filter(BuildObservation::existing).count();
        return (reason == null ? "build finished" : reason) + "; placed=" + placed + "; existing=" + existing;
    }
    private void stopMotion() {
        if(stopped||!body.isAlive())releaseSpace();
        body.haltInputs();
        body.setSprinting(false);
        body.getNavigation().stop();
        body.stopUsingItem();
        if (body.isSleeping()) body.stopSleeping();
        if (miningState != null && executing != null) {
            arbiter.snapshot(executing).ifPresent(snapshot -> {
                if (effectiveOrder(snapshot.id(),snapshot.payload()).position() != null)
                    ((ServerLevel)body.level()).destroyBlockProgress(body.getId(), effectiveOrder(snapshot.id(),snapshot.payload()).position(), -1);
            });
        }
    }
    private void requireServerThread() {
        if (!(body.level() instanceof ServerLevel level) || !level.getServer().isSameThread())
            throw new IllegalStateException("body work must execute on the server thread");
    }
}
