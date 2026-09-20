package io.github.yufeiyufei888.hearthcrew.client;

import io.github.yufeiyufei888.hearthcrew.HearthCrew;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/** Controlled rendering fixture. Never enabled by the shipped game or counted as survival evidence. */
@EventBusSubscriber(modid = HearthCrew.ID, value = Dist.CLIENT)
public final class DevelopmentVisualFixture {
    private static boolean setup;
    private static int frames;
    @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
        boolean persistence = Boolean.getBoolean("hearthcrew.persistenceTest");
        if (!Boolean.getBoolean("hearthcrew.visualTest") && !persistence) return;
        Minecraft client = Minecraft.getInstance();
        var server = client.getSingleplayerServer();
        if (server == null || client.player == null || client.level == null || client.screen != null) return;
        var save = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        if (!save.getFileName().toString().equals("P0Visual")) return;
        if (persistence) {
            if (++frames == 140) server.execute(() -> verifySavedBodies(server, save));
            return;
        }
        if (!setup) {
            setup = true;
            server.execute(() -> {
                var level = server.overworld();
                level.setDayTime(6000);
                level.getGameRules().getRule(net.minecraft.world.level.GameRules.RULE_DOMOBSPAWNING).set(false, server);
                for (int x = -10; x <= 10; x++) for (int z = -8; z <= 12; z++) {
                    level.setBlock(new BlockPos(x, 100, z), Blocks.OAK_PLANKS.defaultBlockState(), 3);
                    for (int y = 101; y < 106; y++) level.setBlock(new BlockPos(x, y, z), Blocks.AIR.defaultBlockState(), 3);
                }
                var player = server.getPlayerList().getPlayer(client.player.getUUID());
                if (player == null) return;
                player.teleportTo(level, 0.5, 101, 7.5, 180, 8);
                String[] names = {"炉火 · 协调防卫", "远溪 · 探索采集", "青砾 · 建设后勤"};
                var held = new net.minecraft.world.item.Item[] {Items.IRON_SWORD, Items.IRON_PICKAXE, Items.TORCH};
                for (int i = 0; i < 3; i++) {
                    var body = io.github.yufeiyufei888.hearthcrew.runtime.CrewPlayers.create(level);
                    if (body == null) continue;
                    body.setOwner(player.getUUID()); body.setSkinIndex(i);
                    body.setCustomName(Component.literal(names[i])); body.setCustomNameVisible(true);
                    body.moveTo(-2.0 + i * 2.5, 101, 0.5, 0, 0);
                    body.setYHeadRot(0); body.yBodyRot = 0;
                    body.inventory().setItem(0, new ItemStack(held[i]));
                    if (i == 0) body.setItemSlot(EquipmentSlot.CHEST, new ItemStack(Items.IRON_CHESTPLATE));
                    body.setRespawnEnabled(false); level.addFreshEntity(body);
                    body.executor().stop("development rendering fixture");
                }
                level.setBlock(new BlockPos(-4, 101, -2), Blocks.CRAFTING_TABLE.defaultBlockState(), 3);
                level.setBlock(new BlockPos(4, 101, -2), Blocks.CHEST.defaultBlockState(), 3);
            });
        }
        if (++frames == 140) {
            Screenshot.grab(client.gameDirectory, "hearthcrew-p0-visual.png", client.getMainRenderTarget(), message ->
                    client.execute(() -> client.gui.getChat().addMessage(Component.literal("HearthCrew P0 FIXTURE: ").append(message))));
        }
    }

    /** Reads bodies actually loaded by a fresh client process; does not rebuild the scene. */
    private static void verifySavedBodies(net.minecraft.server.MinecraftServer server, java.nio.file.Path save) {
        var report = new com.google.gson.JsonObject(); var observations = new com.google.gson.JsonArray();
        boolean passed = true;
        String[] names = {"炉火 · 协调防卫", "远溪 · 探索采集", "青砾 · 建设后勤"};
        var expectedItems = new net.minecraft.world.item.Item[] {Items.IRON_SWORD, Items.IRON_PICKAXE, Items.TORCH};
        var bodies = io.github.yufeiyufei888.hearthcrew.runtime.CrewWorldData.liveCompanions(server);
        for (int i = 0; i < names.length; i++) {
            String name = names[i];
            var matches = bodies.stream().filter(body -> body.getDisplayName().getString().equals(name)).toList();
            if (matches.size() != 1) { passed = false; continue; }
            var body = matches.getFirst();
            boolean valid = body.skinIndex() == i && body.getMainHandItem().is(expectedItems[i])
                    && body.getHealth() > 0 && body.ownerId() != null && body.level().dimension() == net.minecraft.world.level.Level.OVERWORLD;
            if (i == 0) valid &= body.getItemBySlot(EquipmentSlot.CHEST).is(Items.IRON_CHESTPLATE);
            passed &= valid;
            var entry = new com.google.gson.JsonObject(); entry.addProperty("name", name);
            entry.addProperty("companionId", body.companionId().toString()); entry.addProperty("skin", body.skinIndex());
            entry.addProperty("held", body.getMainHandItem().getItem().toString()); entry.addProperty("health", body.getHealth());
            entry.addProperty("food", body.foodLevel()); entry.addProperty("valid", valid); observations.add(entry);
        }
        report.addProperty("evidence", "REAL_CLIENT_FIXTURE_RELOAD"); report.addProperty("passed", passed);
        report.addProperty("sceneRebuilt", false); report.add("bodies", observations);
        try {
            java.nio.file.Files.writeString(save.resolve("hearthcrew-persistence-report.json"), new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(report));
            org.slf4j.LoggerFactory.getLogger(DevelopmentVisualFixture.class).info("HearthCrew persistence fixture: {}", passed ? "PASS" : "FAIL");
        } catch (java.io.IOException error) {
            org.slf4j.LoggerFactory.getLogger(DevelopmentVisualFixture.class).error("Cannot save fixture evidence", error);
        }
    }
}
