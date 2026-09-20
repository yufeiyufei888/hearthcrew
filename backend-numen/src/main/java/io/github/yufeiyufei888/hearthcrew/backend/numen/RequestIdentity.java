package io.github.yufeiyufei888.hearthcrew.backend.numen;

import com.dwinovo.numen.task.TaskRecord;
import com.dwinovo.numen.core.task.build.BuildTaskRecord;
import com.dwinovo.numen.core.task.mine.MineBlockTaskRecord;
import com.dwinovo.numen.core.task.move.MoveToTaskRecord;
import net.minecraft.core.registries.BuiltInRegistries;
import java.util.*;

/** Canonical action semantics; upstream internal counters/deadline extensions are not identity. */
final class RequestIdentity {
    static String fingerprint(TaskRecord request,int accessBudget) {
        var input=new TreeMap<String,Object>();input.put("tool",request.getToolName());input.put("accessBudget",accessBudget);
        if(request instanceof MineBlockTaskRecord m) {
            input.put("blocks",m.targets.stream().map(b->BuiltInRegistries.BLOCK.getKey(b).toString()).sorted().toList());input.put("count",m.count);
        } else if(request instanceof MoveToTaskRecord m) {
            input.put("x",m.x);input.put("y",m.y);input.put("z",m.z);input.put("block",m.block);input.put("mayAlterTerrain",m.mayAlterTerrain);
        } else if(request instanceof ProcessRequest p) {
            input.put("position",List.of(p.position.getX(),p.position.getY(),p.position.getZ()));input.put("collect",p.collect);
            input.put("item",p.input==null?null:BuiltInRegistries.ITEM.getKey(p.input).toString());input.put("count",p.count);
        } else if(request instanceof ContainerRequest c) {
            input.put("position",List.of(c.position.getX(),c.position.getY(),c.position.getZ()));input.put("mode",c.mode.name());
            input.put("item",BuiltInRegistries.ITEM.getKey(c.item).toString());input.put("count",c.count);input.put("slot",c.containerSlot);
        } else if(request instanceof PrepareRequest p) {
            input.put("output",BuiltInRegistries.ITEM.getKey(p.output).toString());input.put("count",p.count);input.put("minimumDurability",p.minimumDurability);
            input.put("preparationLimits",p.limits);input.put("radius",p.radius);
            if(p instanceof CollectRequest c){input.put("allowPreparation",c.allowPreparation);input.put("sources",c.targets.stream().map(b->BuiltInRegistries.BLOCK.getKey(b).toString()).sorted().toList());}
            if(p instanceof RecipeCraftRequest c){input.put("recipe",c.recipe.toString());input.put("executions",c.executions);input.put("allowPreparation",c.allowPreparation);input.put("workstation",c.workstation==null?null:List.of(c.workstation.getX(),c.workstation.getY(),c.workstation.getZ()));}
        } else if(request instanceof CraftRequest c) {
            input.put("output",BuiltInRegistries.ITEM.getKey(c.output).toString());input.put("count",c.count);
            input.put("recipe",c.recipe);input.put("allocatedMaterials",new TreeMap<>(c.materials));
            input.put("workstation",c.workstation==null?null:List.of(c.workstation.getX(),c.workstation.getY(),c.workstation.getZ()));
        } else if(request instanceof BuildTaskRecord b) {
            input.put("targets",b.targets.stream().map(t->{var target=new TreeMap<String,Object>();
                target.put("state",t.desiredState().toString());target.put("item",BuiltInRegistries.ITEM.getKey(t.item()).toString());
                target.put("position",List.of(t.pos().getX(),t.pos().getY(),t.pos().getZ()));
                target.put("facing",t.facing()==null?null:t.facing().name());target.put("axis",t.axis()==null?null:t.axis().name());
                target.put("topHalf",t.topHalf());target.put("itemPlace",t.itemPlace());return target;}).toList());
            input.put("replaceExisting",b.replaceExisting);input.put("replaceMode",b.replaceMode.name());input.put("consumeMaterials",b.consumeMaterials);
            input.put("allowPartial",b.allowPartial);input.put("replaceBlockEntities",b.replaceBlockEntities);
        } else throw new IllegalArgumentException("Unsupported identity schema");
        try {
            byte[] encoded=new com.google.gson.Gson().toJson(input).getBytes(java.nio.charset.StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(encoded));
        } catch(java.security.NoSuchAlgorithmException impossible){throw new IllegalStateException(impossible);}
    }
}
