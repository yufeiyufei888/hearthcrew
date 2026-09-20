package io.github.yufeiyufei888.hearthcrew.entity;

import com.mojang.authlib.GameProfile;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.*;
import net.minecraft.world.entity.*;
import net.minecraft.world.level.Level;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.SimpleContainer;
import io.github.yufeiyufei888.hearthcrew.kernel.FoodState;
import io.github.yufeiyufei888.hearthcrew.gameplay.LocalGuard;
import io.github.yufeiyufei888.hearthcrew.runtime.CrewPlayers;

/** Native server player. Only the executor submits inputs; native player physics owns motion. */
public final class CompanionEntity extends ServerPlayer {
    /** Stable HearthCrew identity.  The native player UUID may change when a
     * test body is replaced, respawned, or transferred; routing and ledgers
     * must continue to use this logical identity. */
    private UUID logicalId;
    private UUID ownerId;
    private int skin;
    private boolean respawnEnabled=true;
    private int foodTickTimer,peacefulClock;
    private long bodyGeneration=1,lastTick=Long.MIN_VALUE;
    private BodyExecutor executor;
    private CompoundTag savedLedger;
    private final PlayerInventoryView view;
    private final PlayerNavigation navigation;
    private boolean jumpRequested;
    private boolean ticking,relocating;
    private final Look look=new Look();
    private final Move move=new Move();
    private final Jump jump=new Jump();
    public CompanionEntity(MinecraftServer server,ServerLevel level,GameProfile profile,ClientInformation information){
        super(server,level,profile,information);logicalId=profile.getId();view=new PlayerInventoryView(getInventory());navigation=new PlayerNavigation(this);
        setGameMode(net.minecraft.world.level.GameType.SURVIVAL);getAbilities().invulnerable=false;getAbilities().mayBuild=true;
    }
    public SimpleContainer inventory(){return view;}
    public UUID companionId(){return logicalId == null ? getUUID() : logicalId;}
    public UUID ownerId(){return ownerId;}
    public void setOwner(UUID id){ownerId=id;}
    public int skinIndex(){return skin;}
    public void setSkinIndex(int index){skin=Math.floorMod(index,3);}
    public int selectedSlot(){return getInventory().selected;}
    public long bodyGeneration(){return bodyGeneration;}
    public int foodLevel(){return getFoodData().getFoodLevel();}
    public FoodState foodState(){return new FoodState(foodLevel(),getFoodData().getSaturationLevel(),0).restoreTimers(foodTickTimer,peacefulClock);}
    public void setFoodState(FoodState state){getFoodData().setFoodLevel(state.foodLevel());getFoodData().setSaturation(state.saturation());foodTickTimer=state.foodTickTimer();peacefulClock=state.peacefulClock();}
    public void addExhaustion(float amount){causeFoodExhaustion(amount);}
    public void selectSlot(int slot){
        if(slot<0||slot>=36)throw new IllegalArgumentException("slot");
        if(slot<9)getInventory().selected=slot;
        else {var before=view.getItem(selectedSlot());view.setItem(selectedSlot(),view.getItem(slot));view.setItem(slot,before);}getInventory().setChanged();
    }
    public BodyExecutor executor(){if(executor==null){executor=new BodyExecutor(this);if(savedLedger!=null){executor.restoreLedger(savedLedger);savedLedger=null;}}return executor;}
    public PlayerNavigation getNavigation(){return navigation;}
    public Look getLookControl(){return look;}
    public Move getMoveControl(){return move;}
    public Jump getJumpControl(){return jump;}
    public void setRespawnEnabled(boolean value){respawnEnabled=value;}
    public boolean respawnEnabled(){return respawnEnabled;}
    public void setRespawnPoint(ResourceKey<Level> dimension,BlockPos pos){setRespawnPosition(dimension,pos,getYRot(),false,false);}
    public void prepareRespawn(){setHealth(getMaxHealth());getFoodData().setFoodLevel(20);deathTime=0;}
    public void haltInputs(){if(getVehicle() instanceof net.minecraft.world.entity.vehicle.Boat boat){boat.setInput(false,false,false,false);boat.setPaddleState(false,false);}zza=0;xxa=0;jumpRequested=false;setSprinting(false);setShiftKeyDown(false);}
    public void drive(Vec3 target,double speed,boolean water){
        Vec3 d=target.subtract(position());
        if(!water&&!isInWater()&&d.horizontalDistanceSqr()>.0025){var ahead=BlockPos.containing(position().add(d.normalize().scale(.7)));if(!level().getFluidState(ahead).isEmpty()||!level().getFluidState(ahead.below()).isEmpty()){zza=0;xxa=0;return;}}
        if(d.horizontalDistanceSqr()<.0025){zza=0;}else{
            setYRot((float)(Math.toDegrees(Math.atan2(d.z,d.x))-90));yHeadRot=getYRot();zza=(float)Math.min(1,Math.sqrt(d.horizontalDistanceSqr())*2);xxa=0;
        }
        setSprinting(speed>1.05&&foodLevel()>6&&!isShiftKeyDown());
        if(onGround()&&d.y>.45||onClimbable()||water&&isInWater()&&d.y>-.15)jumpRequested=true;
    }
    public final class Look{
        public void setLookAt(double x,double y,double z,float yaw,float pitch){lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES,new Vec3(x,y,z));}
        public void setLookAt(Entity e,float yaw,float pitch){setLookAt(e.getX(),e.getEyeY(),e.getZ(),yaw,pitch);}
    }
    public final class Move{public void setWantedPosition(double x,double y,double z,double speed){drive(new Vec3(x,y,z),speed,navigation.allowsWater()||isInWater());}}
    public final class Jump{public void jump(){jumpRequested=true;}}
    @Override public void tick(){
        if(connection==null)return;
        super.tick();long now=serverLevel().getGameTime();if(lastTick==now)return;lastTick=now;
        if(foodTickTimer>0)foodTickTimer--;if(peacefulClock>0)peacefulClock--;
        if(!isAlive()){haltInputs();if(respawnEnabled&&++deathTime>=20)CrewPlayers.queueRespawn(this);return;}
        haltInputs();ticking=true;
        try{
            LocalGuard.tick(this);executor().tick();navigation.tick();
            if(jumpRequested){if(onGround())jumpFromGround();else if(isInWater())jumpInLiquid(net.minecraft.tags.FluidTags.WATER);}
            super.doTick();serverLevel().getChunkSource().move(this);
        }finally{ticking=false;}
    }
    @Override public void doTick(){if(ticking)super.doTick();}
    @Override public void die(DamageSource source){if(executor!=null)executor.interruptForDeath();haltInputs();super.die(source);}
    @Override public boolean isInvulnerableTo(DamageSource source){return isSpectator();}
    @Override public void restoreFrom(ServerPlayer old,boolean alive){
        super.restoreFrom(old,alive);
        if(old instanceof CompanionEntity prior){
            logicalId=prior.companionId();ownerId=prior.ownerId;skin=prior.skin;respawnEnabled=prior.respawnEnabled;bodyGeneration=prior.bodyGeneration+1;
            var previous=prior.executor();
            if(previous.hasPortalWork()) executor=previous.afterDimensionChange(this,true);
            else savedLedger=previous.saveLedger();
        }
    }
    @Override public boolean isAlliedTo(Entity e){return ownerId!=null&&(ownerId.equals(e.getUUID())||e instanceof CompanionEntity p&&ownerId.equals(p.ownerId))||super.isAlliedTo(e);}
    @Override public Entity changeDimension(net.minecraft.world.level.portal.DimensionTransition transition){
        var previous=executor;var sourceDimension=level().dimension();
        if(previous!=null)previous.interruptForDimensionChange();navigation.stop();bodyGeneration++;
        Entity result=super.changeDimension(transition);
        if(result instanceof CompanionEntity destination && previous!=null && previous.hasPortalWork()
                && destination.executor==null && sourceDimension!=destination.level().dimension()) {
            destination.executor=previous.afterPortalTransfer(destination);
        }
        return result;
    }
    @Override public void teleportTo(ServerLevel level,double x,double y,double z,float yaw,float pitch){
        if(!relocating&&executor!=null){executor.interruptForDeath();navigation.stop();bodyGeneration++;}
        relocating=true;try{
            super.teleportTo(level,x,y,z,yaw,pitch);
            // A local player has no client movement packet to relocate its chunk tracker.
            // Update native player tracking now; waiting for its first entity tick can deadlock in an unticked destination.
            level.getChunkSource().move(this);
        }finally{relocating=false;}
    }
    @Override public void addAdditionalSaveData(CompoundTag tag){
        super.addAdditionalSaveData(tag);if(ownerId!=null)tag.putUUID("CrewOwner",ownerId);
        tag.putUUID("CrewIdentity",companionId());tag.putInt("CrewSkin",skin);tag.putLong("CrewBodyGeneration",bodyGeneration);
        tag.putBoolean("CrewRespawnEnabled",respawnEnabled);
        tag.putInt("CrewFoodTickTimer",foodTickTimer);tag.putInt("CrewPeacefulClock",peacefulClock);
        if(executor!=null)tag.put("CrewActionLedger",executor.saveLedger());else if(savedLedger!=null)tag.put("CrewActionLedger",savedLedger.copy());
    }
    @Override public void readAdditionalSaveData(CompoundTag tag){
        super.readAdditionalSaveData(tag);
        if(tag.hasUUID("CrewIdentity"))logicalId=tag.getUUID("CrewIdentity");
        if(tag.hasUUID("CrewOwner"))ownerId=tag.getUUID("CrewOwner");
        skin=Math.floorMod(tag.getInt("CrewSkin"),3);bodyGeneration=Math.max(1,tag.getLong("CrewBodyGeneration"))+1;
        respawnEnabled=!tag.contains("CrewRespawnEnabled")||tag.getBoolean("CrewRespawnEnabled");
        foodTickTimer=Math.max(0,tag.getInt("CrewFoodTickTimer"));peacefulClock=Math.max(0,tag.getInt("CrewPeacefulClock"));
        savedLedger=tag.contains("CrewActionLedger")?tag.getCompound("CrewActionLedger").copy():null;executor=null;
    }
}
