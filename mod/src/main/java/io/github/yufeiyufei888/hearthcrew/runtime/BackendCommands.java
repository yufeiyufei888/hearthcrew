package io.github.yufeiyufei888.hearthcrew.runtime;

import io.github.yufeiyufei888.hearthcrew.backend.*;
import io.github.yufeiyufei888.hearthcrew.entity.BodyOrder;
import io.github.yufeiyufei888.hearthcrew.kernel.ActionPriority;
import net.minecraft.commands.*;
import net.minecraft.commands.arguments.coordinates.BlockPosArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import com.mojang.brigadier.arguments.StringArgumentType;
import java.util.*;

/** Local controls use the same registered bodies and action ledger as the model transport. */
final class BackendCommands {
    static void register(RegisterCommandsEvent event){
        var root=Commands.literal("hearthcrew").executes(c->status(c.getSource()));
        root.then(Commands.literal("join").executes(c->join(c.getSource(),null)).then(Commands.argument("name",StringArgumentType.word())
            .suggests((c,b)->SharedSuggestionProvider.suggest(BackendRoster.NAMES,b)).executes(c->join(c.getSource(),StringArgumentType.getString(c,"name")))));
        root.then(Commands.literal("status").executes(c->status(c.getSource())));
        for(String visibility:List.of("public","private"))root.then(Commands.literal(visibility).then(Commands.argument("position",BlockPosArgument.blockPos()).executes(c->{
            var owner=c.getSource().getPlayerOrException();var p=BlockPosArgument.getLoadedBlockPos(c,"position");
            if(owner.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(p))>64){c.getSource().sendFailure(Component.literal("请走近设施后标记"));return 0;}
            // In a local owner world this is the player's explicit grant. On a shared
            // server a non-operator may publish only their recorded placement.
            String placement=CrewWorldData.get(owner.server).placementOwner(owner.level(),p);
            if(!owner.server.isSingleplayerOwner(owner.getGameProfile())&&!c.getSource().hasPermission(2)
                &&!Objects.equals(placement,"player:"+owner.getUUID())){c.getSource().sendFailure(Component.literal("只能标记自己的设施，或由管理员授权"));return 0;}
            try{BackendWorld.provider().publishFacility(owner.serverLevel(),p,visibility.equals("public"));}
            catch(RuntimeException e){c.getSource().sendFailure(Component.literal("设施标记失败："+e.getMessage()));return 0;}
            c.getSource().sendSuccess(()->Component.literal(visibility.equals("public")?"该设施已允许伙伴使用；拆除保护保留，双箱须分别授权两半":"已取消该设施的伙伴使用许可"),false);return 1;
        })));
        for(String operation:List.of("stop","pause","resume","standby"))root.then(Commands.literal(operation).executes(c->control(c.getSource(),operation)));
        root.then(Commands.literal("autonomy").then(Commands.literal("on").executes(c->control(c.getSource(),"auto_on"))).then(Commands.literal("off").executes(c->control(c.getSource(),"auto_off"))));
        root.then(Commands.literal("move").then(Commands.argument("position",BlockPosArgument.blockPos()).executes(c->{
            var owner=c.getSource().getPlayerOrException();var owned=owned(owner);if(owned.isEmpty())return missing(c.getSource());
            var b=owned.getFirst();BackendWorld.control(owner.server,b.body().getUUID(),"retask");
            var receipt=BackendActions.get(owner.server).dispatch(owner.server,b,new CompanionBackend.Request(b.snapshot().identity(),UUID.randomUUID().toString(),BodyOrder.move(BlockPosArgument.getLoadedBlockPos(c,"position")),ActionPriority.OWNER));
            c.getSource().sendSuccess(()->Component.literal("移动请求："+receipt.state()+"；实际结果见任务页"),false);return 1;
        })));
        event.getDispatcher().register(root);
    }
    private static List<CompanionBackend> owned(ServerPlayer owner){return BackendWorld.live(owner.server).stream().filter(b->BackendWorld.roster(owner.server).member(b.body().getUUID()).owner().equals(owner.getUUID())).toList();}
    private static int missing(CommandSourceStack s){s.sendFailure(Component.literal("当前没有已加入的己方伙伴"));return 0;}
    private static int join(CommandSourceStack s,String name)throws com.mojang.brigadier.exceptions.CommandSyntaxException{
        try{int count=BackendWorld.join(s.getPlayerOrException(),name);s.sendSuccess(()->Component.literal("已加入 "+count+" 名伙伴；原版 /tp、/kill 可以直接使用伙伴名字"),false);return count;}
        catch(RuntimeException e){s.sendFailure(Component.literal("加入未完成："+e.getMessage()));return 0;}
    }
    private static int control(CommandSourceStack s,String op)throws com.mojang.brigadier.exceptions.CommandSyntaxException{
        var owner=s.getPlayerOrException();var bodies=owned(owner);if(bodies.isEmpty())return missing(s);
        for(var b:bodies)BackendWorld.control(owner.server,b.body().getUUID(),op);s.sendSuccess(()->Component.literal("伙伴控制已更新："+op),false);return bodies.size();
    }
    private static int status(CommandSourceStack s)throws com.mojang.brigadier.exceptions.CommandSyntaxException{
        var owner=s.getPlayerOrException();if(!BackendWorld.fault(owner.server).isEmpty()){s.sendFailure(Component.literal(BackendWorld.fault(owner.server)));return 0;}
        var bodies=owned(owner);for(var b:bodies)s.sendSuccess(()->Component.literal(b.body().getGameProfile().getName()+" · "+(int)b.body().getHealth()+" 生命 · "+b.snapshot().state()+" · "+b.backendId()),false);return bodies.size();
    }
}
