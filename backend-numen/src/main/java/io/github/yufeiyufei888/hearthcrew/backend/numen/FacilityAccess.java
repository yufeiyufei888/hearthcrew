package io.github.yufeiyufei888.hearthcrew.backend.numen;

import net.minecraft.world.level.block.*;
import net.minecraft.world.level.block.state.BlockState;
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent;

/** Unknown containers/workstations are private; only a persisted publication grants use. */
final class FacilityAccess {
    static String denial(net.minecraft.server.level.ServerLevel level,net.minecraft.core.BlockPos p) {
        if(!level.hasChunkAt(p))return "FACILITY_NOT_LOADED";
        var state=level.getBlockState(p);if(!isFacility(state))return "FACILITY_MISSING";
        var journal=BackendJournal.get(level.getServer());
        if(!journal.isPublic(level.dimension(),p))return "FACILITY_PRIVATE";
        // A native double-chest menu includes both blocks. Publishing one half cannot
        // authorize reading/withdrawing from the adjacent private half.
        if(state.getBlock() instanceof ChestBlock&&state.getValue(ChestBlock.TYPE)!=net.minecraft.world.level.block.state.properties.ChestType.SINGLE) {
            var other=p.relative(ChestBlock.getConnectedDirection(state));
            if(!level.hasChunkAt(other))return "FACILITY_PAIR_NOT_LOADED";
            var pair=level.getBlockState(other);
            if(pair.getBlock()!=state.getBlock()||!pair.hasProperty(ChestBlock.TYPE)
                ||pair.getValue(ChestBlock.TYPE)==net.minecraft.world.level.block.state.properties.ChestType.SINGLE
                ||!other.relative(ChestBlock.getConnectedDirection(pair)).equals(p))return "FACILITY_PAIR_CHANGED";
            if(!journal.isPublic(level.dimension(),other))return "FACILITY_PAIR_PRIVATE";
        }
        return "";
    }
    static boolean usableNow(com.dwinovo.numen.entity.NumenPlayer body,net.minecraft.core.BlockPos p) {
        if(!body.onGround()||!denial(body.serverLevel(),p).isEmpty())return false;
        var center=net.minecraft.world.phys.Vec3.atCenterOf(p);
        if(body.getEyePosition().distanceToSqr(center)>4.5*4.5)return false;
        var ray=body.level().clip(new net.minecraft.world.level.ClipContext(body.getEyePosition(),center,
            net.minecraft.world.level.ClipContext.Block.OUTLINE,net.minecraft.world.level.ClipContext.Fluid.NONE,body));
        return ray.getType()==net.minecraft.world.phys.HitResult.Type.BLOCK&&ray.getBlockPos().equals(p);
    }
    static boolean isFacility(BlockState state) {
        return state.hasBlockEntity()||state.getBlock() instanceof CraftingTableBlock;
    }
    static void use(PlayerInteractEvent.RightClickBlock event) {
        var player=event.getEntity();if(!NumenBackend.owns(player.getUUID()))return;
        var state=event.getLevel().getBlockState(event.getPos());
        if(!isFacility(state))return;
        if(!NumenBackend.mayUseFacility(player,event.getPos())) {
            event.setCanceled(true);event.setCancellationResult(net.minecraft.world.InteractionResult.FAIL);
        }
    }
}
