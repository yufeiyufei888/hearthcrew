package io.github.yufeiyufei888.hearthcrew.backend.numen;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.FallingBlock;

/** Shared conservative checks for planned and actual excavation, using no world writes. */
public final class TerrainSafety {
    private TerrainSafety() {}
    public static boolean dryStableCell(BlockGetter level,BlockPos pos) {
        var state=level.getBlockState(pos);
        if(state.hasBlockEntity()||!state.getFluidState().isEmpty()||state.getBlock() instanceof FallingBlock)return false;
        for(var direction:Direction.values()) {
            var adjacent=level.getBlockState(pos.relative(direction));
            if(!adjacent.getFluidState().isEmpty())return false;
            if(direction==Direction.UP&&adjacent.getBlock() instanceof FallingBlock)return false;
        }
        return true;
    }
}
