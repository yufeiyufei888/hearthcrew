package io.github.yufeiyufei888.hearthcrew.backend.numen;

import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.*;
import com.dwinovo.numen.core.task.build.BuildTaskRecord;
import com.dwinovo.numen.core.task.move.MoveToTaskRecord;
import com.dwinovo.numen.core.task.mine.MineBlockTaskRecord;
import io.github.yufeiyufei888.hearthcrew.kernel.preparation.PreparationGraph;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Blocks;
import java.util.*;

/** One authority, one complete selected plan, native tasks underneath. No model round-trips per step. */
final class PreparationTask implements Task {
    private final NumenPlayer body;
    private final PrepareRequest request;
    private final MutationAllowance allowance;
    private FacilityProbe probe;
    private final java.util.function.Supplier<java.util.concurrent.CompletableFuture<Void>> persistBoundary;
    private java.util.concurrent.CompletableFuture<Void> pendingWrite;
    private boolean childNeedsStart;
    private PreparationCatalog catalog;
    private PreparationGraph.Result selection;
    private java.util.concurrent.CompletableFuture<PreparationGraph.Result> pendingPlan;
    private SearchBudget planBudget=new SearchBudget();
    private boolean sourcesAdded;
    private SourceProbe sources;
    private DropProof pickup;
    private int index,before;
    private final List<DropProof> goalProofs=new ArrayList<>();
    private DropRecovery recovery;
    private DropRecovery childRecovery;
    private TaskResult stoppedForRecovery;
    private int replans;
    private int acquisitionContinuations;
    private int physicalSteps;
    private int craftedGoal;
    private boolean stationDetour;
    private int settleTicks;
    private Task child;
    private TaskRecord childRecord;
    private PreparationCatalog.Operation operation;
    private String condition="SEARCHING_FACILITY";
    private net.minecraft.core.BlockPos activeTable;
    private final List<Map<String,Object>> receipts=new ArrayList<>();
    PreparationTask(NumenPlayer body,PrepareRequest request,MutationAllowance allowance,java.util.function.Supplier<java.util.concurrent.CompletableFuture<Void>> persistBoundary) {
        this.body=body;this.request=request;this.allowance=allowance;this.persistBoundary=persistBoundary;probe=facilityProbe();
    }
    private FacilityProbe facilityProbe(){return new FacilityProbe(body,allowance.origin,request instanceof RecipeCraftRequest c?c.workstation:null,request.radius);}
    public TaskState tick(NumenPlayer ignored) {
        planBudget.advance(body.server.getTickCount(),true);
        if(pendingPlan!=null){if(!pendingPlan.isDone()){condition="PLANNING_PREPARATION";return TaskState.RUNNING;}selection=pendingPlan.join();pendingPlan=null;}
        if(pendingWrite!=null){if(!pendingWrite.isDone())return TaskState.RUNNING;pendingWrite.join();pendingWrite=null;}
        if(childNeedsStart){childNeedsStart=false;child.start(body);}
        if(request instanceof CollectRequest&&goalOwn()+goalTeam()>=request.count) {
            if(child!=null){
                child.stop(body,StopReason.REPLACED);childRecord.setState(TaskState.CANCELLED);var nativeResult=child.result(TaskState.CANCELLED);
                receipts.add(Map.of("revision",replans,"index",index,"executionId",childRecord.getToolCallId(),"state","CANCELLED_AFTER_CONFIRMED_GOAL","native",nativeResult==null?"NO_RESULT":nativeResult.toJson()));child=null;
            }
            condition="COLLECTION_COMPLETED";return TaskState.SUCCESS;
        }
        if(recovery!=null) {
            condition="RECOVERING_PRIOR_DROPS";
            if(!recovery.tick())return TaskState.RUNNING;
            receipts.add(Map.of("revision",replans,"state","RECOVERY_CHECKED","evidence",recovery.evidence(),"ownNew",goalOwn(),"teamNew",goalTeam()));
            recovery.stop();recovery=null;pendingWrite=persistBoundary.get();
        }
        if(selection==null||selection.plan()==null) {
            if(physicalSteps>=request.limits.maxSteps()){condition="PREPARATION_STEP_BUDGET_EXHAUSTED";return TaskState.FAILED;}
            probe.tick();if(!probe.done)return TaskState.RUNNING;
            if(request instanceof RecipeCraftRequest c&&c.workstation!=null&&probe.table==null){condition=probe.unknown?"EXPLICIT_FACILITY_PATH_UNKNOWN":"EXPLICIT_FACILITY_INVALID_OR_PRIVATE";return TaskState.FAILED;}
            if(catalog==null){catalog=new PreparationCatalog(body,request,probe.table,probe.atTable,probe.placement,probe.unknown);pendingPlan=planAsync();if(probe.atTable)activeTable=probe.table;return TaskState.RUNNING;}
            if(selection.plan()==null) {
                if(sources==null)sources=new SourceProbe(body,allowance.origin,request.radius);
                condition="SCANNING_PREPARATION_RESOURCES";sources.tick();if(!sources.done)return TaskState.RUNNING;
                if(!sourcesAdded){catalog.sources(sources.sources(),request);sourcesAdded=true;pendingPlan=planAsync();return TaskState.RUNNING;}
                if(selection.plan()==null){condition="PREPARATION_"+(sources.unloaded?"UNKNOWN":selection.status())+":"+selection.conditions().stream().sorted().limit(4).reduce((a,b)->a+","+b).orElse("missing verified method");return TaskState.FAILED;}
            }
            System.out.println("BACKEND_PREPARATION_PLAN "+new com.google.gson.Gson().toJson(selection));
        }
        if(index>=selection.plan().steps().size()) {
            boolean verified=request instanceof CollectRequest?goalOwn()+goalTeam()>=request.count:request instanceof RecipeCraftRequest?craftedGoal>=request.count:PreparationCatalog.eligible(body,request.output,request.minimumDurability)>=request.count;
            if(!verified){condition="PREPARATION_OUTPUT_NOT_VERIFIED";return TaskState.FAILED;}
            if(new ItemStack(request.output).isDamageableItem()) {
                int best=-1,remaining=-1;
                for(int slot=0;slot<body.getInventory().items.size();slot++) {
                    var stack=body.getInventory().getItem(slot);if(!stack.is(request.output))continue;
                    int durability=stack.getMaxDamage()-stack.getDamageValue();
                    if(durability>=request.minimumDurability&&durability>remaining){best=slot;remaining=durability;}
                }
                if(best>=0)body.getInventory().pickSlot(best);
            }
            condition=request instanceof CollectRequest?"COLLECTION_COMPLETED":"PREPARATION_COMPLETED";return TaskState.SUCCESS;
        }
        var step=selection.plan().steps().get(index);
        if(child==null) {
            for(var input:step.inputs().entrySet()) {
                var item=net.minecraft.core.registries.BuiltInRegistries.ITEM.get(net.minecraft.resources.ResourceLocation.parse(input.getKey()));
                if(body.getInventory().countItem(item)<input.getValue()){condition="PREPARATION_INVENTORY_CHANGED:"+input.getKey();return TaskState.FAILED;}
            }
            operation=catalog.operations.get(step.method());
            if(operation==null){condition="PREPARATION_CAPABILITY_UNAVAILABLE:"+step.method();return TaskState.FAILED;}
            if(physicalSteps>=request.limits.maxSteps()){condition="PREPARATION_STEP_BUDGET_EXHAUSTED";return TaskState.FAILED;}
            String id=request.getToolCallId()+".exec."+physicalSteps;long deadline=request.getDeadlineGameTime();
            stationDetour=false;settleTicks=0;
            before=body.getInventory().countItem(operation.item());
            pickup=null;childRecovery=null;stoppedForRecovery=null;
            switch(operation.kind()) {
                case CRAFT -> {
                    boolean tableRequired=catalog.methods.stream().filter(m->m.id().equals(step.method())).flatMap(m->m.needs().stream()).anyMatch(n->n.alternatives().contains(PreparationCatalog.TABLE));
                    if(tableRequired&&activeTable==null){condition="CRAFT_FACILITY_NOT_CONFIRMED";return TaskState.FAILED;}
                    allowance.phase(Set.of(),Set.of());
                    if(tableRequired&&!FacilityProbe.usableNow(body,activeTable)) {
                        if(!body.level().hasChunkAt(activeTable)||!body.level().getBlockState(activeTable).is(Blocks.CRAFTING_TABLE)
                            ||!BackendJournal.get(body.server).isPublic(body.level().dimension(),activeTable)){condition="CRAFT_FACILITY_CHANGED";return TaskState.FAILED;}
                        operation=new PreparationCatalog.Operation(PreparationCatalog.Kind.APPROACH,Items.CRAFTING_TABLE,activeTable,"");
                        childRecord=new MoveToTaskRecord(id,deadline,(double)activeTable.getX(),(double)activeTable.getY(),(double)activeTable.getZ(),null,false);
                        stationDetour=true;condition="RETURNING_TO_FACILITY";
                    } else {childRecord=new CraftRequest(id,deadline,operation.item(),step.quantity(),tableRequired?activeTable:null,operation.recipe(),step.inputs());condition="PREPARING_ITEM:"+step.output();}
                }
                case APPROACH -> {
                    var p=operation.position();childRecord=new MoveToTaskRecord(id,deadline,(double)p.getX(),(double)p.getY(),(double)p.getZ(),null,false);
                    allowance.phase(Set.of(),Set.of());condition="APPROACHING_FACILITY";
                }
                case PLACE -> {
                    var p=operation.position();var target=new BuildTaskRecord.Target(Blocks.CRAFTING_TABLE,Items.CRAFTING_TABLE,p,"preparation workbench",null,null,null).asItemPlace();
                    childRecord=new BuildTaskRecord(id,deadline,List.of(target),false,true,false);allowance.phase(Set.of(),Set.of(p));condition="PLACING_OWN_FACILITY";
                }
                case ACQUIRE -> {
                    var source=operation.source();
                    if(isGoalSource())allowance.collectionPhase(source,step.quantity());else allowance.preparationPhase(source,step.quantity());
                    childRecord=new MineBlockTaskRecord(id,deadline,Set.of(source.block()),step.quantity(),step.output());
                    pickup=DropProof.forAction(body.serverLevel(),body.getUUID(),id,NumenBackend.generation(body));condition="ACQUIRING_PREPARATION_MATERIAL:"+step.output();
                    if(isGoalSource())goalProofs.add(pickup);
                    if(!source.tools().isEmpty()) {
                        int best=-1,remaining=-1;
                        for(int slot=0;slot<body.getInventory().items.size();slot++) {
                            var stack=body.getInventory().getItem(slot);int durability=stack.isDamageableItem()?stack.getMaxDamage()-stack.getDamageValue():Integer.MAX_VALUE;
                            if(source.tools().contains(stack.getItem())&&durability>=Math.min(16,step.quantity())+2&&durability>remaining){best=slot;remaining=durability;}
                        }
                        if(best<0){condition="PREPARATION_TOOL_CONDITION_CHANGED";return TaskState.FAILED;}
                        body.getInventory().pickSlot(best);
                    }
                }
            }
            physicalSteps++;childRecord.setState(TaskState.RUNNING);childRecord.markStarted(body.level().getGameTime());
            child=operation.kind()==PreparationCatalog.Kind.APPROACH?new FacilityApproach(operation.position()):TaskFactory.create(body,childRecord);childNeedsStart=true;pendingWrite=persistBoundary.get();return TaskState.RUNNING;
        }
        childRecord.extendDeadlineTo(request.getDeadlineGameTime());
        boolean outputReady=pickup!=null&&pickup.ownAcquired(operation.item())+(isGoalSource()?pickup.teamAcquired(operation.item()):0)>=step.quantity();
        if(pickup!=null&&!outputReady&&allowance.phaseExhausted()&&childRecovery==null) {
            // The source budget is already spent. Do not keep chasing the upstream index's
            // cached integer drop cells; use live positions of this step's evidenced entities.
            child.stop(body,StopReason.REPLACED);stoppedForRecovery=child.result(TaskState.CANCELLED);
            allowance.phase(Set.of(),Set.of()); // only the remaining root access budget can change terrain now
            childRecovery=new DropRecovery(body,List.of(pickup),operation.item(),allowance.origin,allowance.remaining()>0);
        }
        TaskState state;
        if(outputReady)state=TaskState.SUCCESS;
        else if(childRecovery!=null){condition="RECOVERING_STEP_DROPS";state=childRecovery.tick()?TaskState.FAILED:TaskState.RUNNING;}
        else state=toolMissing()?TaskState.FAILED:childRecord.getState().isTerminal()?childRecord.getState():child.tick(body);
        childRecord.setState(state);allowance.reconcile();
        if(!state.isTerminal())return state;
        // Native navigation may finish during the final descent. Wait for original physics;
        // the following usability check still requires actual ground contact and line of sight.
        if(state==TaskState.SUCCESS&&operation.kind()==PreparationCatalog.Kind.APPROACH&&!body.onGround()&&settleTicks++<20) {
            condition="SETTLING_AT_FACILITY";return TaskState.RUNNING;
        }
        child.stop(body,StopReason.REPLACED);if(childRecovery!=null)childRecovery.stop();
        var result=stoppedForRecovery==null?child.result(state):stoppedForRecovery;
        int after=body.getInventory().countItem(operation.item());
        boolean verified=state==TaskState.SUCCESS&&switch(operation.kind()) {
            case CRAFT -> after-before>=step.quantity();
            case APPROACH -> FacilityProbe.usableNow(body,operation.position());
            case PLACE -> before-after==1&&FacilityProbe.usableNow(body,operation.position());
            case ACQUIRE -> isGoalSource()?pickup.ownAcquired(operation.item())+pickup.teamAcquired(operation.item())>=step.quantity():after-before>=step.quantity()&&pickup.ownAcquired(operation.item())>=step.quantity();
        };
        receipts.add(Map.of("revision",replans,"index",index,"executionId",childRecord.getToolCallId(),"method",step.method(),"state",state.name(),"verified",verified,"inventoryDelta",after-before,"native",result==null?"NO_RESULT":result.toJson(),"pickup",pickup==null?Map.of():pickup.evidence()));
        child=null;
        if(!verified){
            // Upstream's inventory-delta counter can finish after picking up a peer's
            // unrelated output. Preserve that receipt, but only this root's drop proofs
            // satisfy its goal. A partially productive candidate with an inaccessible drop
            // also leaves a shortfall, not proof that all remaining candidates are blocked.
            // Continue locally while retaining pending recovery evidence and the step limit.
            if(isGoalSource()&&(state==TaskState.SUCCESS||state==TaskState.FAILED)&&!toolMissing()
                    &&pickup.ownAcquired(operation.item())+pickup.teamAcquired(operation.item())>0
                    &&physicalSteps<request.limits.maxSteps()) {
                acquisitionContinuations++;probe.cancel();probe=facilityProbe();catalog=null;selection=null;sourcesAdded=false;index=0;activeTable=null;
                // Keep the bounded source scan and all original output evidence. Fresh
                // inventory/facility facts are rebuilt, and every native break is rechecked.
                allowance.phase(Set.of(),Set.of());condition="CONTINUING_VERIFIED_RESOURCE_SHORTFALL";
                pendingWrite=persistBoundary.get();return TaskState.RUNNING;
            }
            if(toolMissing()&&replans<2&&physicalSteps<request.limits.maxSteps()&&(!(request instanceof CollectRequest c)||c.allowPreparation)&&(!(request instanceof RecipeCraftRequest c)||c.allowPreparation)) {
                replans++;probe.cancel();probe=facilityProbe();catalog=null;selection=null;sourcesAdded=false;sources=null;index=0;activeTable=null;
                allowance.phase(Set.of(),Set.of());condition="REPLANNING_BROKEN_TOOL";
                if(request instanceof CollectRequest&&!DropProof.visiblePending(body.serverLevel(),goalProofs,request.output,allowance.origin).isEmpty()) {
                    physicalSteps++;recovery=new DropRecovery(body,goalProofs,request.output,allowance.origin,allowance.remaining()>0);
                }
                pendingWrite=persistBoundary.get();return TaskState.RUNNING;
            }
            condition="PREPARATION_STEP_NOT_COMPLETED:"+step.method()+":"+state;return TaskState.FAILED;
        }
        if(operation.kind()==PreparationCatalog.Kind.APPROACH||operation.kind()==PreparationCatalog.Kind.PLACE)activeTable=operation.position();
        if(request instanceof RecipeCraftRequest c&&operation.kind()==PreparationCatalog.Kind.CRAFT&&operation.recipe().equals(c.recipe.toString()))craftedGoal+=after-before;
        if(!stationDetour)index++;condition="RESUMING_PREPARATION";
        pendingWrite=persistBoundary.get();
        // Next ready child is advanced on the next executor tick, never another model round.
        return TaskState.RUNNING;
    }
    int completedSteps(){return (int)receipts.stream().filter(r->Boolean.TRUE.equals(r.get("verified"))).count();}
    boolean waitingForDisk(){return pendingWrite!=null&&!pendingWrite.isDone();}
    void blocked(String reason){condition=reason;}
    private int goalOwn(){return goalProofs.stream().mapToInt(p->p.ownAcquired(request.output)).sum();}
    private int goalTeam(){return goalProofs.stream().mapToInt(p->p.teamAcquired(request.output)).sum();}
    private java.util.concurrent.CompletableFuture<PreparationGraph.Result> planAsync(){return catalog.planAsync(request,request instanceof CollectRequest?request.count-goalOwn()-goalTeam():request instanceof RecipeCraftRequest?request.count-craftedGoal:request.count,request.limits.maxSteps()-physicalSteps,request.limits.maxBreaks()-allowance.preparationBroken(),planBudget);}
    private boolean toolMissing() {
        if(operation==null||operation.kind()!=PreparationCatalog.Kind.ACQUIRE||operation.source().tools().isEmpty())return false;
        return body.getInventory().items.stream().noneMatch(s->operation.source().tools().contains(s.getItem())&&(!s.isDamageableItem()||s.getDamageValue()<s.getMaxDamage()));
    }
    private boolean isGoalSource(){return request instanceof CollectRequest&&operation!=null&&operation.kind()==PreparationCatalog.Kind.ACQUIRE&&operation.item()==request.output;}
    void drops(net.neoforged.neoforge.event.level.BlockDropsEvent event) {
        if(pickup==null||operation==null)return;
        for(var entity:event.getDrops())if(entity.getItem().is(operation.item()))pickup.spawned(entity.getUUID(),operation.item(),entity.getItem().getCount());
    }
    Map<String,Object> checkpoint() {
        var checkpoint=new LinkedHashMap<String,Object>();checkpoint.put("step",index);checkpoint.put("condition",condition);checkpoint.put("receipts",List.copyOf(receipts));checkpoint.put("mutations",allowance.evidence());
        checkpoint.put("limits",Map.of("maxDepth",request.limits.maxDepth(),"maxSteps",request.limits.maxSteps(),"maxBreaks",request.limits.maxBreaks()));checkpoint.put("radius",request.radius);checkpoint.put("executionSteps",physicalSteps);checkpoint.put("stationDetour",stationDetour);
        if(request instanceof RecipeCraftRequest c)checkpoint.put("craftGoal",Map.of("recipe",c.recipe.toString(),"executions",c.executions,"requestedNew",c.count,"craftedNew",craftedGoal));
        if(recovery!=null)checkpoint.put("recovery",recovery.evidence());
        if(childRecovery!=null)checkpoint.put("stepRecovery",childRecovery.evidence());
        checkpoint.put("planRevision",replans);
        checkpoint.put("acquisitionContinuations",acquisitionContinuations);
        if(request instanceof CollectRequest)checkpoint.put("goalOutput",Map.of("item",PreparationCatalog.key(request.output),"requestedNew",request.count,"ownNew",goalOwn(),"teamNew",goalTeam(),"sources",goalProofs.stream().map(DropProof::evidence).toList()));
        if(pickup!=null)checkpoint.put("currentPickup",pickup.evidence());
        if(childRecord!=null)checkpoint.put("child",Map.of("id",childRecord.getToolCallId(),"state",childRecord.getState().name()));
        if(selection!=null){
            checkpoint.put("planStatus",selection.status());
            if(selection.plan()!=null) {
                var plan=selection.plan();var cost=plan.cost();
                checkpoint.put("plan",Map.of("steps",plan.steps().stream().map(s->Map.of("method",s.method(),"runs",s.runs(),"output",s.output(),"quantity",s.quantity(),"inputs",s.inputs())).toList(),
                    "cost",Map.of("resources",cost.resources(),"breaks",cost.breaks(),"placements",cost.placements(),"ticks",cost.ticks()),"remaining",plan.remaining()));
            } else checkpoint.put("conditions",selection.conditions());
        }
        return checkpoint;
    }
    public void stop(NumenPlayer ignored,StopReason reason) {
        if(reason==StopReason.PREEMPTED)planBudget.advance(body.server.getTickCount(),false);else planBudget.invalidate();
        if(recovery!=null)recovery.stop();
        if(childRecovery!=null)childRecovery.stop();
        if(child!=null)child.stop(body,reason);
        if(reason!=StopReason.PREEMPTED)probe.cancel();
    }
    public TaskResult result(TaskState terminal) {
        if(child!=null){child.result(terminal);child=null;}
        return new TaskResult(terminal==TaskState.SUCCESS,condition,terminal==TaskState.TIMEOUT,terminal==TaskState.CANCELLED,checkpoint());
    }
    public String name(){return condition;}
}
