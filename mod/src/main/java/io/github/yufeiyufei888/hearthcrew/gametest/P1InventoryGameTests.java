package io.github.yufeiyufei888.hearthcrew.gametest;

import io.github.yufeiyufei888.hearthcrew.HearthCrew;
import io.github.yufeiyufei888.hearthcrew.entity.CompanionEntity;
import io.github.yufeiyufei888.hearthcrew.gameplay.RecipeActions;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.Container;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** P1 evidence for real crafting and bounded, direct inventory delivery. */
@GameTestHolder(HearthCrew.ID)
@PrefixGameTestTemplate(false)
public final class P1InventoryGameTests {
    private P1InventoryGameTests() {}

    @GameTest(template = "p0_empty", timeoutTicks = 40, batch = "hearthcrew_p1_recipe_portable")
    public static void portableCraftUsesRealRecipeAndExactInputs(GameTestHelper helper) {
        floor(helper);
        CompanionEntity body = body(helper, new BlockPos(1, 1, 1));
        body.inventory().setItem(0, new ItemStack(Items.OAK_PLANKS, 2));

        RecipeActions.CraftResult result = RecipeActions.craft(body, id("minecraft", "stick"), 1, null);
        if (!result.completed() || result.completedRepetitions() != 1 || result.producedCount() != 4) {
            throw new GameTestAssertException("portable stick recipe did not complete exactly once: " + result);
        }
        if (count(body.inventory(), Items.OAK_PLANKS) != 0 || count(body.inventory(), Items.STICK) != 4) {
            throw new GameTestAssertException("portable craft changed inventory incorrectly: " + body.inventory());
        }
        helper.succeedWhen(() -> {});
    }

    @GameTest(template = "p0_empty", timeoutTicks = 40, batch = "hearthcrew_p1_recipe_station")
    public static void threeByThreeRecipeRequiresAndUsesRealCraftingTable(GameTestHelper helper) {
        floor(helper);
        CompanionEntity body = body(helper, new BlockPos(1, 1, 1));
        body.inventory().setItem(0, new ItemStack(Items.OAK_PLANKS, 3));
        body.inventory().setItem(1, new ItemStack(Items.STICK, 2));
        int planksBefore = count(body.inventory(), Items.OAK_PLANKS);
        int sticksBefore = count(body.inventory(), Items.STICK);

        RecipeActions.CraftResult denied = RecipeActions.craft(body, id("minecraft", "wooden_pickaxe"), 1, null);
        if (denied.completed() || denied.completedRepetitions() != 0 || count(body.inventory(), Items.WOODEN_PICKAXE) != 0
                || count(body.inventory(), Items.OAK_PLANKS) != planksBefore || count(body.inventory(), Items.STICK) != sticksBefore) {
            throw new GameTestAssertException("3x3 recipe was allowed without a station or mutated inputs: " + denied);
        }

        BlockPos station = helper.absolutePos(new BlockPos(2, 1, 1));
        helper.setBlock(new BlockPos(2, 1, 1), Blocks.CRAFTING_TABLE);
        RecipeActions.CraftResult crafted = RecipeActions.craft(body, id("minecraft", "wooden_pickaxe"), 1, station);
        if (!crafted.completed() || crafted.completedRepetitions() != 1 || count(body.inventory(), Items.WOODEN_PICKAXE) != 1) {
            throw new GameTestAssertException("real 3x3 station recipe did not complete: " + crafted + ", inventory=" + body.inventory());
        }
        if (count(body.inventory(), Items.OAK_PLANKS) != 0 || count(body.inventory(), Items.STICK) != 0) {
            throw new GameTestAssertException("3x3 craft did not consume the exact ingredients: " + body.inventory());
        }
        helper.succeedWhen(() -> {});
    }

    @GameTest(template = "p0_empty", timeoutTicks = 40, batch = "hearthcrew_p1_recipe_atomic")
    public static void missingAndUnknownRecipeNeverGenerateOrMutate(GameTestHelper helper) {
        floor(helper);
        CompanionEntity body = body(helper, new BlockPos(1, 1, 1));
        body.inventory().setItem(0, new ItemStack(Items.OAK_PLANKS, 1));
        BlockPos station = helper.absolutePos(new BlockPos(2, 1, 1));
        helper.setBlock(new BlockPos(2, 1, 1), Blocks.CRAFTING_TABLE);

        RecipeActions.CraftResult unknown = RecipeActions.craft(body, id("hearthcrew", "does_not_exist"), 1, station);
        if (unknown.completed() || unknown.producedCount() != 0 || count(body.inventory(), Items.OAK_PLANKS) != 1) {
            throw new GameTestAssertException("unknown recipe generated output or mutated input: " + unknown);
        }

        RecipeActions.CraftResult missing = RecipeActions.craft(body, id("minecraft", "chest"), 1, station);
        if (missing.completed() || missing.producedCount() != 0 || count(body.inventory(), Items.OAK_PLANKS) != 1
                || count(body.inventory(), Items.CHEST) != 0) {
            throw new GameTestAssertException("missing ingredients generated output or mutated input: " + missing + ", inventory=" + body.inventory());
        }
        helper.succeedWhen(() -> {});
    }

