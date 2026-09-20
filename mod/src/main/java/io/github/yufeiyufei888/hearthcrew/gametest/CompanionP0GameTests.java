package io.github.yufeiyufei888.hearthcrew.gametest;

import io.github.yufeiyufei888.hearthcrew.HearthCrew;
import io.github.yufeiyufei888.hearthcrew.entity.BodyOrder;
import io.github.yufeiyufei888.hearthcrew.entity.CompanionEntity;
import io.github.yufeiyufei888.hearthcrew.gameplay.CompanionRespawn;
import io.github.yufeiyufei888.hearthcrew.gameplay.CompanionTargeting;
import io.github.yufeiyufei888.hearthcrew.kernel.ActionPriority;
import io.github.yufeiyufei888.hearthcrew.kernel.ActionId;
import io.github.yufeiyufei888.hearthcrew.kernel.ActionState;
import io.github.yufeiyufei888.hearthcrew.kernel.FoodState;
import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.core.Direction;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.level.TicketType;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.boss.enderdragon.EnderDragon;
import net.minecraft.world.entity.boss.enderdragon.phases.EnderDragonPhase;
import net.minecraft.world.entity.monster.Creeper;
import net.minecraft.world.entity.monster.Skeleton;
import net.minecraft.world.entity.monster.Zombie;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BedBlock;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.RespawnAnchorBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/** Server-side P0 gates for the player-style body contract. */
@GameTestHolder(HearthCrew.ID)
@PrefixGameTestTemplate(false)
public final class CompanionP0GameTests {
    private CompanionP0GameTests() {}

    @GameTest(template = "p0_empty", timeoutTicks = 500, batch = "hearthcrew_p0_body")
    public static void movementUsesPhysicsAndRoutesAroundObstacle(GameTestHelper helper) {
        floor(helper);
        helper.setBlock(new BlockPos(2, 1, 1), Blocks.STONE);
        helper.setBlock(new BlockPos(2, 2, 1), Blocks.STONE);
        CompanionEntity body = body(helper, new BlockPos(1, 1, 1));
        body.executor().submit("p0-move-" + body.getUUID(), BodyOrder.move(helper.absolutePos(new BlockPos(3, 1, 1))), ActionPriority.OWNER);
        helper.succeedWhen(() -> {
            BlockPos destination = helper.absolutePos(new BlockPos(3, 1, 1));
            if (body.position().distanceToSqr(destination.getX() + 0.5, destination.getY(), destination.getZ() + 0.5) > 1.25 * 1.25
                    || Math.abs(body.getY() - destination.getY()) > 1.25) {
                throw new GameTestAssertException("body did not reach the requested block through the obstacle");
            }
        });
    }

    @GameTest(template = "p0_empty", timeoutTicks = 500, batch = "hearthcrew_p0_body")
    public static void miningUsesRealToolAndTracksDrop(GameTestHelper helper) {
        floor(helper);
        BlockPos target = new BlockPos(2, 1, 1);
        helper.setBlock(target, Blocks.STONE);
        CompanionEntity body = body(helper, new BlockPos(1, 1, 1));
        body.inventory().setItem(0, new ItemStack(Items.WOODEN_PICKAXE));
        String actionId = "p0-mine-" + body.getUUID();
        body.executor().submit(actionId, BodyOrder.mine(helper.absolutePos(target)), ActionPriority.OWNER);
        helper.succeedWhen(() -> {
            if (!helper.getBlockState(target).isAir()) {
                throw new GameTestAssertException("mine action did not change the target block");
            }
            if (count(body, Items.COBBLESTONE) < 1) {
                throw new GameTestAssertException("stone drop was not acquired by the body inventory; item="
                        + itemDiagnostics(helper) + ", bodyPos=" + body.position() + ", action="
                        + actionDiagnostics(body, actionId));
            }
            if (body.getMainHandItem().getDamageValue() < 1) {
                throw new GameTestAssertException("mining did not consume tool durability");
            }
            if (body.executor().arbiter().snapshot(ActionId.of(actionId)).map(snapshot -> snapshot.state() != ActionState.COMPLETED).orElse(true)) {
                throw new GameTestAssertException("mine action did not reach COMPLETED after inventory postcondition");
            }
        });
    }

    @GameTest(template = "p0_empty", timeoutTicks = 160, batch = "hearthcrew_p0_body")
    public static void pickupMovesActualItemIntoInventory(GameTestHelper helper) {
        floor(helper);
        CompanionEntity body = body(helper, new BlockPos(1, 1, 1));
        var item = helper.spawnItem(Items.APPLE, new BlockPos(1, 1, 1));
        item.setPickUpDelay(0);
        body.executor().submit("p0-pickup-" + body.getUUID(), new BodyOrder(BodyOrder.Kind.PICKUP, null, item.getUUID(), 0), ActionPriority.OWNER);
        helper.succeedWhen(() -> {
            if (count(body, Items.APPLE) < 1) {
                throw new GameTestAssertException("item entity was not added to the actual companion inventory");
            }
            if (item.isAlive()) {
                throw new GameTestAssertException("picked item entity is still alive");
            }
        });
    }

    @GameTest(template = "p0_empty", timeoutTicks = 240, batch = "hearthcrew_p0_pickup_edge")
    public static void pickupApproachesEdgeItemUntilBodiesTouch(GameTestHelper helper) {
        floor(helper);
        CompanionEntity body = body(helper, new BlockPos(1, 1, 1));
        var item = helper.spawnItem(Items.APPLE, new BlockPos(2, 1, 1));
        item.setPickUpDelay(0);
        body.executor().submit("p0-pickup-edge-" + body.getUUID(), new BodyOrder(BodyOrder.Kind.PICKUP, null, item.getUUID(), 0), ActionPriority.OWNER);
        helper.succeedWhen(() -> {
            if (count(body, Items.APPLE) != 1) {
                throw new GameTestAssertException("edge item did not enter the actual body inventory");
            }
            if (item.isAlive()) {
                throw new GameTestAssertException("edge item remained alive after body contact");
            }
        });
    }

    @GameTest(template = "p0_empty", timeoutTicks = 180, batch = "hearthcrew_p0_body")
    public static void eatingHasDurationAndRaisesFoodState(GameTestHelper helper) {
        floor(helper);
        CompanionEntity body = body(helper, new BlockPos(1, 1, 1));
        body.setFoodState(new FoodState(16, 0.0F, 0.0F));
        body.inventory().setItem(0, new ItemStack(Items.BREAD, 1));
        body.executor().submit("p0-eat-" + body.getUUID(), BodyOrder.eat(), ActionPriority.OWNER);
        helper.succeedWhen(() -> {
            if (body.foodLevel() <= 16) {
                throw new GameTestAssertException("food state did not increase after the use duration");
            }
            if (count(body, Items.BREAD) != 0) {
                throw new GameTestAssertException("food stack was not consumed exactly once");
            }
        });
    }

