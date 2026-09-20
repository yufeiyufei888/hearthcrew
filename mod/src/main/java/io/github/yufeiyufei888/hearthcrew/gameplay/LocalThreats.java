package io.github.yufeiyufei888.hearthcrew.gameplay;

import io.github.yufeiyufei888.hearthcrew.entity.CompanionEntity;
import java.util.Comparator;
import java.util.List;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.*;

/** Bounded observation only; all reactions remain in the body's single executor. */
public final class LocalThreats {
    private LocalThreats() {}
    public static List<Mob> nearby(CompanionEntity body) {
        return body.level().getEntitiesOfClass(Mob.class, body.getBoundingBox().inflate(16, 8, 16),
                mob -> threat(body, mob)).stream().sorted(Comparator.comparingDouble(body::distanceToSqr)).limit(12).toList();
    }
    private static boolean threat(CompanionEntity body, Mob mob) {
        if (!mob.isAlive() || mob.isRemoved() || body.isAlliedTo(mob) || body.distanceToSqr(mob) > 256) return false;
        if (body.getLastHurtByMob() == mob && body.tickCount - body.getLastHurtByMobTimestamp() < 100) return true;
        // A retained aggro target through a cliff/wall is not immediate danger.
        // Actual recent damage above still preempts work even without sight.
        if (!body.hasLineOfSight(mob)) return false;
        if (mob.getTarget() == body) return true;
        if (!(mob instanceof Enemy)) return false;
        if (mob.getTarget() != null && body.isAlliedTo(mob.getTarget())
                && (mob.getTarget().getHealth() <= 8 || !body.executor().hasOrdinaryWork()
                    || body.executor().arbiter().activeSnapshot().map(a -> a.payload().kind() == io.github.yufeiyufei888.hearthcrew.entity.BodyOrder.Kind.SELF_DEFENCE).orElse(false))) return true;
        // Neutral creatures are not attacked just because they are nearby.
        return body.distanceToSqr(mob) <= 36 && body.hasLineOfSight(mob)
                && !(mob instanceof net.minecraft.world.entity.NeutralMob)
                && (mob instanceof Zombie || mob instanceof AbstractSkeleton || mob instanceof Creeper);
    }
}
