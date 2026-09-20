package io.github.yufeiyufei888.hearthcrew.entity;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.entity.*;
import net.minecraft.world.level.Level;

/** Reads 0.1.x entities without executing or settling them. Migration is owned by CrewPlayers. */
public final class LegacyCompanionEntity extends PathfinderMob {
    private CompoundTag archive = new CompoundTag();
    public LegacyCompanionEntity(EntityType<? extends PathfinderMob> type,Level level){super(type,level);setNoAi(true);setInvulnerable(true);setNoGravity(true);setPersistenceRequired();}
    @Override public void readAdditionalSaveData(CompoundTag tag){super.readAdditionalSaveData(tag);archive=tag.copy();setNoAi(true);setInvulnerable(true);setNoGravity(true);}
    @Override public void addAdditionalSaveData(CompoundTag tag){super.addAdditionalSaveData(tag);tag.merge(archive.copy());}
    public CompoundTag archive(){var tag=new CompoundTag();saveWithoutId(tag);return tag;}
    public int skinIndex(){return Math.floorMod(archive.getInt("CrewSkin"),3);}
    public static net.minecraft.world.entity.ai.attributes.AttributeSupplier.Builder attributes(){return Mob.createMobAttributes().add(net.minecraft.world.entity.ai.attributes.Attributes.MAX_HEALTH,20);}
}
