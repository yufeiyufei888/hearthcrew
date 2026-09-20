package io.github.yufeiyufei888.hearthcrew.backend.numen;

import com.dwinovo.numen.task.*;
import com.dwinovo.numen.entity.NumenPlayer;
import com.dwinovo.numen.core.pathing.execute.PlayerNav;
import com.dwinovo.numen.core.pathing.goal.GoalCompiler;
import com.dwinovo.numen.core.tools.ContainerOps;
import net.minecraft.core.BlockPos;
import net.minecraft.world.inventory.*;
import net.minecraft.world.item.*;
import java.util.*;

/** Native menu transfer with exact item/count and independently verified postconditions. */
public final class ContainerRequest extends TaskRecord {
    enum Mode { DEPOSIT, WITHDRAW }
    final BlockPos position;
    final Mode mode;
    final Item item;
    final int count;
    final Integer containerSlot;
    ContainerRequest(String id,long deadline,BlockPos position,Mode mode,Item item,int count,Integer slot) {
        super("hearthcrew_container",id,deadline);
        if(count<1||count>64||slot!=null&&(slot<0||slot>53))throw new IllegalArgumentException("Invalid container quantity/slot");
        this.position=position.immutable();this.mode=Objects.requireNonNull(mode);this.item=Objects.requireNonNull(item);this.count=count;containerSlot=slot;
    }
    static void register(){TaskFactory.register(ContainerRequest.class,Transfer::new);}
    private static final class Transfer implements Task {
        final NumenPlayer body;final ContainerRequest r;final BlockPos origin;
        PlayerNav nav;TaskResult outcome;int arrivedTicks;
        Transfer(NumenPlayer body,ContainerRequest request){this.body=body;r=request;origin=body.blockPosition().immutable();}
        public TaskState tick(NumenPlayer ignored) {
            if(outcome!=null)return outcome.success()?TaskState.SUCCESS:TaskState.FAILED;
            var p=r.position;
            if(Math.max(Math.abs(p.getX()-origin.getX()),Math.max(Math.abs(p.getY()-origin.getY()),Math.abs(p.getZ()-origin.getZ())))>32)
                return fail("FACILITY_OUTSIDE_SCOPE");
            if(!body.level().hasChunkAt(p))return fail("FACILITY_NOT_LOADED");
            var denied=FacilityAccess.denial(body.serverLevel(),p);if(!denied.isEmpty())return fail(denied);
            if(!FurnaceWork.get(body.server).permits(body,p))return fail("WORKSTATION_RESERVED_BY_TEAMMATE");
            if(!FacilityAccess.usableNow(body,p)) {
                if(nav==null)nav=PlayerNav.to(body,()->GoalCompiler.interact(p),1.0,()->FacilityAccess.usableNow(body,p));
                var status=nav.tick();
                if(status==PlayerNav.Status.FAILED)return fail("FACILITY_PATH_UNRESOLVED");
                if(status==PlayerNav.Status.ARRIVED&&++arrivedTicks>20)return fail("FACILITY_STANCE_NOT_USABLE");
                return TaskState.RUNNING;
            }
            if(nav!=null){nav.stop();nav=null;}
            body.closeContainer();
            try {
                com.dwinovo.numen.core.act.Interaction.useBlock(body,new net.minecraft.world.phys.BlockHitResult(
                    net.minecraft.world.phys.Vec3.atCenterOf(p),net.minecraft.core.Direction.UP,p,false),net.minecraft.world.InteractionHand.MAIN_HAND).tick();
                var menu=body.containerMenu;
                if(!(menu instanceof ChestMenu||menu instanceof ShulkerBoxMenu||menu instanceof AbstractFurnaceMenu))return fail("CONTAINER_MENU_UNSUPPORTED_OR_DENIED");
                if(!menu.getCarried().isEmpty())return fail("CONTAINER_CURSOR_NOT_EMPTY");
                if(r.containerSlot!=null&&(r.containerSlot>=menu.slots.size()||menu.getSlot(r.containerSlot).container==body.getInventory()))return fail("INVALID_CONTAINER_SLOT");
                int before=body.getInventory().countItem(r.item),containerBefore=contents(menu),remaining=r.count;
                for(int from=0;from<menu.slots.size()&&remaining>0;from++) {
                    var source=menu.getSlot(from);boolean own=source.container==body.getInventory();
                    if(own!=(r.mode==Mode.DEPOSIT)||!source.getItem().is(r.item)||!source.mayPickup(body))continue;
                    if(!own&&r.containerSlot!=null&&from!=r.containerSlot)continue;
                    for(int to=0;to<menu.slots.size()&&remaining>0&&!source.getItem().isEmpty();to++) {
                        var destination=menu.getSlot(to);
                        if((destination.container==body.getInventory())==own||!destination.mayPlace(source.getItem()))continue;
                        if(own&&r.containerSlot!=null&&to!=r.containerSlot)continue;
                        if(!destination.getItem().isEmpty()&&!ItemStack.isSameItemSameComponents(source.getItem(),destination.getItem()))continue;
                        int quantity=Math.min(remaining,source.getItem().getCount());int sourceBefore=source.getItem().getCount();
                        new ContainerOps().transfer(List.of(new ContainerOps.Move(from,to,quantity)),body);
                        remaining-=Math.max(0,sourceBefore-source.getItem().getCount());
                    }
                }
                int ownDelta=body.getInventory().countItem(r.item)-before,containerDelta=contents(menu)-containerBefore;
                int transferred=r.mode==Mode.DEPOSIT?-ownDelta:ownDelta;
                boolean verified=transferred==r.count&&ownDelta+containerDelta==0&&menu.getCarried().isEmpty();
                outcome=new TaskResult(verified,verified?"CONTAINER_TRANSFER_VERIFIED":"CONTAINER_PARTIAL_OR_BLOCKED",false,false,
                    Map.of("requested",r.count,"transferred",transferred,"inventoryDelta",ownDelta,"containerDelta",containerDelta));
                return verified?TaskState.SUCCESS:TaskState.FAILED;
            } finally {body.closeContainer();}
        }
        int contents(AbstractContainerMenu menu){return menu.slots.stream().filter(s->s.container!=body.getInventory()&&s.getItem().is(r.item)).mapToInt(s->s.getItem().getCount()).sum();}
        TaskState fail(String reason){outcome=TaskResult.fail(reason);return TaskState.FAILED;}
        public void stop(NumenPlayer ignored,StopReason reason){if(nav!=null){nav.stop();nav=null;}body.closeContainer();}
        public TaskResult result(TaskState state){return outcome==null?TaskResult.cancelled("Container transfer not executed"):outcome;}
        public String name(){return "APPROACHING_OR_USING_CONTAINER";}
    }
}
