package io.github.yufeiyufei888.hearthcrew.gameplay;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.BiPredicate;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.ai.targeting.TargetingConditions;
import net.minecraft.world.Difficulty;
import org.jetbrains.annotations.Nullable;

/**
 * Server-side target selection for a companion that is not a {@code Player}.
 *
 * <p>Vanilla hostile goals commonly use {@code Player.class} and therefore do
 * not discover a custom companion. This class only supplies the missing
 * candidate selection; the caller still owns the target mutation and must run
 * it on the server thread.</p>
 */
public final class CompanionTargeting {
    private CompanionTargeting() {}

    /**
     * Returns whether the mob may acquire this companion while preserving the
     * ordinary combat targeting checks (difficulty, visibility, range,
     * alliance, attackability and attack type).
     */
    public static boolean canAcquire(
            Mob attacker,
            LivingEntity companion,
            @Nullable BiPredicate<Mob, LivingEntity> speciesRule
    ) {
        if (!(attacker instanceof Enemy)
                || attacker == companion
                || !attacker.isAlive()
                || !companion.isAlive()
                || companion.isRemoved()
                || attacker.level().getDifficulty() == Difficulty.PEACEFUL
                || !companion.canBeSeenAsEnemy()) {
            return false;
        }

        if (speciesRule != null && !speciesRule.test(attacker, companion)) {
            return false;
        }

        TargetingConditions conditions = TargetingConditions.forCombat()
                .range(attacker.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.FOLLOW_RANGE));
        return conditions.test(attacker, companion);
    }

    /**
     * Finds the nearest eligible companion. Existing valid targets are never
     * replaced; the caller should invoke this only when it is allowed to
     * acquire a new target.
     */
    public static Optional<LivingEntity> findNearest(
            Mob attacker,
            List<? extends LivingEntity> companions,
            @Nullable BiPredicate<Mob, LivingEntity> speciesRule
    ) {
        Objects.requireNonNull(attacker, "attacker");
        Objects.requireNonNull(companions, "companions");
        if (!(attacker instanceof Enemy) || companions.isEmpty() || !mayAcquireNewTarget(attacker)) {
            return Optional.empty();
        }

        TargetingConditions conditions = TargetingConditions.forCombat()
                .range(attacker.getAttributeValue(net.minecraft.world.entity.ai.attributes.Attributes.FOLLOW_RANGE))
                .selector(candidate -> canAcquire(attacker, candidate, speciesRule));
        return Optional.ofNullable(attacker.level().getNearestEntity(
                companions,
                conditions,
                attacker,
                attacker.getX(),
                attacker.getEyeY(),
                attacker.getZ()
        ));
    }

    /**
     * Keeps an existing vanilla target intact when it is still a valid combat
     * target. A null, dead or no-longer-attackable target permits acquisition.
     */
    public static boolean mayAcquireNewTarget(Mob attacker) {
        LivingEntity current = attacker.getTarget();
        return current == null
                || !current.isAlive()
                || current.isRemoved()
                || !attacker.canAttack(current)
                || !attacker.canAttackType(current.getType());
    }
}
