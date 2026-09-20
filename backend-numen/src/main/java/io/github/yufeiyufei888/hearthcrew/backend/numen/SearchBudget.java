package io.github.yufeiyufei888.hearthcrew.backend.numen;

/** Shared by all frozen searches of one body. Workers wait; the game thread never waits for them. */
public final class SearchBudget {
    private long tick=Long.MIN_VALUE;
    private int credits,used,maximum;
    private boolean enabled=true,alive=true;
    private int searches,nodes;
    private Thread owner;
    private String outcome="IDLE";
    private boolean boundary,empty;
    public synchronized void boundary(){if(owner==Thread.currentThread())boundary=true;}
    public synchronized void frontier(boolean empty){if(owner==Thread.currentThread())this.empty=empty;}
    public synchronized void started(){owner=Thread.currentThread();searches=1;nodes=0;boundary=empty=false;outcome="SEARCHING";notifyAll();}
    public synchronized void finished(boolean found){if(owner!=Thread.currentThread())return;searches=0;outcome=found?"PATH_FOUND":nodes>=16384?"SEARCH_BUDGET_EXHAUSTED":boundary?"UNLOADED_BOUNDARY":empty?"CONFIRMED_BLOCKED":"SEARCH_TIME_BUDGET_EXHAUSTED";}
    public synchronized String outcome(){return outcome;}
    public synchronized boolean searching(){return searches>0;}
    public synchronized void advance(long nextTick,boolean enabled) {
        this.enabled=enabled;
        if(tick!=nextTick){tick=nextTick;credits=64;used=0;}
        notifyAll();
    }
    public synchronized boolean claim() {
        while(alive&&(owner==null||owner==Thread.currentThread())&&(!enabled||credits==0)) {
            try {wait(20);}catch(InterruptedException interrupted){Thread.currentThread().interrupt();return false;}
        }
        if(!alive||owner!=null&&owner!=Thread.currentThread())return false;
        credits--;nodes++;maximum=Math.max(maximum,++used);return true;
    }
    public synchronized void invalidate(){alive=false;notifyAll();}
    public synchronized int maximum(){return maximum;}
}
