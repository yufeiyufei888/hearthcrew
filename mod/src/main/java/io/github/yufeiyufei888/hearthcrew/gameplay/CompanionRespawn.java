package io.github.yufeiyufei888.hearthcrew.gameplay;

import java.util.Optional;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.PlayerRespawnLogic;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.vehicle.DismountHelper;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.Level;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.phys.Vec3;
import org.jetbrains.annotations.Nullable;

/**
 * Player-style spawn-block selection for a custom companion body.
 *
 * <p>Selection is read-only. Anchor charge consumption is a separate,
 * explicit operation so the body lifecycle can make it exactly once per
 * death generation.</p>
 */
public final class CompanionRespawn {
    private CompanionRespawn() {}

    public enum Source {
        BED,
        RESPAWN_ANCHOR,
        WORLD_SPAWN
    }

    public record Request(
            ResourceKey<Level> dimension,
            @Nullable BlockPos position,
            float angle,
            boolean forced
    ) {}

    public record Choice(
            ServerLevel level,
            Vec3 position,
            float yaw,
            Source source,
            @Nullable BlockPos spawnBlock,
            boolean consumesAnchor,
            int anchorChargeAtSelection
    ) {}

    /**
     * Resolves a valid bed or charged anchor. Any missing, invalid or
     * dimension-incompatible spawn block follows vanilla's missing-respawn
     * behavior and falls back to a safe Overworld spawn.
     */
    public static Optional<Choice> resolve(
            MinecraftServer server,
            EntityType<?> bodyType,
            Request request
    ) {
        ServerLevel configured = server.getLevel(request.dimension());
        if (configured != null && request.position() != null) {
            Optional<Choice> blockChoice = resolveSpawnBlock(configured, bodyType, request);
            if (blockChoice.isPresent()) {
                return blockChoice;
            }
        }
        return resolveWorldSpawn(server.overworld(), bodyType);
    }

    /**
     * Consumes exactly one respawn-anchor charge for a choice selected with
     * {@link Choice#consumesAnchor()}. The caller must call this once only,
     * after it has committed the corresponding respawn generation.
     */
    public static boolean consumeAnchor(Choice choice) {
        if (choice.source() != Source.RESPAWN_ANCHOR
                || !choice.consumesAnchor()
                || choice.spawnBlock() == null) {
            return false;
        }
        BlockPos pos = choice.spawnBlock();
        BlockState state = choice.level().getBlockState(pos);
        if (!state.is(Blocks.RESPAWN_ANCHOR)) {
            return false;
        }
        int charge = state.getValue(RespawnAnchorBlock.CHARGE);
        if (charge <= RespawnAnchorBlock.MIN_CHARGES || charge != choice.anchorChargeAtSelection()) {
            return false;
        }
        choice.level().setBlock(
                pos,
                state.setValue(RespawnAnchorBlock.CHARGE, charge - 1),
                3
        );
        return true;
    }

    private static Optional<Choice> resolveSpawnBlock(
            ServerLevel level,
            EntityType<?> bodyType,
            Request request
    ) {
        BlockPos saved = request.position();
        if (saved == null) {
            return Optional.empty();
        }
        BlockState state = level.getBlockState(saved);
        if (state.getBlock() instanceof RespawnAnchorBlock
                && RespawnAnchorBlock.canSetSpawn(level)
                && (request.forced() || state.getValue(RespawnAnchorBlock.CHARGE) > RespawnAnchorBlock.MIN_CHARGES)) {
            return RespawnAnchorBlock.findStandUpPosition(bodyType, level, saved)
                    .map(pos -> new Choice(
                            level,
                            pos,
                            request.angle(),
                            Source.RESPAWN_ANCHOR,
                            saved,
                            !request.forced(),
                            state.getValue(RespawnAnchorBlock.CHARGE)
                    ));
        }

        BlockPos bedHead = normalizeBedHead(level, saved);
        BlockState bedState = level.getBlockState(bedHead);
        if (bedState.getBlock() instanceof BedBlock && BedBlock.canSetSpawn(level)) {
            return BedBlock.findStandUpPosition(
                            bodyType,
                            level,
                            bedHead,
                            bedState.getValue(BedBlock.FACING),
                            request.angle()
                    )
                    .map(pos -> new Choice(level, pos, request.angle(), Source.BED, bedHead, false, 0));
        }
        return Optional.empty();
    }

    private static Optional<Choice> resolveWorldSpawn(ServerLevel overworld, EntityType<?> bodyType) {
        BlockPos shared = overworld.getSharedSpawnPos();
        BlockPos safe = RespawnLogicAccess.exposeOverworldRespawnPos(overworld, shared.getX(), shared.getZ());
        if (safe == null) {
            safe = PlayerRespawnLogic.getSpawnPosInChunk(overworld, new ChunkPos(shared));
        }
        if (safe == null) {
            return Optional.empty();
        }
        Vec3 position = DismountHelper.findSafeDismountLocation(bodyType, overworld, safe, true);
        return position == null
                ? Optional.empty()
                : Optional.of(new Choice(overworld, position, overworld.getSharedSpawnAngle(), Source.WORLD_SPAWN, null, false, 0));
    }

    private static BlockPos normalizeBedHead(ServerLevel level, BlockPos saved) {
        BlockState state = level.getBlockState(saved);
        if (state.getBlock() instanceof BedBlock
                && state.getValue(BedBlock.PART) == BedPart.FOOT) {
            BlockPos head = saved.relative(state.getValue(BedBlock.FACING));
            BlockState headState = level.getBlockState(head);
            if (headState.getBlock() instanceof BedBlock
                    && headState.getValue(BedBlock.PART) == BedPart.HEAD) {
                return head;
            }
        }
        return saved;
    }

    /** Protected vanilla world-spawn helper exposed without duplicating its terrain scan. */
    private static final class RespawnLogicAccess extends PlayerRespawnLogic {
        private static BlockPos exposeOverworldRespawnPos(ServerLevel level, int x, int z) {
            return getOverworldRespawnPos(level, x, z);
        }
    }
}
