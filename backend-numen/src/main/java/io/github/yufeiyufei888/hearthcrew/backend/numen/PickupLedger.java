package io.github.yufeiyufei888.hearthcrew.backend.numen;

import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.*;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.saveddata.SavedData;
import java.util.*;

/** World-local evidence, separate from terminal action results. Loading never changes inventory. */
final class PickupLedger extends SavedData {
    final Map<String,DropProof> proofs=new LinkedHashMap<>();
    final Map<UUID,Deque<DropProof.Claim>> drops=new LinkedHashMap<>();
    PickupLedger() {}
    static PickupLedger get(MinecraftServer server) {
        if(!server.isSameThread())throw new IllegalStateException("Server thread required");
        return server.overworld().getDataStorage().computeIfAbsent(new Factory<>(PickupLedger::new,(tag,p)->load(tag)),"hearthcrew_backend_pickups");
    }
    static PickupLedger load(CompoundTag input) {
        if(input.getInt("schema")!=1)throw new IllegalArgumentException("Unknown pickup ledger; preserve file");
        var ledger=new PickupLedger();
        for(var raw:input.getList("proofs",Tag.TAG_COMPOUND)) {
            var tag=(CompoundTag)raw;
            var proof=new DropProof(ledger,tag.getUUID("miner"),tag.getString("action"),tag.getLong("generation"));
            if(ledger.proofs.putIfAbsent(proof.key(),proof)!=null)throw new IllegalArgumentException("Duplicate pickup proof");
            readCounts(tag,"own",proof.own);readCounts(tag,"team",proof.team);readCounts(tag,"external",proof.external);
            readCounts(tag,"lost",proof.lost);readCounts(tag,"collectors",proof.collectors);
            proof.ambiguous=tag.getInt("ambiguous");
        }
        for(var raw:input.getList("drops",Tag.TAG_COMPOUND)) {
            var tag=(CompoundTag)raw;var proof=ledger.proofs.get(tag.getString("proof"));
            if(proof==null||tag.getInt("remaining")<1)throw new IllegalArgumentException("Invalid pending pickup evidence");
            var item=net.minecraft.core.registries.BuiltInRegistries.ITEM.get(net.minecraft.resources.ResourceLocation.parse(tag.getString("item")));
            if(item==net.minecraft.world.item.Items.AIR)throw new IllegalArgumentException("Unknown pickup item; preserve evidence");
            ledger.drops.computeIfAbsent(tag.getUUID("entity"),k->new ArrayDeque<>()).add(new DropProof.Claim(proof,item,tag.getInt("remaining")));
        }
        return ledger;
    }
    private static void readCounts(CompoundTag tag,String name,Map<String,Integer> target) {
        var values=tag.getCompound(name);
        for(var key:values.getAllKeys()) {int count=values.getInt(key);if(count<0)throw new IllegalArgumentException("Negative pickup evidence");target.put(key,count);}
    }
    private static CompoundTag counts(Map<String,Integer> values){var tag=new CompoundTag();values.forEach(tag::putInt);return tag;}
    @Override public CompoundTag save(CompoundTag output,HolderLookup.Provider provider) {
        output.putInt("schema",1);var savedProofs=new ListTag();
        for(var proof:proofs.values()) {
            var tag=new CompoundTag();tag.putUUID("miner",proof.miner);tag.putString("action",proof.action);tag.putLong("generation",proof.generation);
            tag.put("own",counts(proof.own));tag.put("team",counts(proof.team));tag.put("external",counts(proof.external));
            tag.put("lost",counts(proof.lost));tag.put("collectors",counts(proof.collectors));tag.putInt("ambiguous",proof.ambiguous);savedProofs.add(tag);
        }
        output.put("proofs",savedProofs);var savedDrops=new ListTag();
        for(var entry:drops.entrySet())for(var claim:entry.getValue()) {
            var tag=new CompoundTag();tag.putUUID("entity",entry.getKey());tag.putString("proof",claim.proof.key());
            tag.putString("item",net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(claim.item).toString());tag.putInt("remaining",claim.remaining);savedDrops.add(tag);
        }
        output.put("drops",savedDrops);return output;
    }
}
