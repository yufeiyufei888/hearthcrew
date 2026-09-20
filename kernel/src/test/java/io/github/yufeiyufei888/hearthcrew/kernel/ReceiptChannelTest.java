package io.github.yufeiyufei888.hearthcrew.kernel;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class ReceiptChannelTest {
 @Test void restoredTerminalFloodNeverEntersRealtimeButLiveAndUnknownRemain(){
  for(int i=0;i<6144;i++)assertFalse(ReceiptChannel.realtime(ActionState.COMPLETED,"saved outcome restored: completed"));
  assertTrue(ReceiptChannel.realtime(ActionState.COMPLETED,"native pickup verified"));
  assertTrue(ReceiptChannel.realtime(ActionState.RECONCILE_REQUIRED,"restored action requires reconciliation"));
 }
 @Test void identityIsIndependentOfConnectionAndSeparatesBodyAndWorld(){
  assertEquals(ReceiptChannel.identity("w","b",1,"a",2),ReceiptChannel.identity("w","b",1,"a",2));
  assertNotEquals(ReceiptChannel.identity("w","b",1,"a",2),ReceiptChannel.identity("w","b",2,"a",2));
  assertNotEquals(ReceiptChannel.identity("w","b",1,"a",2),ReceiptChannel.identity("other","b",1,"a",2));
 }
}
