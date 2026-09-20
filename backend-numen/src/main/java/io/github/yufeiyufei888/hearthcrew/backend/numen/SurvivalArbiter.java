package io.github.yufeiyufei888.hearthcrew.backend.numen;

import com.dwinovo.numen.core.task.chain.BreathChain;
import com.dwinovo.numen.core.task.chain.MobDefenseChain;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.Task;

/** Delegates upstream reactions only while the owning backend holds execution authority. */
final class SurvivalArbiter {
    private Task breath = new WaterRecovery(), defense = new MobDefenseChain(), active;
    private long ticks, switches;

    Task select(NumenPlayer body) {
        return breath.canRun(body) ? breath : defense.canRun(body) ? defense : null;
    }
    boolean change(NumenPlayer body, Task next) {
        if (active == next) return false;
        if (active != null) active.stop(body, Task.StopReason.PREEMPTED);
        active = next;
        switches++;
        return true;
    }
    void tick(NumenPlayer body) { ticks++; active.tick(body); }
    boolean active() { return active != null; }
    String name() { return active == null ? "none" : active.name(); }
    long ticks() { return ticks; }
    long switches() { return switches; }
    void pause(NumenPlayer body) {
        if (active != null) active.stop(body, Task.StopReason.PREEMPTED);
    }
    void discard(NumenPlayer body) {
        if (active != null) active.stop(body, Task.StopReason.BODY_GONE);
        active = null;
        breath = new WaterRecovery(); defense = new MobDefenseChain();
    }
}
