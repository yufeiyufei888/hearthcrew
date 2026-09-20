package io.github.yufeiyufei888.hearthcrew.gameplay;

import io.github.yufeiyufei888.hearthcrew.entity.CompanionEntity;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.SimpleContainer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

/** Server-thread-only transactional crafting and direct inventory delivery. */
public final class RecipeActions {
    private static final int PORTABLE_WIDTH = 2;
    private static final int PORTABLE_HEIGHT = 2;
    private static final int STATION_WIDTH = 3;
    private static final int STATION_HEIGHT = 3;
    private static final int MAX_REPETITIONS = 64;
    private static final int MAX_SEARCH_STEPS = 10_000;
    // A three-block radius is too tight for the normal two-body handoff
    // position (diagonal neighbors are 3.6 blocks apart).  Keep the
    // server-side transfer local and line-of-sight checked, but allow the
    // standard four-block interaction envelope.
    private static final double TRANSFER_RANGE_SQR = 16.0D;

    private RecipeActions() {}

    public record CraftResult(int requestedRepetitions, int completedRepetitions,
                              int producedCount, @Nullable ResourceLocation outputId,
                              String reason, boolean completed) {}

    public record TransferResult(int requestedCount, int movedCount,
                                 String reason, boolean completed) {}

    /**
     * Crafts a real registered crafting recipe from the body inventory. A null station
     * permits a 2x2 hand grid; a station must be a reachable real crafting table and
     * permits a 3x3 grid. No movement is performed here.
     */
    public static CraftResult craft(CompanionEntity body, ResourceLocation recipeId,
                                    int repetitions, @Nullable BlockPos station) {
        Objects.requireNonNull(body, "body");
        Objects.requireNonNull(recipeId, "recipeId");
        if (!(body.level() instanceof ServerLevel level)) throw new IllegalStateException("craft must run on a server level");
        requireServerThread(level);
        if (repetitions <= 0) return new CraftResult(repetitions, 0, 0, null, "repetitions must be positive", false);
        if (repetitions > MAX_REPETITIONS) return new CraftResult(repetitions, 0, 0, null,
                "repetitions exceed the per-call limit of " + MAX_REPETITIONS, false);
        if (!body.isAlive()) return new CraftResult(repetitions, 0, 0, null, "body is not alive", false);
        if (station != null && !canReachStation(body, station)) return new CraftResult(repetitions, 0, 0, null,
                "body cannot reach the real crafting table", false);

        var holder = level.getRecipeManager().byKey(recipeId);
        if (holder.isEmpty() || !(holder.get().value() instanceof CraftingRecipe recipe)) {
            return new CraftResult(repetitions, 0, 0, null, "unknown or non-crafting recipe", false);
        }
        int width = station == null ? PORTABLE_WIDTH : STATION_WIDTH;
        int height = station == null ? PORTABLE_HEIGHT : STATION_HEIGHT;
        if (!recipe.canCraftInDimensions(width, height)) return new CraftResult(repetitions, 0, 0, null,
                station == null ? "recipe requires a real crafting table" : "recipe does not fit a 3x3 grid", false);
        if (recipe.getIngredients().isEmpty() || recipe.isIncomplete()) {
            return new CraftResult(repetitions, 0, 0, null, "recipe has no usable ingredients", false);
        }

        ResourceLocation outputId = BuiltInRegistries.ITEM.getKey(recipe.getResultItem(level.registryAccess()).getItem());
        int completed = 0;
        int produced = 0;
        String reason = "completed";
        for (; completed < repetitions; completed++) {
            CraftPlan plan = makePlan(level, body.inventory(), recipe, width, height);
            if (plan == null) {
                reason = "missing ingredients or recipe did not match the real crafting grid";
                break;
            }
            if (!commitCraft(body, plan, station)) {
                reason = "inventory has no capacity for output or returned containers";
                break;
            }
            produced += plan.output().getCount();
        }
        boolean all = completed == repetitions;
        if (all) reason = "completed";
        return new CraftResult(repetitions, completed, produced, outputId, reason, all);
    }

    /** Returns true when a real table is within four blocks of the eye and not occluded. */
    public static boolean canReachStation(CompanionEntity body, BlockPos station) {
        if (!body.level().getBlockState(station).is(Blocks.CRAFTING_TABLE)) return false;
        return canReachStationFrom(body,station,body.getEyePosition());
    }
    public static boolean canReachStationFrom(CompanionEntity body,BlockPos station,Vec3 eye){
        AABB table = new AABB(station);
        if (table.distanceToSqr(eye) > 16.0D) return false;
        Vec3 center = table.getCenter();
        var hit = body.level().clip(new ClipContext(eye, center, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, body));
        return hit.getType() == net.minecraft.world.phys.HitResult.Type.BLOCK && hit.getBlockPos().equals(station);
    }

