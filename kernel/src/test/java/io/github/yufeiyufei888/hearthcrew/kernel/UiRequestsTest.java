package io.github.yufeiyufei888.hearthcrew.kernel;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class UiRequestsTest {
 @Test void detailAndChatReadsNeverHoldTheCommandSlot(){var g=new UiRequests();var detail=g.begin("detail",0,200);var chat=g.begin("history",0,200);assertNotNull(detail);assertNotNull(chat);assertFalse(g.foregroundPending());assertNotNull(g.begin("command",1,200));assertNull(g.begin("history",1,200));g.finish(chat.id());assertTrue(g.foregroundPending());}
 @Test void statusCannotBlockCommandOrConsumeItsAcknowledgement(){
  var gate=new UiRequests();var status=gate.begin("status",0,200);var command=gate.begin("command",1,200);
  assertNotNull(command);assertNull(gate.begin("command",2,200));
  assertEquals(status,gate.finish(status.id()));assertTrue(gate.foregroundPending());
  assertEquals(command,gate.finish(command.id()));assertFalse(gate.foregroundPending());
 }
 @Test void lateBackgroundViewCannotOverwriteNewerCommandView(){
  var g=new UiRequests();var s=g.begin("status",0,200);var c=g.begin("command",1,200);
  assertTrue(g.applyView(g.finish(c.id())));assertFalse(g.applyView(g.finish(s.id())));
 }
 @Test void urgentStopPreservesPendingCommandAndTimeoutNeverResends(){
  var g=new UiRequests();var c=g.begin("command",0,200);var stop=g.begin("stop",1,200);
  assertNotNull(stop);assertNull(g.begin("stop",2,200));g.finish(stop.id());
  assertEquals(java.util.List.of(c),g.expire(200));assertTrue(g.expire(201).isEmpty());assertFalse(g.foregroundPending());
  assertNull(g.finish(c.id()));g.begin("status",202,200);g.clear();assertNotNull(g.begin("command",203,200));
 }
}
