package io.github.yufeiyufei888.hearthcrew.gameplay;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.Portal;
import net.minecraft.world.level.portal.DimensionTransition;

/** Keeps native portal geometry and placement, with the survival player's entry delay. */
public final class CompanionPortals {
    private CompanionPortals() {}
    public static final Portal NETHER = new Portal() {
        @Override public int getPortalTransitionTime(ServerLevel level, Entity entity) {
            return Math.max(0, level.getGameRules().getInt(GameRules.RULE_PLAYERS_NETHER_PORTAL_DEFAULT_DELAY));
        }
        @Override public DimensionTransition getPortalDestination(ServerLevel level, Entity entity, BlockPos position) {
            return ((Portal) Blocks.NETHER_PORTAL).getPortalDestination(level, entity, position);
        }
        @Override public Transition getLocalTransition() { return Transition.CONFUSION; }
    };
}
