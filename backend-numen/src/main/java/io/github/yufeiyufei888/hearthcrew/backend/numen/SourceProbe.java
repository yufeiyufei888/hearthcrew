package io.github.yufeiyufei888.hearthcrew.backend.numen;

import com.dwinovo.numen.entity.NumenPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.item.*;
import net.minecraft.world.level.block.*;
import java.util.*;

/** Explicit acquisition registry plus bounded loaded-area scan; recipes do not invent sources. */
final class SourceProbe {
    record Source(Block block,Item output,Set<BlockPos> positions,List<Item> tools) {}
    private static final Map<Block,Item> REGISTRY=Map.ofEntries(
        Map.entry(Blocks.STONE,Items.COBBLESTONE),Map.entry(Blocks.COBBLESTONE,Items.COBBLESTONE),
        Map.entry(Blocks.DEEPSLATE,Items.COBBLED_DEEPSLATE),Map.entry(Blocks.COAL_ORE,Items.COAL),Map.entry(Blocks.DEEPSLATE_COAL_ORE,Items.COAL),
        Map.entry(Blocks.IRON_ORE,Items.RAW_IRON),Map.entry(Blocks.DEEPSLATE_IRON_ORE,Items.RAW_IRON),
        Map.entry(Blocks.OAK_LOG,Items.OAK_LOG),Map.entry(Blocks.BIRCH_LOG,Items.BIRCH_LOG),Map.entry(Blocks.SPRUCE_LOG,Items.SPRUCE_LOG),
        Map.entry(Blocks.ACACIA_LOG,Items.ACACIA_LOG),Map.entry(Blocks.JUNGLE_LOG,Items.JUNGLE_LOG),Map.entry(Blocks.DARK_OAK_LOG,Items.DARK_OAK_LOG),
        Map.entry(Blocks.MANGROVE_LOG,Items.MANGROVE_LOG),Map.entry(Blocks.CHERRY_LOG,Items.CHERRY_LOG));
    private static final List<Item> PICKAXES=List.of(Items.WOODEN_PICKAXE,Items.STONE_PICKAXE,Items.IRON_PICKAXE,Items.DIAMOND_PICKAXE,Items.NETHERITE_PICKAXE,Items.GOLDEN_PICKAXE);
    private final NumenPlayer body;
    private final BlockPos origin;
    private final Iterator<BlockPos> scan;
    private final Map<Block,Set<BlockPos>> candidates=new LinkedHashMap<>();
    private final Map<BlockPos,Block> observed=new HashMap<>();
    private final Map<BlockPos,Block> validationFacts=new HashMap<>();
    private final Map<BlockPos,Boolean> validatedRoots=new HashMap<>();
    private Map<String,Object> summary=Map.of("scanComplete",false);
    boolean done,unloaded;
    static Set<Block> blocksFor(Item item){return REGISTRY.entrySet().stream().filter(e->e.getValue()==item).map(Map.Entry::getKey).collect(java.util.stream.Collectors.toUnmodifiableSet());}
    SourceProbe(NumenPlayer body,BlockPos origin) {this(body,origin,32);}
    SourceProbe(NumenPlayer body,BlockPos origin,int radius) {
        this.body=body;this.origin=origin;
        scan=BlockPos.withinManhattan(origin,radius,radius,radius).iterator();
    }
    void tick() {
        while(!done&&io.github.yufeiyufei888.hearthcrew.runtime.ScanBudget.claim(body.server,1)) {
            if(!scan.hasNext()){done=true;break;}
            var p=scan.next();
            if(p.getY()<body.level().getMinBuildHeight()||p.getY()>=body.level().getMaxBuildHeight())continue;
            if(!body.level().hasChunkAt(p)){unloaded=true;continue;}
            var state=body.level().getBlockState(p);var block=state.getBlock();
            if(REGISTRY.containsKey(block)||block instanceof LeavesBlock||state.is(net.minecraft.tags.BlockTags.DIRT)||state.is(net.minecraft.tags.BlockTags.SAND))observed.put(p.immutable(),block);
            if(!REGISTRY.containsKey(block)||BackendProtection.isProtected(body.serverLevel(),p)||BackendJournal.get(body.server).structureAt(body.level().dimension(),p)||state.hasBlockEntity())continue;
            // All candidates remain live-checked before the actual native break.
            candidates.computeIfAbsent(block,k->new LinkedHashSet<>()).add(p.immutable());
        }
    }
    List<Source> sources() {
        if(!done)throw new IllegalStateException("resource scan incomplete");
        var sources=new ArrayList<Source>();var facts=new ArrayList<Map<String,Object>>();
        for(var entry:candidates.entrySet()) {
            var block=entry.getKey();var positions=new LinkedHashSet<BlockPos>();
            for(var p:entry.getValue())if(body.level().hasChunkAt(p)&&body.level().getBlockState(p).is(block)
                &&!BackendProtection.isProtected(body.serverLevel(),p)&&!BackendJournal.get(body.server).structureAt(body.level().dimension(),p)
                &&NumenBackend.preparationCandidateAllowed(body,p)
                &&(!block.defaultBlockState().is(net.minecraft.tags.BlockTags.LOGS)||naturalTrunk(p,block)))positions.add(p);
            if(block.defaultBlockState().is(net.minecraft.tags.BlockTags.LOGS))System.out.println("BACKEND_SOURCE_TREE block="+block+" observed="+entry.getValue().size()+" validated="+positions.size()+" observedLeaves="+observed.values().stream().filter(b->b instanceof LeavesBlock).count());
            facts.add(Map.of("block",net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block).toString(),"observed",entry.getValue().size(),"eligible",positions.size(),"naturalValidation",block.defaultBlockState().is(net.minecraft.tags.BlockTags.LOGS)?"loaded_root_and_canopy_halo; harvest remains inside original range":"not_a_tree"));
            if(positions.isEmpty())continue;
            var state=block.defaultBlockState();
            var tools=state.requiresCorrectToolForDrops()?PICKAXES.stream().filter(i->new ItemStack(i).isCorrectToolForDrops(state)).toList():List.<Item>of();
            if(state.requiresCorrectToolForDrops()&&tools.isEmpty())continue;
            sources.add(new Source(block,REGISTRY.get(block),Set.copyOf(positions),tools));
        }
        summary=Map.of("scanComplete",true,"unloadedBoundary",unloaded,"origin",origin.toShortString(),"sources",List.copyOf(facts));return List.copyOf(sources);
    }
    Map<String,Object> summary(){return summary;}
    private boolean naturalTrunk(BlockPos p,Block block) {
        // A narrow harvesting radius may cut off a tree's root or canopy. Read only
        // a bounded, already loaded halo for validation; never add it to harvest scope.
        // Original scan evidence remains usable after this task removes the trunk base.
        var base=p;for(int n=0;n<32&&treeFact(base.below())==block;n++)base=base.below();
        var cached=validatedRoots.get(base);if(cached!=null)return cached;
        var floor=treeFact(base.below());boolean natural=false;
        if(floor!=null&&(floor.defaultBlockState().is(net.minecraft.tags.BlockTags.DIRT)||floor.defaultBlockState().is(net.minecraft.tags.BlockTags.SAND))) {
            canopy:for(int y=0;y<32&&treeFact(base.above(y))==block;y++)for(int dx=-2;dx<=2;dx++)for(int dz=-2;dz<=2;dz++)for(int dy=0;dy<=1;dy++)
                if(treeFact(base.offset(dx,y+dy,dz)) instanceof LeavesBlock){natural=true;break canopy;}
        }
        validatedRoots.put(base.immutable(),natural);return natural;
    }
    private Block treeFact(BlockPos p){
        var observedBlock=observed.get(p);if(observedBlock!=null)return observedBlock;
        if(p.getY()<body.level().getMinBuildHeight()||p.getY()>=body.level().getMaxBuildHeight()||!body.level().hasChunkAt(p))return null;
        return validationFacts.computeIfAbsent(p.immutable(),cell->body.level().getBlockState(cell).getBlock());
    }
}
