package io.github.yufeiyufei888.hearthcrew.backend.numen;

/** Read-only fixture evidence; never linked into the production artifact. */
public final class BackendTestFacts {
    public static long usablePublicFacilities(net.minecraft.server.level.ServerLevel level){
        return BackendJournal.get(level.getServer()).publicPositions(level.dimension()).stream()
            .filter(p->level.hasChunkAt(p)&&(level.getBlockState(p).is(net.minecraft.world.level.block.Blocks.CRAFTING_TABLE)
                ||level.getBlockState(p).is(net.minecraft.world.level.block.Blocks.FURNACE))).count();
    }
}
