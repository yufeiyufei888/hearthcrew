package io.github.yufeiyufei888.hearthcrew.mixin;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.block.state.BlockState;
/** Preflight the same virtual placement-state method used by vanilla BlockItem.place. */
@Mixin(BlockItem.class)
public interface BlockPlacementAccessor {
 @Invoker("getPlacementState") BlockState hearthcrew$placementState(BlockPlaceContext context);
}
