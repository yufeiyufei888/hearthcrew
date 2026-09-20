package io.github.yufeiyufei888.hearthcrew.backend.numen;

import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.task.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.item.*;
import net.minecraft.world.item.crafting.*;
import net.minecraft.world.level.block.entity.*;
import java.util.*;

/** Body task feeds or retrieves; the persisted order outlives this task while vanilla cooks. */
final class ProcessRequest extends TaskRecord {
    final BlockPos position;final Item input;final int count;final boolean collect;
    ProcessRequest(String id,long deadline,BlockPos position,Item input,int count,boolean collect){
        super("hearthcrew_process",id,deadline);this.position=position.immutable();this.input=input;this.count=count;this.collect=collect;
        if(collect?(input!=null||count!=0):(input==null||count<1||count>64))throw new IllegalArgumentException("Invalid processing fields");
    }
    static void register(){TaskFactory.register(ProcessRequest.class,Work::new);}
    private static final class Work implements Task {
        final NumenPlayer body;final ProcessRequest r;final BlockPos origin;final FurnaceWork book;
        CompoundTag order;Task child;ContainerRequest operation;TaskResult outcome;int step;
        Work(NumenPlayer body,ProcessRequest r){this.body=body;this.r=r;origin=body.blockPosition().immutable();book=FurnaceWork.get(body.server);}
        public TaskState tick(NumenPlayer ignored){
            if(outcome!=null)return outcome.success()?TaskState.SUCCESS:TaskState.FAILED;
            if(Math.max(Math.max(Math.abs(origin.getX()-r.position.getX()),Math.abs(origin.getY()-r.position.getY())),Math.abs(origin.getZ()-r.position.getZ()))>32)return fail("FACILITY_OUTSIDE_SCOPE");
            var denial=FacilityAccess.denial(body.serverLevel(),r.position);if(!denial.isEmpty())return fail(denial);
            if(!(body.level().getBlockEntity(r.position) instanceof AbstractFurnaceBlockEntity furnace))return fail("FURNACE_REQUIRED");
            if(order==null){
                if(r.collect){
                    book.reconcile(body.serverLevel(),r.position);order=book.at(body.serverLevel(),r.position);
                    if(order==null||!order.getUUID("owner").equals(body.getUUID()))return fail("PROCESSING_ORDER_NOT_OWNED");
                    if(!order.getString("state").equals("OUTPUT_READY"))return fail("WAITING_PROCESS:"+order.getString("state"));
                    book.phase(body.server,order,"COLLECTING");
                    child(ContainerRequest.Mode.WITHDRAW,FurnaceWork.item(order,"output"),order.getInt("expected")-order.getInt("collected"),2);
                }else {
                    if(book.at(body.serverLevel(),r.position)!=null)return fail("WORKSTATION_ORDER_BUSY");
                    if(!furnace.getItem(0).isEmpty()||!furnace.getItem(1).isEmpty()||!furnace.getItem(2).isEmpty())return fail("FURNACE_NOT_EMPTY");
                    if(body.getInventory().countItem(r.input)<r.count)return fail("MISSING_PROCESS_INPUT");
                    RecipeType<? extends AbstractCookingRecipe> type=furnace instanceof SmokerBlockEntity?RecipeType.SMOKING:furnace instanceof BlastFurnaceBlockEntity?RecipeType.BLASTING:RecipeType.SMELTING;
                    var recipe=body.server.getRecipeManager().getRecipeFor(type,new SingleRecipeInput(new ItemStack(r.input)),body.level());
                    if(recipe.isEmpty())return fail("NO_RECIPE_FOR_STATION");
                    var output=recipe.get().value().getResultItem(body.registryAccess());
                    if(output.isEmpty()||output.getCount()*r.count>Math.min(64,output.getMaxStackSize()))return fail("PROCESS_OUTPUT_CAPACITY");
                    int ticks=recipe.get().value().getCookingTime()*r.count;Item fuel=null;int quantity=0;
                    for(var stack:body.getInventory().items){if(stack.isEmpty())continue;int burn=stack.getBurnTime(type);if(burn<=0)continue;
                        int needed=(ticks+burn-1)/burn,available=body.getInventory().countItem(stack.getItem())-(stack.is(r.input)?r.count:0);
                        if(needed>0&&needed<=64&&available>=needed){fuel=stack.getItem();quantity=needed;break;}}
                    if(fuel==null)return fail("MISSING_PROCESS_FUEL");
                    try{order=book.reserve(body,r.getToolCallId(),r.position,r.input,r.count,fuel,quantity,output.getItem(),output.getCount());}
                    catch(IllegalStateException blocked){return fail(blocked.getMessage());}
                    child(ContainerRequest.Mode.DEPOSIT,r.input,r.count,0);
                }
            }
            var state=child.tick(body);if(!state.isTerminal())return state;
            var result=child.result(state);child.stop(body,StopReason.REPLACED);child=null;
            int actual=result!=null&&result.data()!=null&&result.data().get("transferred") instanceof Number n?n.intValue():0;
            if(r.collect){
                // Only the native transfer's paired inventory/container delta is credited.
                order.putInt("collected",order.getInt("collected")+actual);
                if(order.getInt("collected")==order.getInt("expected")){book.phase(body.server,order,"COMPLETED");return success("PROCESS_OUTPUT_COLLECTED",actual);}
                book.phase(body.server,order,actual>0?"PROCESSING":"OUTPUT_READY");book.reconcile(body.serverLevel(),r.position);
                return fail("PROCESS_COLLECTION_BLOCKED_OR_PARTIAL");
            }
            if(state!=TaskState.SUCCESS){book.phase(body.server,order,step==0&&actual==0?"FAILED":"RECONCILE_REQUIRED");return fail("PROCESS_FEED_BLOCKED:"+(result==null?"NO_RECEIPT":result.message()));}
            if(step==0){book.phase(body.server,order,"INPUT_DEPOSITED");step=1;child(ContainerRequest.Mode.DEPOSIT,FurnaceWork.item(order,"fuel"),order.getInt("fuelCount"),1);return TaskState.RUNNING;}
            book.phase(body.server,order,"PROCESSING");book.reconcile(body.serverLevel(),r.position);return success("PROCESS_SUBMITTED_OUTPUT_NOT_ACQUIRED",0);
        }
        void child(ContainerRequest.Mode mode,Item item,int count,int slot){operation=new ContainerRequest(r.getToolCallId()+".transfer."+step,r.getDeadlineGameTime(),r.position,mode,item,count,slot);child=TaskFactory.create(body,operation);child.start(body);}
        TaskState success(String message,int acquired){outcome=new TaskResult(true,message,false,false,Map.of("order",FurnaceWork.facts(order),"newOutputAcquired",acquired));return TaskState.SUCCESS;}
        TaskState fail(String message){outcome=TaskResult.fail(message,order==null?Map.of():Map.of("order",FurnaceWork.facts(order)));return TaskState.FAILED;}
        public void stop(NumenPlayer ignored,StopReason reason){if(child!=null)child.stop(body,reason);body.closeContainer();
            if(reason!=StopReason.PREEMPTED&&outcome==null&&order!=null&&!Set.of("PROCESSING","OUTPUT_READY","COMPLETED","FAILED").contains(order.getString("state")))book.phase(body.server,order,"RECONCILE_REQUIRED");}
        public TaskResult result(TaskState state){return outcome==null?TaskResult.cancelled("Processing interrupted; retained effects require reconciliation"):outcome;}
        public String name(){return r.collect?"COLLECTING_PROCESSED_OUTPUT":"APPROACHING_AND_FEEDING_FURNACE";}
    }
}