    /**
     * Transfers real stacks between a companion or a ServerPlayer. The player side is
     * deliberately restricted to its 36 ordinary inventory slots; armor and offhand
     * are never used as delivery storage. Both entities must be alive, nearby and visible.
     */
    public static TransferResult transfer(ServerLevel level, UUID sourceId, UUID targetId,
                                          ResourceLocation itemId, int count) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(targetId, "targetId");
        Objects.requireNonNull(itemId, "itemId");
        requireServerThread(level);
        if (count <= 0) return new TransferResult(count, 0, "count must be positive", false);
        if (sourceId.equals(targetId)) return new TransferResult(count, 0, "source and target are the same", false);

        Entity sourceEntity = level.getEntity(sourceId);
        Entity targetEntity = level.getEntity(targetId);
        if (sourceEntity == null || targetEntity == null || !sourceEntity.isAlive() || !targetEntity.isAlive()) {
            return new TransferResult(count, 0, "source or target is unavailable", false);
        }
        InventoryAccess source = inventoryOf(sourceEntity);
        InventoryAccess target = inventoryOf(targetEntity);
        if (source == null || target == null) return new TransferResult(count, 0, "entity has no supported inventory", false);
        if (sourceEntity.distanceToSqr(targetEntity) > TRANSFER_RANGE_SQR) {
            return new TransferResult(count, 0, "source and target are too far apart", false);
        }
        if (sourceEntity instanceof LivingEntity livingSource && targetEntity instanceof LivingEntity livingTarget
                && !livingSource.hasLineOfSight(livingTarget)) {
            return new TransferResult(count, 0, "source and target do not have line of sight", false);
        }
        Item item = BuiltInRegistries.ITEM.getOptional(itemId).orElse(null);
        if (item == null || item == Items.AIR) return new TransferResult(count, 0, "unknown item", false);

