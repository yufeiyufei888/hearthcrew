package io.github.yufeiyufei888.hearthcrew.client;

import io.github.yufeiyufei888.hearthcrew.HearthCrew;
import io.github.yufeiyufei888.hearthcrew.entity.LegacyCompanionEntity;
import net.minecraft.client.model.HumanoidModel;
import net.minecraft.client.model.PlayerModel;
import net.minecraft.client.model.geom.ModelLayers;
import net.minecraft.client.renderer.entity.EntityRendererProvider;
import net.minecraft.client.renderer.entity.HumanoidMobRenderer;
import net.minecraft.client.renderer.entity.layers.HumanoidArmorLayer;
import net.minecraft.resources.ResourceLocation;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.EntityRenderersEvent;

@EventBusSubscriber(modid = HearthCrew.ID, bus = EventBusSubscriber.Bus.MOD, value = Dist.CLIENT)
public final class CrewClient {
    @SubscribeEvent
    public static void registerKeys(net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent event) {
        io.github.yufeiyufei888.hearthcrew.client.ui.ClientUi.registerKeys(event);
    }
    @SubscribeEvent
    public static void registerRenderers(EntityRenderersEvent.RegisterRenderers event) {
        event.registerEntityRenderer(HearthCrew.COMPANION.get(), Renderer::new);
    }

    private static final class Renderer extends HumanoidMobRenderer<LegacyCompanionEntity, PlayerModel<LegacyCompanionEntity>> {
        Renderer(EntityRendererProvider.Context context) {
            super(context, new PlayerModel<>(context.bakeLayer(ModelLayers.PLAYER), false), 0.5F);
            addLayer(new HumanoidArmorLayer<>(this, new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER_INNER_ARMOR)),
                    new HumanoidModel<>(context.bakeLayer(ModelLayers.PLAYER_OUTER_ARMOR)), context.getModelManager()));
        }
        @Override public ResourceLocation getTextureLocation(LegacyCompanionEntity entity) {
            return HearthCrew.id("textures/entity/companion_" + entity.skinIndex() + ".png");
        }
    }
}