    @GameTest(template = "p0_empty", timeoutTicks = 40, batch = "hearthcrew_p1_recipe_remainders")
    public static void craftingReturnsRealContainerRemainder(GameTestHelper helper) {
        floor(helper);
        CompanionEntity body = body(helper, new BlockPos(1, 1, 1));
        body.inventory().setItem(0, new ItemStack(Items.MILK_BUCKET));
        body.inventory().setItem(1, new ItemStack(Items.MILK_BUCKET));
        body.inventory().setItem(2, new ItemStack(Items.MILK_BUCKET));
        body.inventory().setItem(3, new ItemStack(Items.SUGAR, 2));
        body.inventory().setItem(4, new ItemStack(Items.EGG));
        body.inventory().setItem(5, new ItemStack(Items.WHEAT, 3));
        BlockPos station = helper.absolutePos(new BlockPos(2, 1, 1));
        helper.setBlock(new BlockPos(2, 1, 1), Blocks.CRAFTING_TABLE);

        RecipeActions.CraftResult result = RecipeActions.craft(body, id("minecraft", "cake"), 1, station);
        if (!result.completed() || count(body.inventory(), Items.CAKE) != 1 || count(body.inventory(), Items.BUCKET) != 3
                || count(body.inventory(), Items.MILK_BUCKET) != 0 || count(body.inventory(), Items.SUGAR) != 0
                || count(body.inventory(), Items.EGG) != 0 || count(body.inventory(), Items.WHEAT) != 0) {
            throw new GameTestAssertException("real container remainder was not returned exactly: " + result + ", inventory=" + body.inventory());
        }
        helper.succeedWhen(() -> {});
    }

    @GameTest(template = "p0_empty", timeoutTicks = 60, batch = "hearthcrew_p1_transfer_player")
    public static void transferMovesExactStacksToRealServerPlayer(GameTestHelper helper) {
        floor(helper);
        CompanionEntity source = body(helper, new BlockPos(1, 1, 1));
        source.inventory().setItem(0, new ItemStack(Items.APPLE, 5));
        ServerPlayer target = helper.makeMockServerPlayerInLevel();
        BlockPos targetPos = helper.absolutePos(new BlockPos(2, 1, 1));
        target.teleportTo(helper.getLevel(), targetPos.getX() + 0.5, targetPos.getY(), targetPos.getZ() + 0.5, 0.0F, 0.0F);

        RecipeActions.TransferResult result = RecipeActions.transfer(helper.getLevel(), source.getUUID(), target.getUUID(), id("minecraft", "apple"), 3);
        if (!result.completed() || result.movedCount() != 3 || count(source.inventory(), Items.APPLE) != 2
                || target.getInventory().countItem(Items.APPLE) != 3) {
            throw new GameTestAssertException("companion to real player transfer was not exact: " + result
                    + ", source=" + source.inventory() + ", target=" + target.getInventory());
        }
        if (!helper.getLevel().getEntities(EntityType.ITEM, source.getBoundingBox().inflate(4), item -> item.isAlive()).isEmpty()) {
            throw new GameTestAssertException("transfer incorrectly relied on a dropped ItemEntity");
        }
        helper.succeedWhen(() -> {});
    }

    @GameTest(template = "p0_empty", timeoutTicks = 60, batch = "hearthcrew_p1_transfer_los")
    public static void transferRequiresProximityAndLineOfSight(GameTestHelper helper) {
        floor(helper);
        CompanionEntity source = body(helper, new BlockPos(1, 1, 1));
        CompanionEntity target = body(helper, new BlockPos(3, 1, 1));
        source.inventory().setItem(0, new ItemStack(Items.APPLE, 2));
        helper.setBlock(new BlockPos(2, 1, 1), Blocks.STONE);
        helper.setBlock(new BlockPos(2, 2, 1), Blocks.STONE);

        RecipeActions.TransferResult blocked = RecipeActions.transfer(helper.getLevel(), source.getUUID(), target.getUUID(), id("minecraft", "apple"), 2);
        if (blocked.movedCount() != 0 || count(source.inventory(), Items.APPLE) != 2 || count(target.inventory(), Items.APPLE) != 0) {
            throw new GameTestAssertException("transfer crossed a solid block or mutated on LOS failure: " + blocked);
        }
        helper.setBlock(new BlockPos(2, 1, 1), Blocks.AIR);
        helper.setBlock(new BlockPos(2, 2, 1), Blocks.AIR);
        RecipeActions.TransferResult delivered = RecipeActions.transfer(helper.getLevel(), source.getUUID(), target.getUUID(), id("minecraft", "apple"), 2);
        if (!delivered.completed() || delivered.movedCount() != 2 || count(source.inventory(), Items.APPLE) != 0
                || count(target.inventory(), Items.APPLE) != 2) {
            throw new GameTestAssertException("nearby visible transfer did not complete: " + delivered);
        }
        helper.succeedWhen(() -> {});
    }