    @GameTest(template = "p0_empty", timeoutTicks = 110, batch = "hearthcrew_p0_death_false")
    public static void survivalDamageUsesNormalLivingEntitySemantics(GameTestHelper helper) {
        floor(helper);
        CompanionEntity body = body(helper, new BlockPos(1, 1, 1));
        float before = body.getHealth();
        helper.runAfterDelay(65, () -> {
            if (!body.hurt(body.damageSources().generic(), 4.0F)) helper.fail("generic damage was rejected");
        });
        helper.succeedWhen(() -> {
            if (!(body.getHealth() < before && body.isAlive())) {
                throw new GameTestAssertException("survival damage did not reduce live body health");
            }
        });
    }

    @GameTest(template = "p0_empty", timeoutTicks = 110, batch = "hearthcrew_p0_death_true")
    public static void deathDropsInventoryWhenKeepInventoryFalseEvenWithoutMobLoot(GameTestHelper helper) {
        floor(helper);
        GameRules.BooleanValue keep = helper.getLevel().getGameRules().getRule(GameRules.RULE_KEEPINVENTORY);
        GameRules.BooleanValue mobLoot = helper.getLevel().getGameRules().getRule(GameRules.RULE_DOMOBLOOT);
        boolean originalKeep = keep.get();
        boolean originalMobLoot = mobLoot.get();
        keep.set(false, helper.getLevel().getServer());
        mobLoot.set(false, helper.getLevel().getServer());
        CompanionEntity body = body(helper, new BlockPos(1, 1, 1));
        body.inventory().setItem(0, new ItemStack(Items.DIAMOND, 3));
        helper.runAfterDelay(65, () -> body.hurt(body.damageSources().generic(), 1000.0F));
        helper.runAfterDelay(20, () -> {
            keep.set(originalKeep, helper.getLevel().getServer());
            mobLoot.set(originalMobLoot, helper.getLevel().getServer());
        });
        helper.succeedWhen(() -> {
            helper.assertItemEntityCountIs(Items.DIAMOND, new BlockPos(1, 1, 1), 3.0, 3);
            if (countDrops(helper, Items.DIAMOND) != 3) {
                throw new GameTestAssertException("death settlement did not produce exactly one inventory drop");
            }
        });
    }

    @GameTest(template = "p0_empty", timeoutTicks = 110, batch = "hearthcrew_p0_body")
    public static void deathKeepsInventoryWhenKeepInventoryTrue(GameTestHelper helper) {
        floor(helper);
        GameRules.BooleanValue keep = helper.getLevel().getGameRules().getRule(GameRules.RULE_KEEPINVENTORY);
        GameRules.BooleanValue mobLoot = helper.getLevel().getGameRules().getRule(GameRules.RULE_DOMOBLOOT);
        boolean originalKeep = keep.get();
        boolean originalMobLoot = mobLoot.get();
        keep.set(true, helper.getLevel().getServer());
        mobLoot.set(false, helper.getLevel().getServer());
        CompanionEntity body = body(helper, new BlockPos(1, 1, 1));
        body.inventory().setItem(0, new ItemStack(Items.DIAMOND, 3));
        helper.runAfterDelay(65, () -> body.hurt(body.damageSources().generic(), 1000.0F));
        helper.runAfterDelay(20, () -> {
            keep.set(originalKeep, helper.getLevel().getServer());
            mobLoot.set(originalMobLoot, helper.getLevel().getServer());
        });
        helper.succeedWhen(() -> {
            helper.assertItemEntityCountIs(Items.DIAMOND, new BlockPos(1, 1, 1), 3.0, 0);
            if (count(body, Items.DIAMOND) != 3) {
                throw new GameTestAssertException("keepInventory death lost the actual body inventory");
            }
        });
    }

    @GameTest(template = "p0_empty", timeoutTicks = 60, batch = "hearthcrew_p0_hostiles")
    public static void hostileTargetingSupportsZombie(GameTestHelper helper) {
        floor(helper);
        CompanionEntity body = body(helper, new BlockPos(3, 1, 1));
        Zombie hostile = helper.spawn(EntityType.ZOMBIE, new BlockPos(1, 1, 1));
        if (!CompanionTargeting.canAcquire(hostile, body, null)) helper.fail("targeting helper rejected zombie");
        helper.succeedWhen(() -> {
            if (hostile.getTarget() != body) throw new GameTestAssertException("zombie did not select companion as its actual target");
        });
    }

    @GameTest(template = "p0_empty", timeoutTicks = 80, batch = "hearthcrew_p0_hostiles_skeleton")
    public static void hostileTargetingSupportsSkeleton(GameTestHelper helper) {
        floor(helper);
        CompanionEntity body = body(helper, new BlockPos(3, 1, 1));
        Skeleton hostile = helper.spawn(EntityType.SKELETON, new BlockPos(1, 1, 1));
        if (!CompanionTargeting.canAcquire(hostile, body, null)) helper.fail("targeting helper rejected skeleton");
        helper.succeedWhen(() -> {
            if (hostile.getTarget() != body) throw new GameTestAssertException("skeleton did not select companion as its actual target");
        });
    }

    @GameTest(template = "p0_empty", timeoutTicks = 50, batch = "hearthcrew_p0_hostiles_creeper")
    public static void hostileTargetingSupportsCreeper(GameTestHelper helper) {
        floor(helper);
        CompanionEntity body = body(helper, new BlockPos(3, 1, 1));
        Creeper hostile = helper.spawn(EntityType.CREEPER, new BlockPos(1, 1, 1));
        if (!CompanionTargeting.canAcquire(hostile, body, null)) helper.fail("targeting helper rejected creeper");
        helper.succeedWhen(() -> {
            if (hostile.getTarget() != body) throw new GameTestAssertException("creeper did not select companion as its actual target");
        });
    }

