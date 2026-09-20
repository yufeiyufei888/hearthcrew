package io.github.yufeiyufei888.hearthcrew.client;

import io.github.yufeiyufei888.hearthcrew.HearthCrew;
import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameRules;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.ClientTickEvent;

/** Opt-in launcher for a fresh, isolated P2 team fixture world. */
@EventBusSubscriber(modid = HearthCrew.ID, value = Dist.CLIENT)
public final class DevelopmentTeamBootstrap {
    private static boolean opened;

    private DevelopmentTeamBootstrap() {}

    @SubscribeEvent
    public static void tick(ClientTickEvent.Post event) {
        if (opened || !Boolean.getBoolean(DevelopmentTeamFixture.ENABLE_PROPERTY)) return;
        Minecraft client = Minecraft.getInstance();
        if (client.level != null || !(client.screen instanceof TitleScreen)) return;
        String expected = System.getProperty(DevelopmentTeamFixture.DIRECTORY_PROPERTY, "").trim();
        String runId = System.getProperty(DevelopmentTeamFixture.RUN_ID_PROPERTY, "").trim();
        if (expected.isBlank() || !DevelopmentTeamFixture.isSafeRunId(runId)) return;

        Path actual = client.gameDirectory.toPath().toAbsolutePath().normalize();
        Path requested = Path.of(expected).toAbsolutePath().normalize();
        if (!actual.equals(requested)) throw new IllegalStateException("team fixture client directory mismatch");

        opened = true;
        client.options.pauseOnLostFocus = false;
        String saveName = DevelopmentTeamFixture.saveName(runId);
        Path save = actual.resolve("saves").resolve(saveName).normalize();
        // A P2 run is deliberately a new survival world.  Reopening an old
        // save here would make an old body/action journal look like a new run.
        if (Files.exists(save)) {
            DevelopmentTeamFixture.logExistingSaveRefusal(save, runId);
            return;
        }
        var settings = new LevelSettings(saveName, GameType.SURVIVAL, false, Difficulty.NORMAL, false,
                new GameRules(), WorldDataConfiguration.DEFAULT);
        client.createWorldOpenFlows().createFreshLevel(saveName, settings,
                new WorldOptions(new java.util.Random().nextLong(), true, false),
                registries -> registries.registryOrThrow(Registries.WORLD_PRESET)
                        .getHolderOrThrow(WorldPresets.FLAT).value().createWorldDimensions(),
                new TitleScreen());
    }
}