    @GameTest(template = "p0_empty", timeoutTicks = 60, batch = "hearthcrew_p1_transfer_capacity")
    public static void transferReportsActualPartialCapacityWithoutDuplication(GameTestHelper helper) {
        floor(helper);
        CompanionEntity source = body(helper, new BlockPos(1, 1, 1));
        CompanionEntity target = body(helper, new BlockPos(3, 1, 1));
        source.inventory().setItem(0, new ItemStack(Items.APPLE, 3));
        target.inventory().setItem(0, new ItemStack(Items.APPLE, 63));
        for (int slot = 1; slot < 36; slot++) target.inventory().setItem(slot, new ItemStack(Items.COBBLESTONE, 64));

        RecipeActions.TransferResult result = RecipeActions.transfer(helper.getLevel(), source.getUUID(), target.getUUID(), id("minecraft", "apple"), 3);
        if (result.movedCount() != 1 || result.completed() || count(source.inventory(), Items.APPLE) != 2
                || count(target.inventory(), Items.APPLE) != 64) {
            throw new GameTestAssertException("capacity-limited transfer was not an exact partial move: " + result
                    + ", source=" + source.inventory() + ", target=" + target.inventory());
        }
        helper.succeedWhen(() -> {});
    }

    @GameTest(template = "p0_empty", timeoutTicks = 60, batch = "hearthcrew_p1_transfer_player_full")
    public static void fullPlayerMainInventoryDoesNotUseArmorOrOffhand(GameTestHelper helper) {
        floor(helper);
        CompanionEntity source = body(helper, new BlockPos(1, 1, 1));
        source.inventory().setItem(0, new ItemStack(Items.APPLE));
        ServerPlayer target = helper.makeMockServerPlayerInLevel();
        BlockPos targetPos = helper.absolutePos(new BlockPos(2, 1, 1));
        target.teleportTo(helper.getLevel(), targetPos.getX() + 0.5, targetPos.getY(), targetPos.getZ() + 0.5, 0.0F, 0.0F);
        for (int slot = 0; slot < 36; slot++) target.getInventory().setItem(slot, new ItemStack(Items.COBBLESTONE, 64));
        target.getInventory().setItem(36, ItemStack.EMPTY);
        target.getInventory().setItem(40, ItemStack.EMPTY);

        RecipeActions.TransferResult result = RecipeActions.transfer(helper.getLevel(), source.getUUID(), target.getUUID(), id("minecraft", "apple"), 1);
        if (result.movedCount() != 0 || count(source.inventory(), Items.APPLE) != 1
                || !target.getInventory().getItem(36).isEmpty() || !target.getInventory().getItem(40).isEmpty()) {
            throw new GameTestAssertException("full player main inventory leaked delivery into armor/offhand: " + result
                    + ", armor=" + target.getInventory().getItem(36) + ", offhand=" + target.getInventory().getItem(40));
        }
        helper.succeedWhen(() -> {});
    }

    @GameTest(template = "p0_empty", timeoutTicks = 40, batch = "hearthcrew_p1_recipe_full")
    public static void fullBodyInventoryDoesNotConsumeInputsWhenOutputCannotFit(GameTestHelper helper) {
        floor(helper);
        CompanionEntity body = body(helper, new BlockPos(1, 1, 1));
        body.inventory().setItem(0, new ItemStack(Items.OAK_PLANKS, 64));
        for (int slot = 1; slot < body.inventory().getContainerSize(); slot++) body.inventory().setItem(slot, new ItemStack(Items.COBBLESTONE, 64));

        RecipeActions.CraftResult result = RecipeActions.craft(body, id("minecraft", "stick"), 1, null);
        if (result.completed() || result.completedRepetitions() != 0 || result.producedCount() != 0
                || count(body.inventory(), Items.OAK_PLANKS) != 64 || count(body.inventory(), Items.STICK) != 0) {
            throw new GameTestAssertException("full inventory consumed crafting inputs without output capacity: " + result
                    + ", inventory=" + body.inventory());
        }
        for (int slot = 0; slot < body.inventory().getContainerSize(); slot++) {
            if (body.inventory().getItem(slot).isEmpty()) throw new GameTestAssertException("full inventory developed an empty slot after rejected craft: " + slot);
        }
        helper.succeedWhen(() -> {});
    }

    private static ResourceLocation id(String namespace, String path) {
        return ResourceLocation.fromNamespaceAndPath(namespace, path);
    }

    private static CompanionEntity body(GameTestHelper helper, BlockPos position) {
        CompanionEntity body = io.github.yufeiyufei888.hearthcrew.gametest.PlayerTestBodies.spawn(helper, position);
        body.setRespawnEnabled(false);
        return body;
    }

    private static void floor(GameTestHelper helper) {
        for (int x = 0; x < 6; x++) for (int z = 0; z < 6; z++) helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
    }

    private static int count(Container inventory, Item item) {
        int total = 0;
        for (int slot = 0; slot < inventory.getContainerSize(); slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack.is(item)) total += stack.getCount();
        }
        return total;
    }
}
