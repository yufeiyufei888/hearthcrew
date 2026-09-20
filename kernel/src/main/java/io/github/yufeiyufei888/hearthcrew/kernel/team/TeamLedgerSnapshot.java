package io.github.yufeiyufei888.hearthcrew.kernel.team;

import io.github.yufeiyufei888.hearthcrew.kernel.WorldEpoch;
import java.util.List;

/** Durable value snapshot. Restoring active work requires reconciliation. */
public record TeamLedgerSnapshot(
        WorldEpoch epoch,
        long gameTick,
        List<TeamTaskSnapshot> tasks,
        List<TeamSettlementSnapshot> settlements) {
    public TeamLedgerSnapshot {
        if (epoch == null) throw new NullPointerException("epoch");
        if (gameTick < 0) throw new IllegalArgumentException("game tick must be non-negative");
        tasks = List.copyOf(tasks == null ? List.of() : tasks);
        settlements = List.copyOf(settlements == null ? List.of() : settlements);
    }
}
