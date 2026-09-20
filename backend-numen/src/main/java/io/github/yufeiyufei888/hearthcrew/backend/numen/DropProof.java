package io.github.yufeiyufei888.hearthcrew.backend.numen;

import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.Item;
import java.util.*;

/** Native pickup evidence. No inventory edits, permission changes, or replay. */
public final class DropProof {
    final PickupLedger ledger;
    final long generation;
    static final class Claim {
        final DropProof proof;final Item item;int remaining;
        Claim(DropProof proof,Item item,int remaining){this.proof=proof;this.item=item;this.remaining=remaining;}
    }
    final UUID miner;
    final String action;
    final Map<String,Integer> own=new TreeMap<>(),team=new TreeMap<>(),external=new TreeMap<>(),lost=new TreeMap<>(),collectors=new TreeMap<>();
    int ambiguous;
    DropProof(PickupLedger ledger,UUID miner,String action,long generation){
        if(action==null||action.isBlank()||generation<1)throw new IllegalArgumentException("Invalid pickup identity");
        this.ledger=ledger;this.miner=miner;this.action=action;this.generation=generation;
    }
    String key(){return miner+"/"+generation+"/"+action;}
    static DropProof forAction(net.minecraft.server.level.ServerLevel level,UUID miner,String action,long generation) {
        var ledger=PickupLedger.get(level.getServer());String key=miner+"/"+generation+"/"+action;
        var proof=ledger.proofs.computeIfAbsent(key,k->new DropProof(ledger,miner,action,generation));ledger.setDirty();return proof;
    }
    private static void append(UUID drop,Claim claim) {
        var list=claim.proof.ledger.drops.computeIfAbsent(drop,k->new ArrayDeque<>());
        for(var c:list)if(c.proof==claim.proof&&c.item==claim.item){c.remaining+=claim.remaining;return;}
        list.add(claim);
    }
    void spawned(UUID drop,Item item,int amount){if(amount>0){append(drop,new Claim(this,item,amount));ledger.setDirty();}}
    static void picked(net.minecraft.server.MinecraftServer server,UUID drop,UUID collector,Item item,int amount,int originalCount) {
        if(server==null||!BackendProtection.configured(server))return;
        var ledger=PickupLedger.get(server);var DROPS=ledger.drops;
        var claims=DROPS.get(drop);if(claims==null)return;
        for(var it=claims.iterator();it.hasNext();){var c=it.next();if(c.item!=item)continue;
            int n=Math.max(0,Math.min(c.remaining,amount-(originalCount-c.remaining)));
            int left=Math.max(0,c.remaining-amount);c.proof.ambiguous+=c.remaining-n-left;
            var totals=c.proof.miner.equals(collector)?c.proof.own:NumenBackend.owns(collector)?c.proof.team:c.proof.external;
            if(n>0){totals.merge(BuiltInRegistries.ITEM.getKey(item).toString(),n,Integer::sum);c.proof.collectors.merge(collector+"/"+BuiltInRegistries.ITEM.getKey(item),n,Integer::sum);}c.remaining=left;if(c.remaining==0)it.remove();}
        if(claims.isEmpty())DROPS.remove(drop);ledger.setDirty();
    }
    public static void merged(net.minecraft.server.MinecraftServer server,UUID source,UUID destination,Item item,int amount,int originalCount) {
        if(server==null||!BackendProtection.configured(server))return;
        var ledger=PickupLedger.get(server);var DROPS=ledger.drops;
        if(amount<=0||source.equals(destination))return;var claims=DROPS.get(source);if(claims==null)return;
        var transfers=new ArrayList<Claim>();
        for(var it=claims.iterator();it.hasNext();) {var c=it.next();if(c.item!=item)continue;
            int moved=Math.max(0,Math.min(c.remaining,amount-(originalCount-c.remaining)));
            int left=Math.max(0,c.remaining-amount);c.proof.ambiguous+=c.remaining-moved-left;
            if(moved>0)transfers.add(new Claim(c.proof,item,moved));c.remaining=left;if(left==0)it.remove();
        }
        if(claims.isEmpty())DROPS.remove(source);for(var c:transfers)append(destination,c);ledger.setDirty();
    }
    int acquired(){return own.values().stream().mapToInt(Integer::intValue).sum()+team.values().stream().mapToInt(Integer::intValue).sum();}
    int ownAcquired(Item item){return own.getOrDefault(BuiltInRegistries.ITEM.getKey(item).toString(),0);}
    int teamAcquired(Item item){return team.getOrDefault(BuiltInRegistries.ITEM.getKey(item).toString(),0);}
    static void confirmedRemoved(net.minecraft.server.MinecraftServer server,UUID entity) {
        if(!BackendProtection.configured(server))return;
        var ledger=PickupLedger.get(server);var claims=ledger.drops.remove(entity);if(claims==null)return;
        for(var claim:claims)claim.proof.lost.merge(BuiltInRegistries.ITEM.getKey(claim.item).toString(),claim.remaining,Integer::sum);
        ledger.setDirty();
    }
    static List<net.minecraft.world.entity.item.ItemEntity> visiblePending(net.minecraft.server.level.ServerLevel level,
            Collection<DropProof> proofs,Item item,net.minecraft.core.BlockPos origin) {
        var DROPS=PickupLedger.get(level.getServer()).drops;
        var wanted=Set.copyOf(proofs);var found=new ArrayList<net.minecraft.world.entity.item.ItemEntity>();
        for(var entry:DROPS.entrySet()) {
            if(entry.getValue().stream().noneMatch(c->wanted.contains(c.proof)&&c.item==item&&c.remaining>0))continue;
            if(level.getEntity(entry.getKey()) instanceof net.minecraft.world.entity.item.ItemEntity entity&&!entity.isRemoved()
                    &&entity.getItem().is(item)&&origin.distManhattan(entity.blockPosition())<=96
                    &&Math.max(Math.abs(origin.getX()-entity.getX()),Math.max(Math.abs(origin.getY()-entity.getY()),Math.abs(origin.getZ()-entity.getZ())))<=32)
                found.add(entity);
        }
        return found;
    }
    Map<String,Object> evidence(){
        var pending=new ArrayList<Map<String,Object>>();
        for(var entry:ledger.drops.entrySet())for(var claim:entry.getValue())if(claim.proof==this&&claim.remaining>0)
            pending.add(Map.of("entityId",entry.getKey().toString(),"item",BuiltInRegistries.ITEM.getKey(claim.item).toString(),"unconfirmed",claim.remaining));
        var result=new LinkedHashMap<String,Object>();
        result.put("action",action);result.put("bodyGeneration",generation);result.put("collectors",Map.copyOf(collectors));
        result.put("confirmedLost",Map.copyOf(lost));result.put("ownPicked",Map.copyOf(own));result.put("teamPicked",Map.copyOf(team));
        result.put("externalPicked",Map.copyOf(external));result.put("verifiedTotal",acquired());result.put("ambiguousMixedItems",ambiguous);
        result.put("pendingEvidence",pending);return Collections.unmodifiableMap(result);
    }
}
