package io.github.yufeiyufei888.hearthcrew.kernel;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class Recovery015Test {
 @Test void driftAndOscillationDoNotCountAsProgressAndReplanOnlyOnce() {
  var progress=new TravelProgress(0,10,3);
  for(int t=1;t<100;t++)assertEquals(TravelProgress.Result.ADVANCING,progress.update(t,10+(t%4)*.3,3+(t%2)*.2,0));
  assertEquals(TravelProgress.Result.REPLAN,progress.update(100,10,3,0));
  assertEquals(TravelProgress.Result.BLOCKED,progress.update(200,10,3,0));
  assertEquals(TravelProgress.Result.BLOCKED,progress.update(220,10,3,0));
 }
 @Test void approachAndHeightProgressExtendDeadlineButPauseDoesNotAdvanceIt() {
  var p=new TravelProgress(0,10,3);assertEquals(TravelProgress.Result.ADVANCING,p.update(99,9,3,0));
  for(int i=0;i<1000;i++)assertEquals(TravelProgress.Result.ADVANCING,p.update(99,9,3,0));
  assertEquals(TravelProgress.Result.ADVANCING,p.update(198,9,2,0));
  assertEquals(TravelProgress.Result.REPLAN,p.update(298,9,2,0));
 }
 @Test void terminalSaveLoadStaysTerminalAndCannotReplayEffects() {
  var epoch=new WorldEpoch(1,1,1);var req=ActionRequest.withoutDeadline(ActionId.of("done"),ActionPriority.PERSONAL,"move",epoch,0);
  for(var state:List.of(ActionState.COMPLETED,ActionState.PARTIAL,ActionState.FAILED,ActionState.CANCELLED,ActionState.EXPIRED)) {
   ActionArbiter<String,String> restored=new ActionArbiter<>(epoch);
   restored.restoreSaved(List.of(req),Map.of(req.id(),new ActionArbiter.SavedOutcome(state,"actual receipt")));
   assertEquals(state,restored.snapshot(req.id()).orElseThrow().state());assertTrue(restored.activeSnapshot().isEmpty());
   assertEquals(ReceiptDecision.IDEMPOTENT_REPLAY,restored.submit(req).decision());assertTrue(restored.activeSnapshot().isEmpty());
  }
 }
 @Test void legacyUnknownRequiresMatchingEvidenceAndNoLeaseIsCreated() {
  var epoch=new WorldEpoch(1,1,1);var req=ActionRequest.withoutDeadline(ActionId.of("old"),ActionPriority.PERSONAL,"select:3",epoch,0);
  ActionArbiter<String,String> a=new ActionArbiter<>(epoch);a.restoreForReconciliation(List.of(req));
  assertEquals(ActionState.RECONCILE_REQUIRED,a.snapshot(req.id()).orElseThrow().state());
  var proof=new ActionArbiter.SavedOutcome(ActionState.COMPLETED,"observed");
  assertFalse(a.restoreHistoricalOutcome(ActionRequest.withoutDeadline(req.id(),req.priority(),"select:0",epoch,0),proof));
  assertTrue(a.restoreHistoricalOutcome(req,proof));assertFalse(a.restoreHistoricalOutcome(req,proof));assertTrue(a.activeSnapshot().isEmpty());
 }
 @Test void oxygenPrioritySuspendsWorkAndPauseFreezesLogicalDeadline() {
  var epoch=new WorldEpoch(1,1,1);ActionArbiter<String,String> a=new ActionArbiter<>(epoch);
  var work=ActionRequest.withoutDeadline(ActionId.of("work"),ActionPriority.PERSONAL,"move",epoch,0);a.submit(work);a.start(work.id());a.checkpoint(work.id(),"position");
  var air=ActionRequest.withoutDeadline(ActionId.of("air"),ActionPriority.SAFETY,"breathe",epoch,0);a.submit(air);
  assertEquals(ActionState.SUSPENDED,a.snapshot(work.id()).orElseThrow().state());
  a.advanceTick(100,true);assertEquals(0,a.gameTick());a.finish(air.id(),ActionState.COMPLETED,"air=300");
  a.advanceTick(101,false);assertEquals(0,a.gameTick());assertEquals(ReceiptDecision.RESUMED,a.resume(work.id(),p->p.equals("position")).decision());
 }
 @Test void placementOwnershipSeparatesCrewPlayerAndUnknownWithoutClearingLegacyProtection() {
  Set<String> protectedBlocks=new HashSet<>();Map<String,String> owners=new HashMap<>();
  PlacementOwnership.placed(protectedBlocks,owners,"crew","1",true);assertFalse(protectedBlocks.contains("crew"));
  PlacementOwnership.placed(protectedBlocks,owners,"player","2",false);assertTrue(protectedBlocks.contains("player"));
  assertFalse(PlacementOwnership.removed(protectedBlocks,owners,"player","player:wrong"));
  assertTrue(PlacementOwnership.removed(protectedBlocks,owners,"player","player:2"));assertFalse(protectedBlocks.contains("player"));
  protectedBlocks.add("legacy");PlacementOwnership.placed(protectedBlocks,owners,"legacy","1",true);
  assertFalse(PlacementOwnership.removed(protectedBlocks,owners,"legacy",null));assertTrue(protectedBlocks.contains("legacy"));
 }
}
