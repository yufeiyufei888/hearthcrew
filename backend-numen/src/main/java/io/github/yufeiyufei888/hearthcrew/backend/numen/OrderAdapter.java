package io.github.yufeiyufei888.hearthcrew.backend.numen;

import io.github.yufeiyufei888.hearthcrew.entity.BodyOrder;
import com.dwinovo.numen.task.TaskRecord;
import com.dwinovo.numen.core.task.move.MoveToTaskRecord;
import com.dwinovo.numen.core.task.build.BuildTaskRecord;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.Block;
import java.util.*;

/** Explicit protocol mapping. Unadapted abilities never fall back to another executor. */
final class OrderAdapter {
    static final Set<BodyOrder.Kind> CAPABILITIES=Set.of(BodyOrder.Kind.MOVE,BodyOrder.Kind.EXCAVATE,BodyOrder.Kind.COLLECT_RESOURCE,BodyOrder.Kind.CRAFT,
        BodyOrder.Kind.PLACE,BodyOrder.Kind.BUILD,BodyOrder.Kind.STORE,BodyOrder.Kind.TAKE,BodyOrder.Kind.PROCESS,BodyOrder.Kind.COLLECT_PROCESS);
    static TaskRecord convert(NumenBackend backend,String id,BodyOrder order) {
        if(!CAPABILITIES.contains(order.kind()))throw new IllegalArgumentException("BACKEND_ACTION_UNAVAILABLE:"+order.kind()+"; supported="+CAPABILITIES);
        if(order.target()!=null)throw new IllegalArgumentException("target is not used by this action");
        if(order.radius()!=32&&order.kind()!=BodyOrder.Kind.COLLECT_RESOURCE&&order.kind()!=BodyOrder.Kind.CRAFT)throw new IllegalArgumentException("Only resource/craft tasks accept a smaller preparation scope");
        var prep=order.preparation();
        var body=backend.body();long deadline=body.level().getGameTime()+Math.min(24000,2400+Math.max(1,order.count())*300L);
        return switch(order.kind()) {
            case MOVE -> {
                position(order.position());unusedCount(order);if(order.resource()!=null)throw new IllegalArgumentException("MOVE does not use resource");
                var p=order.position();yield new MoveToTaskRecord(id,deadline,p.getX()+.5,(double)p.getY(),p.getZ()+.5,null,false);
            }
            case EXCAVATE -> {
                position(order.position());
                if(order.count()<1||order.count()>64)throw new IllegalArgumentException("EXCAVATE count must be 1..64");
                var p=order.position();
                if(body.blockPosition().distSqr(p)>32*32)throw new IllegalArgumentException("EXCAVATE_SCOPE: target feet must be within 32 blocks");
                yield new MoveToTaskRecord(id,deadline,p.getX()+.5,(double)p.getY(),p.getZ()+.5,null,true);
            }
            case COLLECT_RESOURCE -> {
                if(order.position()!=null)throw new IllegalArgumentException("COLLECT_RESOURCE position scope is not adapted; omit position to use current body origin");
                var output=item(order.resource());var sources=new LinkedHashSet<Block>();
                if(order.candidates().isEmpty())sources.addAll(SourceProbe.blocksFor(output));
                else for(var candidate:order.candidates()) {
                    var block=block(candidate);
                    if(!SourceProbe.blocksFor(output).contains(block))throw new IllegalArgumentException("UNVERIFIED_SOURCE_FOR_ITEM:"+candidate);
                    sources.add(block);
                }
                if(sources.isEmpty())throw new IllegalArgumentException("RESOURCE_ACQUISITION_UNAVAILABLE:"+order.resource());
                yield new CollectRequest(id,deadline,output,order.count(),sources,prep.enabled()).scope(order.radius(),prep);
            }
            case CRAFT -> RecipeCraftRequest.resolve(body.serverLevel(),id,deadline,order.resource(),order.count(),prep.enabled(),order.position()).scope(order.radius(),prep);
            case PLACE -> {
                position(order.position());unusedCount(order);
                var selected=order.resource()==null?body.getMainHandItem().getItem():item(order.resource());
                if(!(selected instanceof BlockItem place))throw new IllegalArgumentException("PLACE_REQUIRES_REAL_BLOCK_ITEM");
                yield new BuildTaskRecord(id,deadline,List.of(target(place.getBlock(),selected,order.position())),false,true,false);
            }
            case BUILD -> new BuildTaskRecord(id,deadline,order.steps().stream().map(s->{var b=block(s.block());return target(b,b.asItem(),s.position());}).toList(),false,true,false);
            case STORE,TAKE -> {
                position(order.position());yield new ContainerRequest(id,deadline,order.position(),order.kind()==BodyOrder.Kind.STORE?ContainerRequest.Mode.DEPOSIT:ContainerRequest.Mode.WITHDRAW,item(order.resource()),order.count(),null);
            }
            case PROCESS -> {position(order.position());yield new ProcessRequest(id,deadline,order.position(),item(order.resource()),order.count(),false);}
            case COLLECT_PROCESS -> {position(order.position());unusedCount(order);if(order.resource()!=null)throw new IllegalArgumentException("COLLECT_PROCESS uses the recorded order, not resource");yield new ProcessRequest(id,deadline,order.position(),null,0,true);}
            default -> throw new IllegalArgumentException("Unadapted action");
        };
    }
    private static BuildTaskRecord.Target target(Block b,Item i,BlockPos p) {
        if(!(i instanceof BlockItem))throw new IllegalArgumentException("Block has no native placement item");
        return new BuildTaskRecord.Target(b,i,p,"HearthCrew placement",null,null,null).asItemPlace();
    }
    private static Item item(ResourceLocation id){if(id==null||!BuiltInRegistries.ITEM.containsKey(id))throw new IllegalArgumentException("ITEM_NOT_REGISTERED:"+id);var i=BuiltInRegistries.ITEM.get(id);if(i==Items.AIR)throw new IllegalArgumentException("AIR_NOT_ITEM");return i;}
    private static Block block(ResourceLocation id){if(id==null||!BuiltInRegistries.BLOCK.containsKey(id))throw new IllegalArgumentException("BLOCK_NOT_REGISTERED:"+id);return BuiltInRegistries.BLOCK.get(id);}
    private static void position(BlockPos p){if(p==null)throw new IllegalArgumentException("position required");}
    private static void unusedCount(BodyOrder o){if(o.count()!=0)throw new IllegalArgumentException(o.kind()+" does not use count");}
}