        int sourceAvailable = countItem(source, item);
        List<Move> moves = planTransfer(source, target, item, count);
        if (moves.isEmpty()) return new TransferResult(count, 0,
                sourceAvailable == 0 ? "source has no requested item" : "target inventory is full", false);
        ItemStack[] sourceSnapshot = snapshot(source);
        ItemStack[] targetSnapshot = snapshot(target);
        try {
            int moved = 0;
            for (Move move : moves) {
                ItemStack removed = source.container().removeItem(move.slot(), move.amount());
                if (removed.getCount() != move.amount() || !removed.is(item)) throw new IllegalStateException("source changed during transfer");
                ItemStack remainder = addTo(target, removed);
                if (!remainder.isEmpty()) throw new IllegalStateException("target changed during transfer");
                moved += removed.getCount();
            }
            boolean complete = moved == count;
            String reason = complete ? "completed" : (sourceAvailable < count ? "source quantity insufficient" : "target capacity limited");
            return new TransferResult(count, moved, reason, complete);
        } catch (RuntimeException failure) {
            restore(source, sourceSnapshot);
            restore(target, targetSnapshot);
            return new TransferResult(count, 0, "transfer rolled back: " + failure.getMessage(), false);
        }
    }

    /** Read-only exact allocation, including recipe remainders and output capacity. */
    public static java.util.Map<String,Object> assess(ServerPlayer body,CraftingRecipe recipe,int repetitions){
        var copy=new SimpleContainer(36);for(int i=0;i<36;i++)copy.setItem(i,body.getInventory().getItem(i).copy());
        var allocation=new java.util.LinkedHashMap<String,Integer>();int complete=0;boolean capacity=true;
        for(int i=0;i<repetitions;i++){
            var plan=makePlan(body.serverLevel(),copy,recipe,3,3);if(plan==null)break;
            for(var use:plan.uses()){var stack=copy.getItem(use.slot());allocation.merge(net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(),use.amount(),Integer::sum);stack.shrink(use.amount());}
            if(!copy.addItem(plan.output().copy()).isEmpty()){capacity=false;break;}
            for(var rest:plan.remaining())if(!rest.isEmpty()&&!copy.addItem(rest.copy()).isEmpty())capacity=false;
            if(!capacity)break;complete++;
        }
        var gaps=new java.util.ArrayList<java.util.Map<String,Object>>();
        if(complete<repetitions&&capacity){var available=new int[36];for(int i=0;i<36;i++)available[i]=copy.getItem(i).getCount();
            for(var ingredient:recipe.getIngredients()){if(ingredient.isEmpty())continue;int needed=repetitions-complete;
                for(int slot=0;slot<36&&needed>0;slot++)if(ingredient.test(copy.getItem(slot))){int used=Math.min(needed,available[slot]);needed-=used;available[slot]-=used;}
                if(needed>0)gaps.add(java.util.Map.of("alternatives",java.util.Arrays.stream(ingredient.getItems()).map(x->BuiltInRegistries.ITEM.getKey(x.getItem()).toString()).toList(),"missing",needed));}}
        return java.util.Map.of("materialsReady",complete==repetitions,"completedRepetitionsPossible",complete,"requestedRepetitions",repetitions,"allocation",allocation,"materialGaps",gaps,"outputCapacity",capacity,"allocationBasis","exact recipe matching; gaps are conservative alternative ingredient groups");
    }
    private static void requireServerThread(ServerLevel level) {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("inventory action must run on the server thread");
    }

    @Nullable
    private static InventoryAccess inventoryOf(Entity entity) {
        if (entity instanceof CompanionEntity companion) return new InventoryAccess(companion.inventory(), companion.inventory().getContainerSize());
        if (entity instanceof ServerPlayer player) return new InventoryAccess(player.getInventory(), 36);
        return null;
    }

    private static int countItem(InventoryAccess inventory, Item item) {
        int total = 0;
        for (int slot = 0; slot < inventory.slots(); slot++) if (inventory.container().getItem(slot).is(item)) total += inventory.container().getItem(slot).getCount();
        return total;
    }

    @Nullable
    private static CraftPlan makePlan(ServerLevel level, SimpleContainer inventory,
                                      CraftingRecipe recipe, int width, int height) {
        List<Ingredient> ingredients = recipe.getIngredients();
        if (ingredients.size() > width * height) return null;
        ItemStack[] available = new ItemStack[inventory.getContainerSize()];
        for (int i = 0; i < available.length; i++) available[i] = inventory.getItem(i).copy();
        List<ItemStack> grid = new ArrayList<>(width * height);
        for (int i = 0; i < width * height; i++) grid.add(ItemStack.EMPTY);
        List<Use> uses = new ArrayList<>();
        int shapedWidth = recipe instanceof ShapedRecipe shaped ? shaped.getWidth() : 0;
        if (!assignIngredients(ingredients, 0, grid, available, uses, width, shapedWidth, new SearchBudget())) return null;
        CraftingInput input = CraftingInput.of(width, height, grid);
        if (!recipe.matches(input, level)) return null;
        ItemStack output = recipe.assemble(input, level.registryAccess());
        if (output.isEmpty() || output.getCount() <= 0) return null;
        return new CraftPlan(uses, output.copy(), recipe.getRemainingItems(input),grid);
    }

    private static boolean assignIngredients(List<Ingredient> ingredients, int index,
                                             List<ItemStack> grid, ItemStack[] available,
                                             List<Use> uses, int gridWidth, int shapedWidth,
                                             SearchBudget budget) {
        if (!budget.consume()) return false;
        if (index == ingredients.size()) return true;
        Ingredient ingredient = ingredients.get(index);
        if (ingredient.isEmpty()) return assignIngredients(ingredients, index + 1, grid, available, uses, gridWidth, shapedWidth, budget);
        int cell = shapedWidth > 0 ? (index % shapedWidth) + (index / shapedWidth) * gridWidth : index;
        if (cell < 0 || cell >= grid.size()) return false;
        for (int slot = 0; slot < available.length; slot++) {
            ItemStack stack = available[slot];
            if (stack.isEmpty() || !ingredient.test(stack)) continue;
            grid.set(cell, stack.copyWithCount(1));
            stack.shrink(1);
            uses.add(new Use(slot, 1));
            if (assignIngredients(ingredients, index + 1, grid, available, uses, gridWidth, shapedWidth, budget)) return true;
            uses.remove(uses.size() - 1);
            stack.grow(1);
            grid.set(cell, ItemStack.EMPTY);
        }
        return false;
    }

    private static boolean commitCraft(CompanionEntity body,CraftPlan plan,BlockPos station) {
        var inventory=body.inventory();SimpleContainer simulation=copyContainer(inventory,inventory.getContainerSize());
        for(Use use:plan.uses()){var stack=simulation.getItem(use.slot());if(stack.getCount()<use.amount())return false;stack.shrink(use.amount());}
        if(!simulation.addItem(plan.output().copy()).isEmpty())return false;
        for(var remainder:plan.remaining())if(!remainder.isEmpty()&&!simulation.addItem(remainder.copy()).isEmpty())return false;
        if(station!=null){var provider=body.level().getBlockState(station).getMenuProvider(body.level(),station);if(provider==null||body.openMenu(provider).isEmpty())return false;}
        var menu=station==null?body.inventoryMenu:body.containerMenu;
        if(!(menu instanceof net.minecraft.world.inventory.CraftingMenu || menu instanceof net.minecraft.world.inventory.InventoryMenu))return false;
        try{
            for(int i=0;i<plan.grid().size();i++)if(!menu.getSlot(i+1).getItem().isEmpty())return false;
            for(Use use:plan.uses())inventory.removeItem(use.slot(),use.amount());
            for(int i=0;i<plan.grid().size();i++)menu.getSlot(i+1).set(plan.grid().get(i).copy());
            var slot=menu.getSlot(0);var actual=slot.getItem();
            if(!ItemStack.matches(actual,plan.output())||!slot.mayPickup(body))return false;
            var taken=slot.remove(actual.getCount());slot.onTake(body,taken);
            var leftover=inventory.addItem(taken);if(!leftover.isEmpty()){body.drop(leftover,false);throw new IllegalStateException("craft output changed capacity after native event; reconcile required");}
            inventory.setChanged();return true;
        }finally{if(station!=null)body.closeContainer();else body.inventoryMenu.removed(body);}
    }

    private static SimpleContainer copyContainer(Container source, int slots) {
        SimpleContainer copy = new SimpleContainer(slots);
        for (int i = 0; i < slots; i++) copy.setItem(i, source.getItem(i).copy());
        return copy;
    }

    private static ItemStack[] snapshot(InventoryAccess access) {
        ItemStack[] snapshot = new ItemStack[access.slots()];
        for (int i = 0; i < snapshot.length; i++) snapshot[i] = access.container().getItem(i).copy();
        return snapshot;
    }

    private static void restore(InventoryAccess access, ItemStack[] snapshot) {
        for (int i = 0; i < snapshot.length; i++) access.container().setItem(i, snapshot[i].copy());
        access.container().setChanged();
    }

    private static ItemStack addTo(InventoryAccess access, ItemStack stack) {
        Container container = access.container();
        if (container instanceof SimpleContainer simple && access.slots() == simple.getContainerSize()) return simple.addItem(stack);
        ItemStack remaining = stack.copy();
        for (int i = 0; i < access.slots() && !remaining.isEmpty(); i++) {
            ItemStack current = container.getItem(i);
            if (!current.isEmpty() && ItemStack.isSameItemSameComponents(current, remaining)) {
                int room = Math.min(container.getMaxStackSize(current), current.getMaxStackSize()) - current.getCount();
                int moved = Math.min(room, remaining.getCount());
                if (moved > 0) { current.grow(moved); remaining.shrink(moved); container.setChanged(); }
            }
        }
        for (int i = 0; i < access.slots() && !remaining.isEmpty(); i++) {
            if (!container.getItem(i).isEmpty() || !container.canPlaceItem(i, remaining)) continue;
            int moved = Math.min(remaining.getCount(), Math.min(container.getMaxStackSize(remaining), remaining.getMaxStackSize()));
            container.setItem(i, remaining.split(moved));
        }
        return remaining;
    }

    private static List<Move> planTransfer(InventoryAccess source, InventoryAccess target, Item item, int requested) {
        InventoryAccess simulatedTarget = new InventoryAccess(copyContainer(target.container(), target.slots()), target.slots());
        List<Move> moves = new ArrayList<>();
        int left = requested;
        for (int slot = 0; slot < source.slots() && left > 0; slot++) {
            ItemStack stack = source.container().getItem(slot);
            if (stack.isEmpty() || !stack.is(item)) continue;
            int tryAmount = Math.min(left, stack.getCount());
            ItemStack remainder = addTo(simulatedTarget, stack.copyWithCount(tryAmount));
            int accepted = tryAmount - remainder.getCount();
            if (accepted > 0) { moves.add(new Move(slot, accepted)); left -= accepted; }
        }
        return moves;
    }

    private record Use(int slot, int amount) {}
    private record Move(int slot, int amount) {}
    private record CraftPlan(List<Use> uses, ItemStack output, List<ItemStack> remaining,List<ItemStack> grid) {}
    private record InventoryAccess(Container container, int slots) {}
    private static final class SearchBudget {
        private int remaining = MAX_SEARCH_STEPS;
        private boolean consume() { return remaining-- > 0; }
    }
}
