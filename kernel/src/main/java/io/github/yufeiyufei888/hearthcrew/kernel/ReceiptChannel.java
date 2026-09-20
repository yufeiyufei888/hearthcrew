package io.github.yufeiyufei888.hearthcrew.kernel;
/** Restored evidence is queryable history, never a newly completed body operation. */
public final class ReceiptChannel {
 private ReceiptChannel(){}
 public static boolean historical(String message){return message.startsWith("historical outcome restored:")||message.startsWith("saved outcome restored:");}
 public static boolean realtime(ActionState state,String message){return !state.terminal()||!historical(message);}
 public static String identity(String world,String body,long generation,String action,long sequence){return "action:"+world+":"+body+":"+generation+":"+action+":"+sequence;}
}
