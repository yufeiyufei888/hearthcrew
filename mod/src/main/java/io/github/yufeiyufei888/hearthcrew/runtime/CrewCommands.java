package io.github.yufeiyufei888.hearthcrew.runtime;

import com.mojang.brigadier.arguments.StringArgumentType;
import io.github.yufeiyufei888.hearthcrew.HearthCrew;
import io.github.yufeiyufei888.hearthcrew.entity.*;
import io.github.yufeiyufei888.hearthcrew.kernel.ActionPriority;
import java.util.*;
import net.minecraft.commands.Commands;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

public final class CrewCommands {
    public static void register(RegisterCommandsEvent event) {
        if(io.github.yufeiyufei888.hearthcrew.backend.BackendWorld.enabled()){BackendCommands.register(event);return;}
        event.getDispatcher().register(Commands.literal("hearthcrew")
                .executes(ctx -> {ctx.getSource().sendSuccess(() -> Component.translatable("hearthcrew.command.development"), false); return status(ctx.getSource());})
                .then(Commands.literal("join").then(Commands.argument("name", StringArgumentType.word()).executes(ctx -> {
                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                    CrewWorldData data = CrewWorldData.get(player.getServer());
                    if (CrewWorldData.liveCompanions(player.getServer()).size() >= CompanionChunks.MAX_COMPANIONS
                            || data.chunkAnchors().size() >= CompanionChunks.MAX_COMPANIONS) {
                        ctx.getSource().sendFailure(Component.literal("伙伴区块票据容量已满，无法加入新伙伴")); return 0;
                    }
                    if (data.chunkRecoveryInvalid()) {
                        ctx.getSource().sendFailure(Component.literal("伙伴区块登记数据无效，已安全停止加入")); return 0;
                    }
                    String name=StringArgumentType.getString(ctx,"name");
                    if(!Set.of("Ember","Moss","Flint").contains(name)){ctx.getSource().sendFailure(Component.literal("伙伴名称为 Ember、Moss、Flint"));return 0;}
                    var pos=player.position().add(1.5,0,0);
                    if(!player.serverLevel().noCollision(player,player.getBoundingBox().move(1.5,0,0))){ctx.getSource().sendFailure(Component.literal("附近没有安全站位"));return 0;}
                    CompanionEntity body;
                    try{body=CrewPlayers.create(player.serverLevel(),name,player.getUUID(),pos);WorldEvents.trackCompanion(body);}
                    catch(RuntimeException error){ctx.getSource().sendFailure(Component.literal("伙伴加入失败："+error.getMessage()));return 0;}
                    ctx.getSource().sendSuccess(() -> Component.translatable("hearthcrew.command.created", body.getDisplayName()), false); return 1;
                })))
                .then(Commands.literal("status").executes(ctx -> status(ctx.getSource())))
                .then(Commands.literal("public").then(Commands.argument("position",BlockPosArgument.blockPos()).executes(ctx->{var player=ctx.getSource().getPlayerOrException();var pos=BlockPosArgument.getLoadedBlockPos(ctx,"position");if(player.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(pos))>64){ctx.getSource().sendFailure(Component.literal("请走近容器后标记"));return 0;}var block=player.level().getBlockEntity(pos);if(!(block instanceof net.minecraft.world.Container)){ctx.getSource().sendFailure(Component.literal("该位置不是容器"));return 0;}block.getPersistentData().putBoolean("HearthCrewPublic",true);block.setChanged();ctx.getSource().sendSuccess(()->Component.literal("已将该容器标为伙伴公共设施"),false);return 1;})))
                .then(Commands.literal("locate").then(Commands.argument("name", StringArgumentType.word())
                        .suggests((ctx, builder) -> net.minecraft.commands.SharedSuggestionProvider.suggest(
                                companions(ctx.getSource().getPlayerOrException()).stream().map(b -> b.getName().getString()), builder))
                        .executes(ctx -> locate(ctx.getSource(), StringArgumentType.getString(ctx, "name"), false))))
                .then(Commands.literal("tp").requires(source -> source.hasPermission(2))
                        .then(Commands.argument("name", StringArgumentType.word())
                        .suggests((ctx, builder) -> net.minecraft.commands.SharedSuggestionProvider.suggest(
                                companions(ctx.getSource().getPlayerOrException()).stream().map(b -> b.getName().getString()), builder))
                        .executes(ctx -> locate(ctx.getSource(), StringArgumentType.getString(ctx, "name"), true))))
                .then(Commands.literal("stop").executes(ctx -> {
                    ServerPlayer player = ctx.getSource().getPlayerOrException();
                    for (var body : companions(player)) {
                        WorldEvents.controlBody(body, "stop");
                    }
                    ctx.getSource().sendSuccess(() -> Component.translatable("hearthcrew.command.stopped"), false); return 1;
                }))
                .then(Commands.literal("resume").executes(ctx -> { for (var body : companions(ctx.getSource().getPlayerOrException())) WorldEvents.controlBody(body, "resume"); return 1; }))
                .then(Commands.literal("autonomy")
                        .then(Commands.literal("on").executes(ctx -> setAutonomy(ctx.getSource(), true)))
                        .then(Commands.literal("off").executes(ctx -> setAutonomy(ctx.getSource(), false))))
                .then(Commands.literal("standby").executes(ctx -> setStandby(ctx.getSource())))
                .then(Commands.literal("follow").executes(ctx -> submit(ctx.getSource(), BodyOrder.follow(ctx.getSource().getPlayerOrException().getUUID()))))
                .then(Commands.literal("move").then(Commands.argument("position", BlockPosArgument.blockPos()).executes(ctx ->
                        submit(ctx.getSource(), BodyOrder.move(BlockPosArgument.getLoadedBlockPos(ctx, "position"))))))
                .then(Commands.literal("mine").then(Commands.argument("position", BlockPosArgument.blockPos()).executes(ctx ->
                        submit(ctx.getSource(), BodyOrder.mine(BlockPosArgument.getLoadedBlockPos(ctx, "position")))))));
    }
    private static List<CompanionEntity> companions(ServerPlayer player) {
        return CrewWorldData.liveCompanions(player.getServer()).stream().filter(body -> player.getUUID().equals(body.ownerId())).toList();
    }
    private static int locate(CommandSourceStack source, String name, boolean teleport) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        var matches = companions(player).stream().filter(b -> b.getName().getString().equalsIgnoreCase(name)).toList();
        if (matches.size() != 1) {
            source.sendFailure(Component.literal(matches.isEmpty() ? "未找到已加载的己方伙伴：" + name : "伙伴重名，请使用 /tp 的 UUID 或实体选择器"));
            return 0;
        }
        CompanionEntity body = matches.getFirst();
        var level = (net.minecraft.server.level.ServerLevel)body.level();
        source.sendSuccess(() -> Component.literal(body.getName().getString() + " · " + level.dimension().location()
                + " · " + body.blockPosition().toShortString() + " · UUID " + body.getUUID()), false);
        if (!teleport) return 1;
        if (!body.isAlive()) { source.sendFailure(Component.literal("伙伴正在死亡或重生，请稍后重试")); return 0; }
        // Explicit player cheat only. Never moves a companion or changes its task/inventory.
        for (int radius = 1; radius <= 4; radius++) for (int dy = 2; dy >= -2; dy--)
            for (int dx = -radius; dx <= radius; dx++) for (int dz = -radius; dz <= radius; dz++) {
                if (Math.max(Math.abs(dx), Math.abs(dz)) != radius) continue;
                var feet = body.blockPosition().offset(dx, dy, dz);
                if (!level.hasChunkAt(feet) || !level.getWorldBorder().isWithinBounds(feet)
                        || !level.getBlockState(feet.below()).isFaceSturdy(level, feet.below(), net.minecraft.core.Direction.UP)) continue;
                boolean hazard = false;
                for (var pos : List.of(feet.below(), feet, feet.above())) {
                    var state = level.getBlockState(pos);
                    if (!level.getFluidState(pos).isEmpty() || state.is(net.minecraft.world.level.block.Blocks.FIRE)
                            || state.is(net.minecraft.world.level.block.Blocks.SOUL_FIRE) || state.is(net.minecraft.world.level.block.Blocks.MAGMA_BLOCK)
                            || state.is(net.minecraft.world.level.block.Blocks.CACTUS) || state.is(net.minecraft.world.level.block.Blocks.CAMPFIRE)
                            || state.is(net.minecraft.world.level.block.Blocks.SOUL_CAMPFIRE) || state.is(net.minecraft.world.level.block.Blocks.POWDER_SNOW)) hazard = true;
                }
                var point = net.minecraft.world.phys.Vec3.atBottomCenterOf(feet);
                if (hazard || !level.noCollision(player, player.getBoundingBox().move(point.subtract(player.position())))) continue;
                player.teleportTo(level, point.x, point.y, point.z, player.getYRot(), player.getXRot());
                player.fallDistance = 0;
                source.sendSuccess(() -> Component.literal("已传送到 " + body.getName().getString() + " 附近；这是手动传送，不计入正常生存验收"), false);
                return 1;
            }
        source.sendFailure(Component.literal("伙伴附近没有可确认的安全落脚点，未传送")); return 0;
    }
    private static int submit(CommandSourceStack source, BodyOrder order) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        var crew = companions(player);
        if (crew.isEmpty()) { source.sendFailure(Component.translatable("hearthcrew.command.no_companion")); return 0; }
        var selected = crew.getFirst();
        WorldEvents.controlBody(selected, "retask");
        var receipt = selected.executor().submit(UUID.randomUUID().toString(), order, ActionPriority.OWNER);
        source.sendSuccess(() -> Component.translatable("hearthcrew.command.accepted", receipt.decision().name()), false); return 1;
    }
    private static int setAutonomy(CommandSourceStack source, boolean enabled) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        List<CompanionEntity> crew = companions(player);
        if (crew.isEmpty()) { source.sendFailure(Component.translatable("hearthcrew.command.no_companion")); return 0; }
        for (CompanionEntity body : crew) WorldEvents.controlBody(body, enabled ? "auto_on" : "auto_off");
        source.sendSuccess(() -> Component.literal(enabled ? "炉火伙伴自主游玩已开启" : "炉火伙伴自主游玩已关闭"), false);
        return 1;
    }
    private static int setStandby(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        ServerPlayer player = source.getPlayerOrException();
        List<CompanionEntity> crew = companions(player);
        if (crew.isEmpty()) { source.sendFailure(Component.translatable("hearthcrew.command.no_companion")); return 0; }
        for (CompanionEntity body : crew) WorldEvents.controlBody(body, "standby");
        source.sendSuccess(() -> Component.literal("炉火伙伴已进入待命"), false);
        return 1;
    }
    private static int status(CommandSourceStack source) throws com.mojang.brigadier.exceptions.CommandSyntaxException {
        for (var body : companions(source.getPlayerOrException())) source.sendSuccess(() -> Component.translatable("hearthcrew.command.status", body.getDisplayName(), body.getHealth(), body.foodLevel(),
                body.executor().arbiter().activeSnapshot().map(snapshot -> snapshot.payload().kind().name()).orElse("IDLE")), false);
        return 1;
    }
}
