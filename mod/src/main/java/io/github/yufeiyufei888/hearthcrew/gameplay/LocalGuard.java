package io.github.yufeiyufei888.hearthcrew.gameplay;

import io.github.yufeiyufei888.hearthcrew.entity.BodyExecutor;
import io.github.yufeiyufei888.hearthcrew.entity.CompanionEntity;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;

/**
 * Server-side, nearby-only protection of a known owner.
 *
 * <p>This class only observes the owner's vanilla last-hurt-by-mob state and
 * asks the body's single executor for a GUARD lease. It never moves, attacks,
 * or changes a world directly.</p>
 */
public final class LocalGuard {
    public static final double OWNER_RANGE = 32.0D;
    private static final double OWNER_RANGE_SQUARED = OWNER_RANGE * OWNER_RANGE;

    private LocalGuard() {}

    /** Called from the companion's server tick, before the executor tick. */
    public static void tick(CompanionEntity body) {
        if (!(body.level() instanceof ServerLevel level) || body.ownerId() == null || !body.isAlive()) return;

        BodyExecutor executor = body.executor();
        if (executor.stopped() || executor.paused()) return;

        ServerPlayer owner = level.getServer().getPlayerList().getPlayer(body.ownerId());
        LivingEntity threat = validThreat(body, owner);
        if (threat != null && (!executor.hasOrdinaryWork() || owner.getHealth() <= 8)) {
            executor.ensureGuard(body.ownerId(), Integer.toUnsignedLong(owner.getId()), threat.getUUID(), owner.getLastHurtByMobTimestamp());
        } else if (executor.isGuarding()) {
            executor.releaseGuard("owner threat cleared or owner unavailable");
        }
    }

    private static LivingEntity validThreat(CompanionEntity body, ServerPlayer owner) {
        if (owner == null || !owner.isAlive() || owner.isRemoved() || owner.level() != body.level() || owner.distanceToSqr(body) > OWNER_RANGE_SQUARED) return null;
        if (owner.tickCount - owner.getLastHurtByMobTimestamp() > 100) return null;
        LivingEntity attacker = owner.getLastHurtByMob();
        if (!(attacker instanceof Mob) || !(attacker instanceof Enemy)
                || attacker == body || attacker.isRemoved() || !attacker.isAlive()
                || attacker.level() != body.level()
                || attacker.distanceToSqr(owner) > OWNER_RANGE_SQUARED
                || body.isAlliedTo(attacker)) return null;
        return attacker;
    }
}
