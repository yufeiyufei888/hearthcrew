package io.github.yufeiyufei888.hearthcrew.backend.numen;

import io.github.yufeiyufei888.hearthcrew.backend.CompanionBackend;
import com.dwinovo.numen.entity.CompanionFactory;
import com.dwinovo.numen.entity.InputDriver;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.*;
import com.dwinovo.numen.core.task.build.BuildTaskRecord;
import com.dwinovo.numen.core.task.mine.MineBlockTaskRecord;
import com.dwinovo.numen.core.task.move.MoveToTaskRecord;
import net.minecraft.core.BlockPos;
import java.util.*;

/** Owns one upstream Task directly, avoiding upstream tool-call replay persistence. */
public final class NumenBackend implements CompanionBackend {
    private static final Map<UUID, NumenBackend> OWNED = new HashMap<>();
    private static volatile int ownedCount;
    private static volatile boolean unrelatedNumen;
    /** Published on the server thread; client never reads the mutable body map. */
    public static boolean hasOwnedBodies(){return ownedCount>0&&!unrelatedNumen;}
    private static final Set<UUID> JOINING = new HashSet<>();
    private NumenPlayer body;
    private Identity identity;
    private final BackendJournal journal;
    private long actionGeneration;
    private boolean respawning;
    private Task task;
    private TaskRecord record;
    private boolean paused, closed;
    private long activeTicks, pausedTicks;
    private NavigationProgress navigation=new NavigationProgress();
    private String result = "";
    private java.util.concurrent.CompletableFuture<Void> pendingStart;
    private String executionFailure="";
    private MutationAllowance allowance;
    private DropProof proof;
    private final SurvivalArbiter survival = new SurvivalArbiter();
    private String fault = "";
    private SearchBudget searchBudget=new SearchBudget();

