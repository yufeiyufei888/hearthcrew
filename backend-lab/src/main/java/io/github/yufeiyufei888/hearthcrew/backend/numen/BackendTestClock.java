package io.github.yufeiyufei888.hearthcrew.backend.numen;

/** Test-only pacing; incoming server work may unpark the thread before its deadline. */
public final class BackendTestClock {
    private static long lastTick=Long.MIN_VALUE,nextWallTick;
    public static void pace(long tick){
        if(tick==lastTick)return;lastTick=tick;
        long remaining;while((remaining=nextWallTick-System.nanoTime())>0)java.util.concurrent.locks.LockSupport.parkNanos(remaining);
        nextWallTick=System.nanoTime()+50_000_000L;
    }
}
