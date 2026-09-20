package io.github.yufeiyufei888.hearthcrew.client;

import io.github.yufeiyufei888.hearthcrew.HearthCrew;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.*;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/** Opt-in test bootstrap through vanilla world creation, excluded from the shipped JAR. */
@EventBusSubscriber(modid = HearthCrew.ID, value = Dist.CLIENT)
public final class DevelopmentModelBootstrap {
    private static boolean opened;
    @SubscribeEvent public static void tick(ClientTickEvent.Post event) {
        if (opened || !Boolean.getBoolean("hearthcrew.modelTest")) return;
        Minecraft client = Minecraft.getInstance();
        if (client.level != null || !(client.screen instanceof TitleScreen)) return;
        String expected = System.getProperty("hearthcrew.fixtureDirectory", "");
        String runId = System.getProperty("hearthcrew.modelRunId", "");
        if (expected.isBlank() || !runId.matches("[A-Za-z0-9-]{1,48}")) return;
        Path actual = client.gameDirectory.toPath().toAbsolutePath().normalize();
        if (!actual.equals(Path.of(expected).toAbsolutePath().normalize())) throw new IllegalStateException("fixture client directory mismatch");
        opened = true;
        client.options.pauseOnLostFocus = false;
        String saveName = DevelopmentModelFixture.saveName(runId);
        // Existing test worlds are opened unchanged; the fixture itself refuses
        // to replace prior run evidence. Never overwrite or copy another save.
        if (Files.exists(actual.resolve("saves").resolve(saveName))) {
            client.createWorldOpenFlows().openWorld(saveName, () -> client.setScreen(new TitleScreen()));
        } else {
            var settings = new LevelSettings(saveName, GameType.SURVIVAL, false, Difficulty.NORMAL, false,
                    new GameRules(), WorldDataConfiguration.DEFAULT);
            client.createWorldOpenFlows().createFreshLevel(saveName, settings,
                    new WorldOptions(new java.util.Random().nextLong(), true, false),
                    registries -> registries.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions(),
                    new TitleScreen());
        }
    }
}