    static long generation(NumenPlayer body){var b=OWNED.get(body.getUUID());if(b==null||b.body!=body)throw new IllegalStateException("Unowned body");return b.identity.generation();}
    public static boolean owns(UUID id) { return OWNED.containsKey(id)||JOINING.contains(id); }
    /** Reserve authority before native spawn callbacks can dispatch upstream work. */
    public static NumenBackend spawn(net.minecraft.server.level.ServerLevel level,UUID id,String name,UUID owner,
            net.minecraft.world.phys.Vec3 position,boolean restore) {
        var server=level.getServer();
        if(!server.isSameThread())throw new IllegalStateException("Server thread required");
        BackendProtection.requireConfigured(server);
        if(name==null||!name.matches("[A-Za-z0-9_]{1,16}")||id==null||owner==null)throw new IllegalArgumentException("Invalid companion identity");
        if(server.getPlayerList().getPlayerByName(name)!=null||server.getPlayerList().getPlayer(id)!=null||owns(id))throw new IllegalStateException("COMPANION_IDENTITY_CONFLICT");
        if(!restore&&java.nio.file.Files.exists(server.getWorldPath(net.minecraft.world.level.storage.LevelResource.PLAYER_DATA_DIR).resolve(id+".dat")))
            throw new IllegalStateException("EXISTING_PLAYER_DATA_REQUIRES_EXPLICIT_RESTORE");
        JOINING.add(id);
        try {
            var nativeBody=CompanionFactory.spawn(server,id,name,owner,level,position);
            return new NumenBackend(nativeBody);
        } catch(RuntimeException failure) {
            // A login callback can fail after registration. Do not leave an unowned body
            // with live upstream inputs; native removal preserves any existing player data.
            var partial=server.getPlayerList().getPlayer(id);
            if(partial instanceof NumenPlayer p) {
                InputDriver.haltVehicle(p);p.stopUsingItem();
                try {CompanionFactory.despawn(server,p);} catch(RuntimeException cleanup){failure.addSuppressed(cleanup);}
            }
            throw failure;
        } finally { JOINING.remove(id); }
    }
    public static SearchBudget searchBudget(net.minecraft.server.level.ServerPlayer player) {
        if(player==null)return null;var b=OWNED.get(player.getUUID());if(b==null||b.body!=player)return null;b.requireThread();return b.searchBudget;
    }
    public static void selectMiningCandidates(NumenPlayer player,java.util.List<BlockPos> candidates){var b=OWNED.get(player.getUUID());if(b!=null&&b.body==player&&!b.survival.active())b.navigation.candidates(player,candidates);}
    public static void navigationTarget(NumenPlayer player,BlockPos target){var b=OWNED.get(player.getUUID());if(b!=null&&b.body==player&&!b.survival.active())b.navigation.target(player,target);}
    public static void navigationFailed(NumenPlayer player,String reason){var b=OWNED.get(player.getUUID());if(b!=null&&b.body==player&&!b.survival.active())b.navigation.failed(player,(reason.toLowerCase(java.util.Locale.ROOT).contains("budget")?"SEARCH_BUDGET_EXHAUSTED":b.searchBudget.outcome())+": "+reason);}
    static boolean mayUseFacility(net.minecraft.world.entity.player.Player player,BlockPos position) {
        var b=OWNED.get(player.getUUID());return b!=null&&b.body==player&&b.task!=null&&!b.paused&&!b.survival.active()
            &&FacilityAccess.denial(b.body.serverLevel(),position).isEmpty();
    }
    public static void relocating(net.minecraft.server.level.ServerPlayer player) {
        var backend=OWNED.get(player.getUUID());if(backend==null||backend.body!=player)return;
        backend.requireThread();backend.cancel();backend.survival.discard(backend.body);
        backend.searchBudget.invalidate();backend.searchBudget=new SearchBudget();
        backend.identity=new Identity(backend.identity.world(),player.getUUID(),backend.journal.nextGeneration(player.getUUID()));
    }
    static void shutdown(net.neoforged.neoforge.event.server.ServerStoppingEvent event) {
        for(var backend:List.copyOf(OWNED.values()))if(backend.body.server==event.getServer()) {
            try {backend.close();}catch(RuntimeException failure){backend.failClosed("shutdown",failure);}
        }
        try {BackendJournal.get(event.getServer()).barrier().get(10,java.util.concurrent.TimeUnit.SECONDS);}catch(Exception error){System.err.println("HEARTHCREW_EXECUTION_LOG_FLUSH_FAILED "+error.getClass().getSimpleName());}
        BackendProtection.release(event.getServer());
    }
    static void stopped(net.neoforged.neoforge.event.server.ServerStoppedEvent event){OWNED.entrySet().removeIf(e->e.getValue().body.server==event.getServer());ownedCount=OWNED.size();}
    /** Immutable main-thread snapshot; path workers must never dereference the live body. */
    public record PathPolicy(BlockPos origin,int radius,Set<BlockPos> protectedPositions,Set<net.minecraft.world.level.block.Block> targets,int accessRemaining,boolean restrictTargets,Set<BlockPos> targetPositions) {
        public boolean permitsBreak(BlockPos p,net.minecraft.world.level.block.state.BlockState state) {
            return Math.max(Math.max(Math.abs(p.getX()-origin.getX()),Math.abs(p.getY()-origin.getY())),Math.abs(p.getZ()-origin.getZ()))<=radius
                && !protectedPositions.contains(p)&&!state.hasBlockEntity()
                &&(targets.contains(state.getBlock())?(!restrictTargets||targetPositions.contains(p)):accessRemaining>0);
        }
    }
    public static PathPolicy pathPolicy(net.minecraft.server.level.ServerPlayer player) {
        if(player==null)return null;
        var b=OWNED.get(player.getUUID());if(b==null)return null;b.requireThread();
        var protectedPositions=b.journal.structures(player.level().dimension());protectedPositions.addAll(BackendProtection.positions(player.serverLevel()));
        if(b.body!=player||b.task==null||b.allowance==null||b.paused||b.survival.active())
            return new PathPolicy(player.blockPosition(),32,Set.copyOf(protectedPositions),Set.of(),0,false,Set.of());
        return new PathPolicy(b.allowance.origin,b.allowance.radius,Set.copyOf(protectedPositions),b.allowance.primary,b.allowance.remaining(),b.allowance.restricted(),b.allowance.currentTargetPositions());
    }
    public static boolean handleDeath(NumenPlayer previous) {
        var backend=OWNED.get(previous.getUUID());
        if(backend==null||backend.body!=previous)return false;
        backend.requireThread();
        if(backend.respawning)return true;
        backend.respawning=true;
        backend.searchBudget.invalidate();
        backend.cancel();
        backend.survival.discard(previous);
        previous.server.execute(()->{
          try {
            if(backend.closed||previous.server.getPlayerList().getPlayer(previous.getUUID())!=previous)return;
            // The vanilla transaction handles drops/keepInventory, bed position and player tracking.
            var next=previous.server.getPlayerList().respawn(previous,false,net.minecraft.world.entity.Entity.RemovalReason.KILLED);
            if(!(next instanceof NumenPlayer player))throw new IllegalStateException("Owned respawn lost player subtype");
            player.connection.player=player;
            player.setOwnerUuid(previous.getOwnerUuid());
            player.showAllSkinLayers();
            backend.body=player;
            backend.searchBudget=new SearchBudget();
            backend.identity=new Identity(backend.identity.world(),player.getUUID(),backend.journal.nextGeneration(player.getUUID()));
            backend.respawning=false;
            backend.halt();
            System.out.println("BACKEND_LAB_RESPAWN "+backend.snapshot());
          } catch(RuntimeException failure){backend.failClosed("respawn",failure);}
        });
        return true;
    }
    public static boolean miningCandidateAllowed(NumenPlayer player,BlockPos p) {
        var b=OWNED.get(player.getUUID());if(b==null)return true;b.requireThread();
        return b.body==player&&b.allowance!=null&&b.allowance.inside(p)&&b.body.level().hasChunkAt(p)&&!BackendProtection.isProtected(b.body.serverLevel(),p)
            &&b.allowance.candidateAllowed(p)&&!b.journal.structureAt(player.level().dimension(),p)&&b.body.level().getBlockEntity(p)==null;
    }
    static boolean beforeBreak(net.minecraft.world.entity.Entity player,BlockPos p) {var b=OWNED.get(player.getUUID());return b!=null&&b.body==player&&!b.paused&&!b.survival.active()&&b.task!=null&&b.allowance!=null&&b.allowance.beforeBreak(p);}
    static boolean beforePlace(net.minecraft.world.entity.Entity player,BlockPos p) {var b=OWNED.get(player.getUUID());return b!=null&&b.body==player&&!b.paused&&!b.survival.active()&&b.task!=null&&b.allowance!=null&&b.allowance.beforePlace(p);}
    static void drops(net.neoforged.neoforge.event.level.BlockDropsEvent e) {
        if(e.getBreaker()==null)return;var b=OWNED.get(e.getBreaker().getUUID());
        if(b==null||b.body!=e.getBreaker()||b.task==null||b.allowance==null)return;
        if(b.task instanceof PreparationTask preparation){preparation.drops(e);return;}
        if(!b.allowance.primary.contains(e.getState().getBlock()))return;
        if(b.proof==null)return;
        for(var item:e.getDrops())b.proof.spawned(item.getUUID(),item.getItem().getItem(),item.getItem().getCount());
    }
    public static boolean tickManaged(NumenPlayer p) {
        var backend=OWNED.get(p.getUUID());
        if(backend==null){if(!JOINING.contains(p.getUUID()))return false;InputDriver.haltVehicle(p);return true;}
        if(backend.body==p) {
            try {backend.tick();} catch(RuntimeException failure) {backend.failClosed("tick",failure);}
        }
        return true;
    }
    NumenBackend(NumenPlayer body) {
        this.body = body;
        BackendProtection.requireConfigured(body.server);
        if (OWNED.containsKey(body.getUUID())) throw new IllegalStateException("Body already owned");
        journal=BackendJournal.get(body.server);
        identity = new Identity(journal.worldId().toString(), body.getUUID(), journal.nextGeneration(body.getUUID()));
        OWNED.put(body.getUUID(),this);ownedCount=OWNED.size();
        body.closeContainer();
        halt();
        // Lab bodies are freshly created; never take over or clear another body's saved task.
    }
    public record Submission(boolean reused,String actionId,long generation,String state,String result) {}
    public Submission submit(TaskRecord next) {
        return submit(next,0);
    }
    public Submission submit(TaskRecord next,int accessBudget) {
        return submit(identity,next,accessBudget);
    }
    public Submission submit(Identity expected,TaskRecord next,int accessBudget) {
        return submit(expected,next,accessBudget,null);
    }
    private Submission submit(Identity expected,TaskRecord next,int accessBudget,String authorityFingerprint) {
        requireThread();
        if(!identity.equals(expected))throw new IllegalArgumentException("STALE_EXECUTION_CONTEXT: refresh world and body generation");
        if(next.getToolCallId()==null||!next.getToolCallId().matches("[A-Za-z0-9_.:-]{1,128}"))throw new IllegalArgumentException("Invalid action identity");
        if(accessBudget<0||accessBudget>64)throw new IllegalArgumentException("Invalid access budget");
        if(next instanceof BuildTaskRecord build && (!build.consumeMaterials||build.targets.stream().anyMatch(t->!t.itemPlace())||build.replaceExisting||!build.blockEntityData.isEmpty()||!build.entities.isEmpty()))
            throw new IllegalArgumentException("UNSUPPORTED_EXACT_BUILD: only native item placement without clearing or injected block/entity data");
        if(!(next instanceof BuildTaskRecord||next instanceof MineBlockTaskRecord||next instanceof MoveToTaskRecord||next instanceof CraftRequest||next instanceof PrepareRequest||next instanceof ContainerRequest||next instanceof ProcessRequest))
            throw new IllegalArgumentException("UNSUPPORTED_LAB_TASK");
        if(next instanceof MineBlockTaskRecord m&&(m.count<1||m.count>64||m.targets.isEmpty()||m.targets.size()>32))throw new IllegalArgumentException("Invalid mining request");
        String fingerprint=authorityFingerprint==null?RequestIdentity.fingerprint(next,accessBudget):authorityFingerprint;
        var prior=journal.find(body.getUUID(),next.getToolCallId());
        if(prior!=null) {
            if(!prior.fingerprint().equals(fingerprint))throw new IllegalArgumentException("ACTION_ID_CONFLICT");
            return new Submission(true,prior.actionId(),prior.generation(),prior.state(),prior.result());
        }
        if (closed || paused || respawning || survival.active() || !fault.isEmpty() || record != null && !record.getState().isTerminal())
            throw new IllegalStateException("Body unavailable");
        actionGeneration=identity.generation();
        journal.begin(body.getUUID(),next.getToolCallId(),fingerprint,actionGeneration);
        pendingStart=journal.persist(body.getUUID(),next.getToolCallId());
        allowance=new MutationAllowance(body,next instanceof MineBlockTaskRecord m?m.targets:Set.of(),
                next instanceof BuildTaskRecord b?b.targets.stream().map(BuildTaskRecord.Target::pos).collect(java.util.stream.Collectors.toSet()):Set.of(),accessBudget,next instanceof PrepareRequest p?p.limits.maxBreaks():64,next instanceof PrepareRequest p?p.radius:32);
        record = next;
        proof=next instanceof MineBlockTaskRecord?DropProof.forAction(body.serverLevel(),body.getUUID(),next.getToolCallId(),identity.generation()):null;
        activeTicks = pausedTicks = 0;navigation=new NavigationProgress();
        result = "";executionFailure="";
        next.setState(TaskState.RUNNING);
        next.markStarted(body.level().getGameTime());
        try {
            task = next instanceof PrepareRequest prepare?new PreparationTask(body,prepare,allowance,()->{checkpoint();return journal.barrier();}):next instanceof MoveToTaskRecord move?new ExactMoveTask(body,move):TaskFactory.create(body, next);
            // Native start is deferred until the action identity has reached durable storage.
            allowance.reconcile();
            if (next.getState().isTerminal()) settle();
        } catch(RuntimeException failure) { failClosed("start", failure); }
        return new Submission(false,next.getToolCallId(),actionGeneration,next.getState().name(),result);
    }
    private void tick() {
        requireThread();
        unrelatedNumen=body.server.getPlayerList().getPlayers().stream().anyMatch(p->p instanceof NumenPlayer&&!OWNED.containsKey(p.getUUID()));
        searchBudget.advance(body.server.getTickCount(),!paused&&!closed);
        if (closed || !body.isAlive() || body.isRemoved()) { cancel(); return; }
        if (paused) {
            pausedTicks++;
            if (record != null && !record.getState().isTerminal()) record.extendDeadlineTo(record.getDeadlineGameTime() + 1);
            halt(); return;
        }
        if(!fault.isEmpty()) {halt();return;}
        Task rescue = survival.select(body);
        boolean switching = survival.change(body,rescue);
        if(rescue!=null) {
            if(switching && task!=null) task.stop(body,Task.StopReason.PREEMPTED);
            if(record!=null&&!record.getState().isTerminal()) record.extendDeadlineTo(record.getDeadlineGameTime()+1);
            survival.tick(body);
            return;
        }
        if (task == null || record.getState().isTerminal()) { halt(); return; }
        if(pendingStart!=null){
            if(!pendingStart.isDone()){record.extendDeadlineTo(record.getDeadlineGameTime()+1);halt();return;}
            pendingStart.join();pendingStart=null;task.start(body);
        }
        if(task instanceof PreparationTask p&&p.waitingForDisk()){record.extendDeadlineTo(record.getDeadlineGameTime()+1);halt();return;}
        activeTicks++;
        if (body.level().getGameTime() >= record.getDeadlineGameTime()) record.setState(TaskState.TIMEOUT);
        else if(record instanceof MineBlockTaskRecord mine && proof.acquired()>=mine.count) {
            task.stop(body,Task.StopReason.REPLACED);
            record.setState(TaskState.SUCCESS);
        } else {
            try {record.setState(task.tick(body));}
            finally {allowance.reconcile();}
        }
        if (!record.getState().isTerminal() && navigation.tick(body,allowance.primaryBroken()+allowance.accessSpent()+allowance.preparationBroken(),task instanceof PreparationTask p?p.completedSteps():0,searchBudget.searching())) {
            task.stop(body,Task.StopReason.REPLACED);record.setState(TaskState.FAILED);
            executionFailure="NO_PROGRESS_30_SECONDS: "+navigation.snapshot().get("reason");
            if(task instanceof PreparationTask p)p.blocked(executionFailure);
        }
        if (record.getState().isTerminal()) settle();
        else if(activeTicks%20==0)checkpoint();
    }
    private void checkpoint(){
        long began=System.nanoTime();
        String evidence=result;
        if(!record.getState().isTerminal())evidence=new com.google.gson.Gson().toJson(Map.of("mutations",allowance.evidence(),
            "pickupEvidence",proof==null?Map.of():proof.evidence(),"execution","RUNNING","preparation",task instanceof PreparationTask p?p.checkpoint():Map.of()));
        journal.update(body.getUUID(),record.getToolCallId(),record.getState().name(),evidence,allowance.accessSpent(),allowance.primaryBroken());
        journal.persist(body.getUUID(),record.getToolCallId());
        SlowOperations.record("checkpoint_serialization",began);
    }
    private void settle() {
        if (task == null) return;
        task.stop(body, Task.StopReason.REPLACED);
        var outcome = task.result(record.getState());
        if(record instanceof MineBlockTaskRecord mine && record.getState()==TaskState.SUCCESS && proof.acquired()<mine.count) {
            record.setState(TaskState.FAILED);
            outcome=TaskResult.fail("OUTPUT_NOT_VERIFIED: upstream success does not prove requested resource acquisition",
                    Map.of("upstream",outcome==null?"NO_RESULT":outcome.toJson(),"pickupEvidence",proof.evidence(),"requested",mine.count));
        }
        if(proof!=null)outcome=new TaskResult(record.getState()==TaskState.SUCCESS,
            record.getState()==TaskState.SUCCESS?"Requested acquisition verified by native pickup events":"Resource task ended before verified completion",
            record.getState()==TaskState.TIMEOUT,record.getState()==TaskState.CANCELLED,
            Map.of("upstream",outcome==null?"NO_RESULT":outcome.toJson(),"pickupEvidence",proof.evidence(),"mutations",allowance.evidence(),"requested",((MineBlockTaskRecord)record).count));
        if(!executionFailure.isEmpty())outcome=TaskResult.fail(executionFailure,Map.of("navigation",navigation.snapshot(),"original",outcome==null?"NO_RESULT":outcome.toJson()));
        record.setResult(outcome);
        result = outcome == null ? "NO_UPSTREAM_RESULT" : outcome.toJson();
        checkpoint();
        task = null;
        halt();
        System.out.println("BACKEND_LAB_RESULT " + snapshot());
        System.out.println("BACKEND_LAB_MUTATIONS " + allowance.evidence());
        if(proof!=null)System.out.println("BACKEND_LAB_PICKUP " + proof.evidence());
    }
    private void halt() {
        InputDriver.haltVehicle(body);
        body.setShiftKeyDown(false);
        body.stopUsingItem();
    }
    private void failClosed(String phase,RuntimeException failure) {
        // A task can throw after a native mutation. Preserve evidence and never auto-replay it.
        fault="BACKEND_EXCEPTION:"+phase+":"+failure.getClass().getSimpleName();
        try {if(task!=null)task.stop(body,Task.StopReason.REPLACED);} catch(RuntimeException ignored) {}
        try {survival.discard(body);} catch(RuntimeException ignored) {}
        // Cleanup and evidence failures must not escape the body tick and halt the entire server.
        try {
        if(allowance!=null)allowance.reconcile();
        if(record!=null) {
            var confirmed=journal.find(body.getUUID(),record.getToolCallId());
            if(confirmed!=null&&BackendJournal.terminal(confirmed.state())) {
                record.setState(TaskState.valueOf(confirmed.state()));result=confirmed.result();return;
            }
            record.setState(TaskState.FAILED);
            result=TaskResult.fail(fault,Map.of("worldEffects","RECONCILE_REQUIRED",
                "mutations",allowance==null?Map.of():allowance.evidence(),"pickupEvidence",proof==null?Map.of():proof.evidence())).toJson();
            if(allowance!=null)checkpoint();
        }
        } catch(RuntimeException evidenceFailure) {
            fault += ";EVIDENCE_REQUIRES_RECONCILIATION:"+evidenceFailure.getClass().getSimpleName();
        } finally {
            task=null;
            try {halt();}catch(RuntimeException haltFailure){body.zza=0;body.xxa=0;fault+=";INPUT_CLEANUP_FAILED:"+haltFailure.getClass().getSimpleName();}
            System.out.println("BACKEND_LAB_FAULT "+fault+" "+snapshot());
        }
    }
    public Map<String,Object> diagnostics() {return Map.of("active",survival.active(),"reaction",survival.name(),"reactionTicks",survival.ticks(),"reactionSwitches",survival.switches(),"fault",fault,"maxSearchNodesPerBodyTick",searchBudget.maximum());}
    public Map<String,Object> execution(){requireThread();var report=new LinkedHashMap<String,Object>();report.put("activeTicks",activeTicks);report.put("pausedTicks",pausedTicks);report.put("navigation",navigation.snapshot());report.put("timings",SlowOperations.snapshot());report.put("persistence",pendingStart!=null||task instanceof PreparationTask p&&p.waitingForDisk()?"WAITING_FOR_DURABILITY":"READY");if(allowance!=null)report.putAll(allowance.evidence());if(task instanceof PreparationTask p)report.put("preparation",p.checkpoint());if(proof!=null)report.put("pickupEvidence",proof.evidence());report.put("processingOrders",FurnaceWork.get(body.server).summary(body.getUUID()));return report;}
    private void requireThread() {
        if (!body.server.isSameThread()) throw new IllegalStateException("Server thread required");
    }
    public NumenPlayer body() { return body; }
    public String backendId(){return "numen";}
    public Set<io.github.yufeiyufei888.hearthcrew.entity.BodyOrder.Kind> capabilities(){return OrderAdapter.CAPABILITIES;}
    public Acceptance dispatch(Request request) {
        requireThread();
        if(!identity.equals(request.identity()))throw new IllegalArgumentException("STALE_EXECUTION_CONTEXT");
        if(request.priority().outranks(io.github.yufeiyufei888.hearthcrew.kernel.ActionPriority.OWNER))
            throw new IllegalArgumentException("LOCAL_SAFETY_AUTHORITY_REQUIRED");
        var next=OrderAdapter.convert(this,request.actionId(),request.order());
        int budget=request.order().kind()==io.github.yufeiyufei888.hearthcrew.entity.BodyOrder.Kind.EXCAVATE?Math.min(request.order().count(),request.order().accessBudget()):request.order().accessBudget();
        String fingerprint=RequestIdentity.fingerprint(next,budget)+":"+request.priority().name()+":"+request.order().kind();
        // Busy ordinary requests are queued by the controller, never implicit preemption here.
        var accepted=submit(request.identity(),next,budget,fingerprint);
        return new Acceptance(accepted.reused(),accepted.actionId(),accepted.generation(),accepted.state(),accepted.result());
    }
    public Snapshot snapshot() {
        return new Snapshot(identity, record == null ? "" : record.getToolCallId(),actionGeneration,
                record == null ? "IDLE" : record.getState().name(), activeTicks, pausedTicks, result);
    }
    public Optional<Snapshot> lookup(String actionId){requireThread();var e=journal.find(body.getUUID(),actionId);return e==null?Optional.empty():Optional.of(new Snapshot(identity,e.actionId(),e.generation(),e.state(),-1,-1,e.result()));}
    public boolean terminal() { return record != null && record.getState().isTerminal(); }
    public void pause() {
        requireThread(); paused = true;
        searchBudget.advance(body.server.getTickCount(),false);
        survival.pause(body);
        if (task != null) task.stop(body, Task.StopReason.PREEMPTED);
        halt();
    }
    public void resume() { requireThread(); paused = false; }
    public void cancel() {
        requireThread();
        if (task != null && !record.getState().isTerminal()) {
            task.stop(body, Task.StopReason.REPLACED);
            record.setState(TaskState.CANCELLED);
            settle();
        }
        halt();
    }
    public void close() {
        requireThread(); cancel(); closed = true;
        FurnaceWork.get(body.server).releaseOwner(body.getUUID());
        searchBudget.invalidate();
        survival.discard(body);
        // Leave an inert authority until upstream removal finishes so reflexes cannot take over.
        CompanionFactory.despawn(body.server, body);
        OWNED.remove(body.getUUID(), this);ownedCount=OWNED.size();
    }
}