    @GameTest(template = "p0_empty", timeoutTicks = 80, batch = "hearthcrew_p0_respawn")
    public static void bedRespawnSelectsSafeStandPosition(GameTestHelper helper) {
        floor(helper);
        BlockPos foot = new BlockPos(1, 1, 1);
        BlockState bedFoot = Blocks.RED_BED.defaultBlockState()
                .setValue(BedBlock.FACING, Direction.EAST)
                .setValue(BedBlock.PART, BedPart.FOOT)
                .setValue(BedBlock.OCCUPIED, false);
        BlockState bedHead = bedFoot.setValue(BedBlock.PART, BedPart.HEAD);
        helper.setBlock(foot, bedFoot);
        helper.setBlock(foot.relative(Direction.EAST), bedHead);
        var choice = CompanionRespawn.resolve(helper.getLevel().getServer(), HearthCrew.COMPANION.get(),
                new CompanionRespawn.Request(Level.OVERWORLD, helper.absolutePos(foot), 0.0F, false));
        if (choice.isEmpty() || choice.get().source() != CompanionRespawn.Source.BED) {
            helper.fail("valid bed did not produce a player-style respawn choice");
        }
        helper.succeedWhen(() -> {
            if (!Double.isFinite(choice.get().position().x) || !Double.isFinite(choice.get().position().z)) {
                throw new GameTestAssertException("bed choice is not a finite position");
            }
        });
    }

