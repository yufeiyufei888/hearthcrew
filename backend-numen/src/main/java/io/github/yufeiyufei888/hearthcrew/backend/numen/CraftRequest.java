package io.github.yufeiyufei888.hearthcrew.backend.numen;

import com.dwinovo.numen.core.tools.CraftOps;
import com.dwinovo.numen.task.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import java.util.Map;

/** Lab-only server crafting adapter. The authority runs this operation once. */
public final class CraftRequest extends TaskRecord {
    final Item output;
    final int count;
    final net.minecraft.core.BlockPos workstation;
    final String recipe;
    final Map<String,Integer> materials;
    public CraftRequest(String id,long deadline,Item output,int count) {
        this(id,deadline,output,count,null);
    }
    public CraftRequest(String id,long deadline,Item output,int count,net.minecraft.core.BlockPos workstation) {
        this(id,deadline,output,count,workstation,null,Map.of());
    }
    public CraftRequest(String id,long deadline,Item output,int count,net.minecraft.core.BlockPos workstation,String recipe,Map<String,Integer> materials) {
        super("hearthcrew_lab_craft",id,deadline);
        if(count<1||count>64)throw new IllegalArgumentException("count");
        this.output=output;this.count=count;this.workstation=workstation==null?null:workstation.immutable();
        this.recipe=recipe;this.materials=Map.copyOf(materials);
        if(recipe!=null&&(recipe.isBlank()||materials.isEmpty()))throw new IllegalArgumentException("Selected recipe requires allocated materials");
    }
    static void register() {
        TaskFactory.register(CraftRequest.class,(body,request)->new Task() {
            TaskResult result;
            public TaskState tick(com.dwinovo.numen.entity.NumenPlayer ignored) {
                if(result!=null)return result.success()?TaskState.SUCCESS:TaskState.FAILED;
                body.closeContainer(); // re-open only a currently authorized facility; never reuse a stale menu
                if(request.workstation!=null) {
                    if(!FacilityProbe.usableNow(body,request.workstation)){result=TaskResult.fail("CRAFT_FACILITY_CHANGED",Map.of());return TaskState.FAILED;}
                    com.dwinovo.numen.core.act.Interaction.useBlock(body,
                        new net.minecraft.world.phys.BlockHitResult(net.minecraft.world.phys.Vec3.atCenterOf(request.workstation),net.minecraft.core.Direction.UP,request.workstation,false),
                        net.minecraft.world.InteractionHand.MAIN_HAND).tick();
                    if(body.containerMenu==body.inventoryMenu){result=TaskResult.fail("CRAFT_FACILITY_OPEN_DENIED",Map.of());return TaskState.FAILED;}
                }
                int before=body.getInventory().countItem(request.output);
                String raw;
                try {
                    if(request.recipe==null)raw=new CraftOps().craft(BuiltInRegistries.ITEM.getKey(request.output).toString(),request.count,body);
                    else try(var selected=new CraftingSelection(body,request.recipe,request.materials)) {
                        raw=new CraftOps().craft(BuiltInRegistries.ITEM.getKey(request.output).toString(),request.count,body);
                    }
                }
                finally {body.closeContainer();}
                int delta=body.getInventory().countItem(request.output)-before;
                result=new TaskResult(delta>=request.count,"Crafting checked against actual inventory",false,false,
                        Map.of("requested",request.count,"actualDelta",delta,"upstream",raw));
                return result.success()?TaskState.SUCCESS:TaskState.FAILED;
            }
            public TaskResult result(TaskState state) { return result==null?TaskResult.cancelled("Craft not executed"):result; }
            public void stop(com.dwinovo.numen.entity.NumenPlayer ignored,StopReason reason) { /* No retained inputs or open transaction. */ }
            public String name() { return "hearthcrew_lab_native_craft"; }
        });
    }
}