    @GameTest(template = "p0_empty", timeoutTicks = 80, batch = "hearthcrew_p0_respawn")
    public static void chargedNetherAnchorConsumesExactlyOnce(GameTestHelper helper) {
        ServerLevel nether = helper.getLevel().getServer().getLevel(Level.NETHER);
        if (nether == null) helper.fail("Nether level is unavailable in the GameTest server");
        BlockPos anchor = new BlockPos(0, 64, 0);
        for (int x = -2; x <= 2; x++) for (int z = -2; z <= 2; z++) {
            nether.setBlock(new BlockPos(x, 63, z), Blocks.OBSIDIAN.defaultBlockState(), 3);
            for (int y = 64; y <= 67; y++) nether.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 3);
        }
        nether.setBlock(anchor, Blocks.RESPAWN_ANCHOR.defaultBlockState().setValue(RespawnAnchorBlock.CHARGE, 2), 3);
        var choice = CompanionRespawn.resolve(helper.getLevel().getServer(), HearthCrew.COMPANION.get(),
                new CompanionRespawn.Request(Level.NETHER, anchor, 0.0F, false));
        if (choice.isEmpty() || choice.get().source() != CompanionRespawn.Source.RESPAWN_ANCHOR) {
            helper.fail("charged Nether anchor did not produce a respawn choice");
        }
        if (!CompanionRespawn.consumeAnchor(choice.get())) helper.fail("first anchor settlement was rejected");
        if (nether.getBlockState(anchor).getValue(RespawnAnchorBlock.CHARGE) != 1) helper.fail("anchor charge was not decremented once");
        if (CompanionRespawn.consumeAnchor(choice.get())) helper.fail("anchor choice could be consumed twice");
        helper.succeedWhen(() -> {});
    }

    @GameTest(template = "p0_empty", timeoutTicks = 260, batch = "hearthcrew_p0_sleep")
    public static void sleepOccupiesBedAndRespawnPreservesSavedBodyState(GameTestHelper helper) {
        floor(helper);
        BlockPos foot = new BlockPos(1, 1, 1);
        BlockState bedFoot = Blocks.RED_BED.defaultBlockState()
                .setValue(BedBlock.FACING, Direction.EAST)
                .setValue(BedBlock.PART, BedPart.FOOT)
                .setValue(BedBlock.OCCUPIED, false);
        BlockState bedHead = bedFoot.setValue(BedBlock.PART, BedPart.HEAD);
        helper.setBlock(foot, bedFoot);
        BlockPos head = foot.relative(Direction.EAST);
        helper.setBlock(head, bedHead);
        helper.setNight();
        GameRules.BooleanValue keep = helper.getLevel().getGameRules().getRule(GameRules.RULE_KEEPINVENTORY);
        boolean originalKeep = keep.get();
        keep.set(true, helper.getLevel().getServer());
        CompanionEntity body = body(helper, new BlockPos(1, 1, 2));
        body.setRespawnEnabled(true);
        body.inventory().setItem(4, new ItemStack(Items.IRON_INGOT, 2));
        UUID identity = body.companionId();
        String actionId = "p0-sleep-" + body.getUUID();
        body.executor().submit(actionId, new BodyOrder(BodyOrder.Kind.SLEEP, helper.absolutePos(head), null, 0), ActionPriority.OWNER);
        helper.runAfterDelay(240, () -> keep.set(originalKeep, helper.getLevel().getServer()));
        helper.startSequence()
                .thenWaitUntil(() -> {
                    if (!body.isSleeping()) throw new GameTestAssertException("sleep action did not put body into sleeping state");
                    if (!helper.getBlockState(head).getValue(BedBlock.OCCUPIED)) throw new GameTestAssertException("sleep action did not occupy the bed");
                })
                .thenExecuteAfter(65, () -> {
                    if (!body.hurt(body.damageSources().generic(), 1000.0F)) throw new GameTestAssertException("sleeping body rejected lethal damage");
                })
                .thenWaitUntil(() -> {
                    var living = helper.getLevel().getEntitiesOfClass(CompanionEntity.class, helper.getBounds(), CompanionEntity::isAlive);
                    if (living.size() != 1) throw new GameTestAssertException("expected one live respawned companion, found " + living.size());
                    CompanionEntity replacement = living.getFirst();
                    if (!identity.equals(replacement.companionId())) throw new GameTestAssertException("respawn changed companion identity");
                    if (count(replacement, Items.IRON_INGOT) != 2) throw new GameTestAssertException("respawn did not preserve saved inventory");
                })
                .thenExecute(() -> keep.set(originalKeep, helper.getLevel().getServer()))
                .thenSucceed();
    }

    @GameTest(template = "p0_empty", timeoutTicks = 110, batch = "hearthcrew_p0_damage")
    public static void armorDamageConsumesDurabilityThroughLivingEntityPath(GameTestHelper helper) {
        floor(helper);
        CompanionEntity body = body(helper, new BlockPos(1, 1, 1));
        body.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
        Zombie attacker = EntityType.ZOMBIE.create(helper.getLevel());
        if (attacker == null) helper.fail("could not construct armor damage source");
        attacker.moveTo(1.5, 1.0, 2.5, 0.0F, 0.0F);
        attacker.setNoAi(true);
        if (!helper.getLevel().addFreshEntity(attacker)) helper.fail("could not add armor damage source");
        float[] healthBefore = {body.getHealth()};
        helper.runAfterDelay(65, () -> {
            if (body.getArmorValue() <= 0) throw new GameTestAssertException("equipped chestplate did not apply armor attribute");
            healthBefore[0] = body.getHealth();
            if (!body.hurt(body.damageSources().mobAttack(attacker), 8.0F)) throw new GameTestAssertException("normal mob damage was rejected");
        });
        helper.succeedWhen(() -> {
            if (!(body.getHealth() < healthBefore[0] && body.getHealth() > healthBefore[0] - 8.0F)) {
                throw new GameTestAssertException("normal mob damage did not apply armor reduction; health=" + body.getHealth()
                        + ", rawBefore=" + healthBefore[0]);
            }
            if (body.getItemBySlot(EquipmentSlot.CHEST).getDamageValue() <= 0) {
                throw new GameTestAssertException("armor absorbed damage without consuming real chestplate durability; item="
                        + body.getItemBySlot(EquipmentSlot.CHEST) + ", armorValue=" + body.getArmorValue()
                        + ", health=" + body.getHealth() + ", healthBefore=" + healthBefore[0]);
            }
        });
    }

    @GameTest(template = "p0_empty", timeoutTicks = 60, batch = "hearthcrew_p0_food")
    public static void foodTimerSerializationContinuesNaturalRegeneration(GameTestHelper helper) {
        floor(helper);
        CompanionEntity original = body(helper, new BlockPos(1, 1, 1));
        original.setHealth(10.0F);
        original.setFoodState(new FoodState(20, 5.0F, 0.0F).restoreTimers(9, 3));
        original.inventory().setItem(4, new ItemStack(Items.BREAD, 2));
        CompoundTag saved = new CompoundTag();
        original.addAdditionalSaveData(saved);
        CompanionEntity restored = io.github.yufeiyufei888.hearthcrew.runtime.CrewPlayers.create(helper.getLevel());
        if (restored == null) helper.fail("could not construct food timer restoration body");
        restored.readAdditionalSaveData(saved);
        if (restored.foodState().foodTickTimer() != 9 || restored.foodState().peacefulClock() != 3) {
            helper.fail("food timers were not serialized exactly");
        }
        BlockPos restoredPosition = helper.absolutePos(new BlockPos(3, 1, 1));
        restored.moveTo(restoredPosition.getX() + 0.5, restoredPosition.getY(), restoredPosition.getZ() + 0.5, 0.0F, 0.0F);
        restored.setRespawnEnabled(false);
        if (helper.getLevel().getServer().getPlayerList().getPlayer(restored.getUUID()) != restored) helper.fail("restored food body was not registered");
        helper.succeedWhen(() -> {
            if (!(restored.getHealth() > 10.0F)) throw new GameTestAssertException("restored food timer did not reach natural regeneration tick");
            if (restored.foodState().foodTickTimer() >= 9) throw new GameTestAssertException("food timer did not advance from restored value");
            if (count(restored, Items.BREAD) != 2) throw new GameTestAssertException("food timer restoration changed inventory");
        });
    }

    @GameTest(template = "p0_empty", timeoutTicks = 1200, batch = "hearthcrew_p0_dragon")
    public static void attackDamagesDragonAndDragonBreathDamagesCompanion(GameTestHelper helper) {
        ServerLevel end = helper.getLevel().getServer().getLevel(Level.END);
        if (end == null) helper.fail("End level is unavailable in the GameTest server");
        // Use this run's GameTest X/Z instead of the persistent End world's
        // fixed origin. Otherwise old dragons and entities from a previous
        // run can share the fixture, and the body can be spawned in a
        // different entity-ticking area. Y is intentionally kept at the
        // controlled End platform height.
        BlockPos runOrigin = helper.absolutePos(new BlockPos(1, 1, 1));
        int baseX = runOrigin.getX();
        int baseZ = runOrigin.getZ();
        for (int x = -16; x <= 16; x++) for (int z = -16; z <= 16; z++) {
            end.setBlock(new BlockPos(baseX + x, 69, baseZ + z), Blocks.END_STONE.defaultBlockState(), 3);
            for (int y = 70; y <= 74; y++) end.setBlock(new BlockPos(baseX + x, y, baseZ + z), Blocks.AIR.defaultBlockState(), 3);
        }
        // EndDragonFight removes an unregistered dragon when no exit-portal block entity exists.
        end.setBlock(new BlockPos(baseX, 64, baseZ), Blocks.END_PORTAL.defaultBlockState(), 3);
        BlockPos dragonOrigin = new BlockPos(baseX, 70, baseZ);
        BlockPos attackerPosition = new BlockPos(baseX, 70, baseZ - 4);
        BlockPos victimPosition = new BlockPos(baseX, 70, baseZ - 9);
        ChunkPos dragonChunk = new ChunkPos(dragonOrigin);
        // radius=3 maps to ticket level 30 in vanilla: the center and its
        // eight neighboring chunks reach ENTITY_TICKING (level 31). radius=2
        // only makes the center entity-ticking, which is insufficient when
        // the attacker's or victim's block crosses a chunk boundary.
        // This is test-only loading and does not replace production evidence.
        end.getChunkSource().addRegionTicket(TicketType.FORCED, dragonChunk, 3, dragonChunk, true);
        // GameTest has no real player in End; keep the mock player near the
        // same run-local chunk rather than at the persistent origin.
        ServerPlayer ticker = helper.makeMockServerPlayerInLevel();
        ticker.teleportTo(end, baseX + 4.0, 70.0, baseZ + 4.0, 0.0F, 0.0F);
        EnderDragon[] dragonRef = new EnderDragon[1];
        CompanionEntity[] attackerRef = new CompanionEntity[1];
        CompanionEntity[] victimRef = new CompanionEntity[1];
        float[] dragonHealthRef = new float[1];
        float[] victimHealthRef = new float[1];
        int[] swordDamageRef = new int[1];
        long[] combatStartRef = new long[] {-1L};
        String[] actionIdRef = new String[1];
        helper.startSequence()
                // Do not spawn the dragon or set its phase before readiness:
                // a cold remote chunk can leave the phase without ticking,
                // making the breath assertion a false fixture failure.
                .thenWaitUntil(() -> {
                    if (!end.isPositionEntityTicking(dragonOrigin)
                            || !end.isPositionEntityTicking(attackerPosition)
                            || !end.isPositionEntityTicking(victimPosition)) {
                        throw new GameTestAssertException("End combat fixture is not entity-ticking; dragon="
                                + end.isPositionEntityTicking(dragonOrigin) + ", attacker="
                                + end.isPositionEntityTicking(attackerPosition) + ", victim="
                                + end.isPositionEntityTicking(victimPosition) + ", chunk=" + dragonChunk
                                + ", gameTime=" + end.getGameTime());
                    }
                })
                .thenExecute(() -> {
                    EnderDragon dragon = EntityType.ENDER_DRAGON.create(end);
                    if (dragon == null) throw new GameTestAssertException("could not construct EnderDragon");
                    dragon.moveTo(baseX + 0.5, 70.0, baseZ + 0.5, 0.0F, 0.0F);
                    if (!end.addFreshEntity(dragon)) throw new GameTestAssertException("could not add EnderDragon to End test level");
                    // A fixed vanilla sitting phase keeps the breath location deterministic while retaining normal dragon ticking and collision code.
                    dragon.getPhaseManager().setPhase(EnderDragonPhase.SITTING_FLAMING);

                    CompanionEntity attacker = io.github.yufeiyufei888.hearthcrew.runtime.CrewPlayers.create(end);
                    CompanionEntity victim = io.github.yufeiyufei888.hearthcrew.runtime.CrewPlayers.create(end);
                    if (attacker == null || victim == null) throw new GameTestAssertException("could not construct End test companions");
                    // With yaw zero and the fixed sitting phase, vanilla's head is about six blocks toward -Z;
                    // this legal ground position is close enough for the real hitbox without disabling physics.
                    attacker.moveTo(baseX + 0.5, 70.0, baseZ - 4.0, 180.0F, 0.0F);
                    victim.moveTo(baseX + 0.5, 70.0, baseZ - 9.0, 0.0F, 0.0F);
                    attacker.setRespawnEnabled(false);
                    victim.setRespawnEnabled(false);
                    attacker.setNoGravity(true);
                    victim.setNoGravity(true);
                    // Both bodies retain ordinary collision, gravity, and damage handling; this fixture only fixes the dragon phase.
                    attacker.inventory().setItem(0, new ItemStack(Items.DIAMOND_SWORD, 1));
                    // CrewPlayers.create already registers the native players
                    // with the server. Adding them a second time reuses their
                    // UUID and leaves the later references null.
                    if (end.getServer().getPlayerList().getPlayer(attacker.getUUID()) != attacker
                            || end.getServer().getPlayerList().getPlayer(victim.getUUID()) != victim) {
                        throw new GameTestAssertException("End test companions were not registered");
                    }
                    dragonRef[0] = dragon;
                    attackerRef[0] = attacker;
                    victimRef[0] = victim;
                    dragonHealthRef[0] = dragon.getHealth();
                    victimHealthRef[0] = victim.getHealth();
                    swordDamageRef[0] = attacker.getMainHandItem().getDamageValue();
                    actionIdRef[0] = "p0-dragon-attack-" + attacker.getUUID();
                    combatStartRef[0] = end.getGameTime();
                    attacker.executor().submit(actionIdRef[0], new BodyOrder(BodyOrder.Kind.ATTACK, null, dragon.getUUID(), 0), ActionPriority.OWNER);
                })
                .thenWaitUntil(() -> {
                    EnderDragon dragon = dragonRef[0];
                    CompanionEntity attacker = attackerRef[0];
                    CompanionEntity victim = victimRef[0];
                    String actionId = actionIdRef[0];
                    if (end.getGameTime() - combatStartRef[0] > 180) {
                        String state = attacker.executor().arbiter().snapshot(ActionId.of(actionId)).map(snapshot -> snapshot.state() + ":" + snapshot.message()).orElse("missing");
                        throw new GameTestAssertException("dragon combat exceeded 180 game ticks; action=" + state
                                + ", attacker=" + attacker.position() + ", dragon=" + dragon.position()
                                + ", distance=" + attacker.distanceToSqr(dragon) + ", lineOfSight=" + attacker.hasLineOfSight(dragon));
                    }
                    if (!(dragon.getHealth() < dragonHealthRef[0])) {
                        String state = attacker.executor().arbiter().snapshot(ActionId.of(actionId)).map(snapshot -> snapshot.state() + ":" + snapshot.message()).orElse("missing");
                        throw new GameTestAssertException("companion attack did not reduce EnderDragon health; action=" + state
                                + ", distance=" + attacker.distanceToSqr(dragon) + ", lineOfSight=" + attacker.hasLineOfSight(dragon)
                                + ", attacker=" + attacker.position() + ", dragon=" + dragon.position());
                    }
                    if (!(attacker.getMainHandItem().getDamageValue() > swordDamageRef[0])) throw new GameTestAssertException("successful dragon attack did not consume sword durability");
                    if (attacker.executor().arbiter().snapshot(ActionId.of(actionId)).map(snapshot -> snapshot.state() == ActionState.FAILED).orElse(true)) {
                        throw new GameTestAssertException("dragon attack action entered FAILED state");
                    }
                    if (!(victim.getHealth() < victimHealthRef[0])) throw new GameTestAssertException("vanilla dragon breath did not damage a companion");
                })
                .thenExecute(() -> end.getChunkSource().removeRegionTicket(TicketType.FORCED, dragonChunk, 3, dragonChunk, true))
                .thenSucceed();
    }

    @GameTest(template = "p0_empty", timeoutTicks = 1200, batch = "hearthcrew_p0_portal_nether")
    public static void netherPortalRoundTripPreservesBodyState(GameTestHelper helper) {
        ServerLevel overworld = helper.getLevel();
        ServerLevel nether = overworld.getServer().getLevel(Level.NETHER);
        if (nether == null) helper.fail("Nether level is unavailable; this gate is not a pass without it");

        BlockPos sourcePortal = helper.absolutePos(new BlockPos(1, 1, 1));
        buildPortalPlatform(overworld, sourcePortal, Blocks.STONE.defaultBlockState());
        buildNetherPortal(overworld, sourcePortal);
        int targetX = Math.floorDiv(sourcePortal.getX(), 8);
        int targetZ = Math.floorDiv(sourcePortal.getZ(), 8);
        BlockPos targetPortal = new BlockPos(targetX, 64, targetZ);
        buildNetherPlatform(nether, targetPortal);
        buildNetherPortal(nether, targetPortal);
        // setBlock creates the portal geometry synchronously, while the POI index used by
        // vanilla PortalForcer may still be cold. Validate both fixture POIs before movement.
        overworld.getPoiManager().ensureLoadedAndValid(overworld, sourcePortal, 128);
        nether.getPoiManager().ensureLoadedAndValid(nether, targetPortal, 16);
        ChunkPos destinationChunk = new ChunkPos(targetPortal);
        // Test-only remote ticking: this makes the destination entity-ticking deterministic.
        // It is deliberately removed at the end; it does not prove production remote loading.
        // radius=3 keeps the portal center and its adjacent physical-exit
        // chunks at ENTITY_TICKING. radius=2 only keeps the center there;
        // this fixture's valid exit search deliberately moves 2-3 blocks.
        nether.getChunkSource().addRegionTicket(TicketType.FORCED, destinationChunk, 3, destinationChunk, true);
        // Vanilla may create the return portal at the scaled fixture coordinate when no
        // POI is found. Prepare that possible remote landing area and keep it ticking too.
        buildPortalPlatform(overworld, targetPortal, Blocks.STONE.defaultBlockState());
        ChunkPos returnChunk = new ChunkPos(targetPortal);
        overworld.getChunkSource().addRegionTicket(TicketType.FORCED, returnChunk, 3, returnChunk, true);

        ServerPlayer ticker = helper.makeMockServerPlayerInLevel();
        ticker.teleportTo(nether, (targetX >> 4 << 4) + 8.0, 64.0, (targetZ >> 4 << 4) + 8.0, 0.0F, 0.0F);

        CompanionEntity[] originRef = new CompanionEntity[1];
        UUID[] identityRef = new UUID[1];
        int[] inventoryCountRef = new int[1];
        FoodState[] foodRef = new FoodState[1];
        String[] enterIdRef = new String[1];
        String[] returnIdRef = new String[1];
        int[] destinationTicks = {-1};

        helper.startSequence()
                // Ticket registration is asynchronous. Wait for the official entity-ticking
                // predicate before creating the source body and submitting its portal order.
                // This is test-only remote loading and is not production chunk-loading evidence.
                .thenWaitUntil(() -> {
                    if (!nether.isPositionEntityTicking(targetPortal)) {
                        throw new GameTestAssertException("Nether destination chunk is not entity-ticking yet; chunk=" + destinationChunk
                                + ", gameTime=" + nether.getGameTime());
                    }
                })
                .thenExecute(() -> {
                    // Keep the source body out of the portal while the remote ticket settles.
                    // Otherwise vanilla can transfer it before the PORTAL order exists.
                    CompanionEntity origin = io.github.yufeiyufei888.hearthcrew.runtime.CrewPlayers.create(overworld);
                    if (origin == null) throw new GameTestAssertException("could not construct Nether portal companion");
                    origin.moveTo(sourcePortal.getX() + 0.5, sourcePortal.getY(), sourcePortal.getZ() + 0.5, 0.0F, 0.0F);
                    origin.setRespawnEnabled(false);
                    origin.inventory().setItem(4, new ItemStack(Items.IRON_INGOT, 3));
                    origin.setFoodState(new FoodState(13, 2.5F, 1.0F));
                    originRef[0] = origin;
                    identityRef[0] = origin.companionId();
                    inventoryCountRef[0] = count(origin, Items.IRON_INGOT);
                    foodRef[0] = origin.foodState();
                    enterIdRef[0] = "p0-nether-enter-" + identityRef[0];
                    returnIdRef[0] = "p0-nether-return-" + identityRef[0];
                    if (overworld.getServer().getPlayerList().getPlayer(origin.getUUID()) != origin)
                        throw new GameTestAssertException("portal companion was not registered");
                    origin.executor().submit(enterIdRef[0],
                            new BodyOrder(BodyOrder.Kind.PORTAL, sourcePortal, null, 0), ActionPriority.OWNER);
                })
                .thenWaitUntil(() -> {
                    CompanionEntity current = findCompanion(overworld.getServer(), identityRef[0]);
                    if (current == null || current.level() != nether) {
                        throw new GameTestAssertException("native Nether portal did not transfer companion; body=" + current
                                + ", origin=" + entityDiagnostics(originRef[0]) + ", enterAction=" + actionDiagnostics(current, enterIdRef[0]));
                    }
                    destinationTicks[0] = current.tickCount;
                    if (countCompanions(overworld.getServer(), identityRef[0]) != 1) {
                        throw new GameTestAssertException("Nether transfer did not preserve unique live entity");
                    }
                    if (count(current, Items.IRON_INGOT) != inventoryCountRef[0] || !foodRef[0].equals(current.foodState())) {
                        throw new GameTestAssertException("Nether transfer changed inventory or food state");
                    }
                    if (!isCompleted(current, enterIdRef[0])) {
                        throw new GameTestAssertException("Nether enter action was not COMPLETED: " + actionDiagnostics(current, enterIdRef[0])
                                + ", pos=" + current.position() + ", block=" + current.blockPosition()
                                + ", ground=" + current.onGround() + ", portalCooldown=" + current.isOnPortalCooldown()
                                + ", inBlock=" + current.getInBlockState() + ", bodyTick=" + destinationTicks[0]
                                + ", tickerTick=" + ticker.tickCount);
                    }
                })
                .thenExecute(() -> {
                    CompanionEntity current = findCompanion(overworld.getServer(), identityRef[0]);
                    if (current == null || current.level() != nether) throw new GameTestAssertException("Nether body disappeared before return order");
                    current.executor().submit(returnIdRef[0], new BodyOrder(BodyOrder.Kind.PORTAL, targetPortal, null, 0), ActionPriority.OWNER);
                })
                .thenWaitUntil(() -> {
                    CompanionEntity current = findCompanion(overworld.getServer(), identityRef[0]);
                    if (current == null || current.level() != overworld) {
                        throw new GameTestAssertException("native Nether portal did not return companion; body=" + current
                                + ", origin=" + entityDiagnostics(originRef[0]) + ", returnAction=" + actionDiagnostics(current, returnIdRef[0])
                                + ", bodyTick=" + (current == null ? -1 : current.tickCount)
                                + ", gameTime=" + nether.getGameTime()
                                + ", portalCooldown=" + (current == null ? -1 : current.getPortalCooldown())
                                + ", onPortalCooldown=" + (current != null && current.isOnPortalCooldown())
                                + ", onGround=" + (current != null && current.onGround())
                                + ", inBlock=" + (current == null ? "missing" : current.getInBlockState())
                                + ", targetBlock=" + nether.getBlockState(targetPortal)
                                + ", targetEntityTicking=" + nether.isPositionEntityTicking(targetPortal));
                    }
                    if (countCompanions(overworld.getServer(), identityRef[0]) != 1) {
                        throw new GameTestAssertException("Nether round trip duplicated or lost the companion entity");
                    }
                    if (count(current, Items.IRON_INGOT) != inventoryCountRef[0] || !foodRef[0].equals(current.foodState())) {
                        throw new GameTestAssertException("Nether round trip changed inventory or food state");
                    }
                    if (!isCompleted(current, returnIdRef[0])) {
                        throw new GameTestAssertException("Nether return action was not COMPLETED: " + actionDiagnostics(current, returnIdRef[0]));
                    }
                })
                .thenExecute(() -> {
                    nether.getChunkSource().removeRegionTicket(TicketType.FORCED, destinationChunk, 3, destinationChunk, true);
                    overworld.getChunkSource().removeRegionTicket(TicketType.FORCED, returnChunk, 3, returnChunk, true);
                })
                .thenSucceed();
    }

    @GameTest(template = "p0_empty", timeoutTicks = 1000, batch = "hearthcrew_p0_portal_end")
    public static void endPortalEntryAndNativeExitPreserveBodyState(GameTestHelper helper) {
        ServerLevel overworld = helper.getLevel();
        ServerLevel end = overworld.getServer().getLevel(Level.END);
        if (end == null) helper.fail("End level is unavailable; this gate is not a pass without it");

        floor(helper);
        BlockPos entry = helper.absolutePos(new BlockPos(1, 1, 1));
        overworld.setBlock(entry.below(), Blocks.STONE.defaultBlockState(), 3);
        overworld.setBlock(entry, Blocks.END_PORTAL.defaultBlockState(), 3);
        BlockPos bedFoot = helper.absolutePos(new BlockPos(5, 1, 1));
        BlockState bedFootState = Blocks.RED_BED.defaultBlockState()
                .setValue(BedBlock.FACING, Direction.EAST)
                .setValue(BedBlock.PART, BedPart.FOOT)
                .setValue(BedBlock.OCCUPIED, false);
        overworld.setBlock(bedFoot.below(), Blocks.STONE.defaultBlockState(), 3);
        overworld.setBlock(bedFoot, bedFootState, 3);
        overworld.setBlock(bedFoot.relative(Direction.EAST), bedFootState.setValue(BedBlock.PART, BedPart.HEAD), 3);

        ServerPlayer ticker = helper.makeMockServerPlayerInLevel();
        // Vanilla End portal entry uses ServerLevel.END_SPAWN_POINT (100, 50, 0); tick that region.
        ticker.teleportTo(end, 100.0, 55.0, 0.0, 0.0F, 0.0F);
        CompanionEntity origin = io.github.yufeiyufei888.hearthcrew.runtime.CrewPlayers.create(overworld);
        if (origin == null) helper.fail("could not construct End portal companion");
        origin.moveTo(entry.getX() + 0.5, entry.getY(), entry.getZ() + 0.5, 0.0F, 0.0F);
        origin.setRespawnEnabled(false);
        origin.setRespawnPoint(Level.OVERWORLD, bedFoot);
        origin.inventory().setItem(4, new ItemStack(Items.IRON_INGOT, 5));
        origin.setFoodState(new FoodState(11, 1.5F, 0.5F));
        UUID identity = origin.companionId();
        int inventoryCount = count(origin, Items.IRON_INGOT);
        FoodState food = origin.foodState();
        if (overworld.getServer().getPlayerList().getPlayer(origin.getUUID()) != origin)
            helper.fail("End portal companion was not registered");

        String enterId = "p0-end-enter-" + identity;
        String returnId = "p0-end-return-" + identity;
        origin.executor().submit(enterId, new BodyOrder(BodyOrder.Kind.PORTAL, entry, null, 0), ActionPriority.OWNER);
        BlockPos[] exit = new BlockPos[1];
        helper.startSequence()
                .thenWaitUntil(() -> {
                    CompanionEntity current = findCompanion(overworld.getServer(), identity);
                    if (current == null || current.level() != end) {
                        throw new GameTestAssertException("native End portal did not transfer companion; body=" + current
                                + ", origin=" + entityDiagnostics(origin) + ", enterAction=" + actionDiagnostics(current, enterId));
                    }
                    if (countCompanions(overworld.getServer(), identity) != 1) {
                        throw new GameTestAssertException("End entry did not preserve unique live entity");
                    }
                    if (count(current, Items.IRON_INGOT) != inventoryCount || !food.equals(current.foodState())) {
                        throw new GameTestAssertException("End entry changed inventory or food state");
                    }
                    if (!isCompleted(current, enterId)) {
                        throw new GameTestAssertException("End entry action was not COMPLETED: " + actionDiagnostics(current, enterId));
                    }
                    BlockPos landing = current.blockPosition();
                    exit[0] = landing.east(4);
                    buildEndPlatform(end, landing, exit[0]);
                    end.setBlock(exit[0], Blocks.END_PORTAL.defaultBlockState(), 3);
                })
                .thenExecute(() -> {
                    CompanionEntity current = findCompanion(overworld.getServer(), identity);
                    if (current == null || current.level() != end || exit[0] == null) throw new GameTestAssertException("End body unavailable before return order");
                    current.executor().submit(returnId, new BodyOrder(BodyOrder.Kind.PORTAL, exit[0], null, 0), ActionPriority.OWNER);
                })
                .thenWaitUntil(() -> {
                    CompanionEntity current = findCompanion(overworld.getServer(), identity);
                    if (current == null || current.level() != overworld) {
                        throw new GameTestAssertException("native End exit did not return to respawn dimension; body=" + current
                                + ", origin=" + entityDiagnostics(origin) + ", returnAction=" + actionDiagnostics(current, returnId));
                    }
                    if (countCompanions(overworld.getServer(), identity) != 1) {
                        throw new GameTestAssertException("End round trip duplicated or lost the companion entity");
                    }
                    if (count(current, Items.IRON_INGOT) != inventoryCount || !food.equals(current.foodState())) {
                        throw new GameTestAssertException("End round trip changed inventory or food state");
                    }
                    if (!isCompleted(current, returnId)) {
                        throw new GameTestAssertException("End return action was not COMPLETED: " + actionDiagnostics(current, returnId));
                    }
                })
                .thenSucceed();
    }

    @GameTest(template = "p0_empty", timeoutTicks = 40, batch = "hearthcrew_p0_persistence")
    public static void serializationPreservesCompanionIdentityInventoryAndFood(GameTestHelper helper) {
        floor(helper);
        CompanionEntity body = body(helper, new BlockPos(1, 1, 1));
        UUID owner = UUID.randomUUID();
        body.setOwner(owner);
        body.inventory().setItem(4, new ItemStack(Items.IRON_INGOT, 7));
        body.setFoodState(new FoodState(9, 2.0F, 1.0F));
        UUID identity = body.companionId();
        CompoundTag tag = new CompoundTag();
        body.addAdditionalSaveData(tag);
        CompanionEntity restored = io.github.yufeiyufei888.hearthcrew.runtime.CrewPlayers.create(helper.getLevel());
        if (restored == null) helper.fail("could not construct restoration body");
        restored.readAdditionalSaveData(tag);
        if (!identity.equals(restored.companionId()) || !owner.equals(restored.ownerId())) helper.fail("identity or owner did not survive serialization");
        if (count(restored, Items.IRON_INGOT) != 7 || restored.foodState().foodLevel() != 9) helper.fail("inventory or food state did not survive serialization");
        helper.succeedWhen(() -> {});
    }

    private static CompanionEntity body(GameTestHelper helper, BlockPos position) {
        CompanionEntity body = io.github.yufeiyufei888.hearthcrew.gametest.PlayerTestBodies.spawn(helper, position);
        body.setRespawnEnabled(false);
        return body;
    }

    private static void floor(GameTestHelper helper) {
        for (int x = 0; x < 5; x++) for (int z = 0; z < 5; z++) helper.setBlock(new BlockPos(x, 0, z), Blocks.STONE);
    }

    private static int count(CompanionEntity body, Item item) {
        int total = 0;
        for (int i = 0; i < body.inventory().getContainerSize(); i++) if (body.inventory().getItem(i).is(item)) total += body.inventory().getItem(i).getCount();
        return total;
    }

    private static CompanionEntity findCompanion(MinecraftServer server, UUID identity) {
        for (ServerLevel level : server.getAllLevels()) {
            for (var entity : level.getEntities().getAll()) {
                if (entity instanceof CompanionEntity body && body.companionId().equals(identity)) return body;
            }
        }
        return null;
    }

    private static int countCompanions(MinecraftServer server, UUID identity) {
        int count = 0;
        for (ServerLevel level : server.getAllLevels()) {
            for (var entity : level.getEntities().getAll()) {
                if (entity instanceof CompanionEntity body && body.isAlive() && body.companionId().equals(identity)) count++;
            }
        }
        return count;
    }

    private static boolean isCompleted(CompanionEntity body, String id) {
        return body != null && body.executor().arbiter().snapshot(ActionId.of(id))
                .map(snapshot -> snapshot.state() == ActionState.COMPLETED).orElse(false);
    }

    private static void buildNetherPortal(ServerLevel level, BlockPos lowerLeftInterior) {
        BlockState portal = Blocks.NETHER_PORTAL.defaultBlockState()
                .setValue(net.minecraft.world.level.block.NetherPortalBlock.AXIS, Direction.Axis.X);
        // Complete the frame before placing portal blocks so updateShape cannot delete a partial portal.
        for (int x = -1; x <= 2; x++) for (int y = -1; y <= 3; y++) {
            if (x == -1 || x == 2 || y == -1 || y == 3) {
                level.setBlock(lowerLeftInterior.offset(x, y, 0), Blocks.OBSIDIAN.defaultBlockState(), 3);
            }
        }
        for (int x = -1; x <= 2; x++) for (int y = -1; y <= 3; y++) {
            if (x >= 0 && x <= 1 && y >= 0 && y <= 2) {
                level.setBlock(lowerLeftInterior.offset(x, y, 0), portal, 3);
            }
        }
    }

    private static void buildNetherPlatform(ServerLevel level, BlockPos lowerLeftInterior) {
        for (int x = -12; x <= 12; x++) for (int z = -12; z <= 12; z++) {
            level.setBlock(new BlockPos(lowerLeftInterior.getX() + x, lowerLeftInterior.getY() - 1, lowerLeftInterior.getZ() + z), Blocks.NETHERRACK.defaultBlockState(), 3);
            for (int y = 0; y <= 5; y++) level.setBlock(new BlockPos(lowerLeftInterior.getX() + x, lowerLeftInterior.getY() + y, lowerLeftInterior.getZ() + z), Blocks.AIR.defaultBlockState(), 3);
        }
    }

    private static void buildPortalPlatform(ServerLevel level, BlockPos portal, BlockState floor) {
        for (int x = -6; x <= 6; x++) for (int z = -6; z <= 6; z++) {
            level.setBlock(new BlockPos(portal.getX() + x, portal.getY() - 1, portal.getZ() + z), floor, 3);
            for (int y = 0; y <= 5; y++) level.setBlock(new BlockPos(portal.getX() + x, portal.getY() + y, portal.getZ() + z), Blocks.AIR.defaultBlockState(), 3);
        }
    }

    private static void buildEndPlatform(ServerLevel level, BlockPos landing, BlockPos exit) {
        int minX = Math.min(landing.getX(), exit.getX()) - 8;
        int maxX = Math.max(landing.getX(), exit.getX()) + 8;
        int minZ = Math.min(landing.getZ(), exit.getZ()) - 8;
        int maxZ = Math.max(landing.getZ(), exit.getZ()) + 8;
        int floorY = landing.getY() - 1;
        for (int x = minX; x <= maxX; x++) for (int z = minZ; z <= maxZ; z++) {
            level.setBlock(new BlockPos(x, floorY, z), Blocks.END_STONE.defaultBlockState(), 3);
            for (int y = floorY + 1; y <= floorY + 5; y++) level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 3);
        }
    }

    private static int countDrops(GameTestHelper helper, Item item) {
        int total = 0;
        for (var entity : helper.getLevel().getEntities(EntityType.ITEM, helper.getBounds(), e -> e.isAlive())) {
            if (entity.getItem().is(item)) total += entity.getItem().getCount();
        }
        return total;
    }

    private static String itemDiagnostics(GameTestHelper helper) {
        var items = helper.getLevel().getEntities(EntityType.ITEM, helper.getBounds(), e -> e.isAlive());
        if (items.isEmpty()) return "none";
        return items.stream().map(e -> e.getUUID() + "@" + e.position() + "=" + e.getItem()).toList().toString();
    }

    private static String actionDiagnostics(CompanionEntity body, String id) {
        if (body == null) return "body-missing";
        return body.executor().arbiter().snapshot(ActionId.of(id))
                .map(snapshot -> snapshot.state() + ":" + snapshot.message()).orElse("missing");
    }

    private static String entityDiagnostics(CompanionEntity body) {
        return "removed=" + body.isRemoved() + ",reason=" + body.getRemovalReason() + ",alive=" + body.isAlive()
                + ",health=" + body.getHealth() + ",pos=" + body.position() + ",dim=" + body.level().dimension().location()
                + ",uuid=" + body.getUUID() + ",identity=" + body.companionId();
    }
}
