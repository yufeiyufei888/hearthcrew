package io.github.yufeiyufei888.hearthcrew.entity;

import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.phys.Vec3;
import io.github.yufeiyufei888.hearthcrew.gameplay.TravelTerrain;

/** Bounded navigation for a real player, without a hidden Mob or a second motor. */
public final class PlayerNavigation {
    private final CompanionEntity player;
    private Search search;
    private Route route;
    private BlockPos destination;
    private double speed=1;
    private BlockPos segmentTarget; private long segmentRetry;
    private boolean water;
    private final LinkedHashMap<BlockPos,Search> probes=new LinkedHashMap<>();
    private int probeCursor;
    private final LinkedHashMap<String,Search> groups=new LinkedHashMap<>();
    private long budgetTick=Long.MIN_VALUE; private int spentNodes;
    /** Navigation, interaction probes and access excavation share this per-body tick allowance. */
    public int searchAllowance(int requested){
        long now=player.serverLevel().getGameTime();if(now!=budgetTick){budgetTick=now;spentNodes=0;}
        int allowed=Math.min(Math.max(0,requested),64-spentNodes);spentNodes+=allowed;return allowed;
    }
    public Route pathToAny(String key,List<BlockPos> candidates){
        var goals=candidates.stream().map(BlockPos::immutable).distinct().toList();
        var probe=groups.get(key);
        if(probe==null||!probe.goals.equals(goals)||probe.swimming!=water){
            if(groups.size()>=8)groups.remove(groups.keySet().iterator().next());
            probe=new Search(TravelTerrain.supportedStart(player),goals,water);groups.put(key,probe);
        }
        return probe.route;
    }
    public String groupStatus(String key){var p=groups.get(key);return p==null?"unknown":p.route!=null?"reachable":!p.done?"searching":p.expanded>=4096?"budget_exhausted":"blocked";}
    public void invalidateGroup(String key){groups.remove(key);}

    public String pathStatus(BlockPos target){var p=probes.get(target);return p==null?"unknown":p.route!=null?"reachable":!p.done?"searching":p.expanded>=4096?"budget_exhausted":"blocked";}
    public boolean pendingPaths(){return probes.values().stream().anyMatch(p->!p.done)||groups.values().stream().anyMatch(p->!p.done);}
    public void invalidateProbes(){probes.clear();groups.clear();}

    public PlayerNavigation(CompanionEntity player){this.player=player;}
    public void allowWater(boolean value){if(water!=value){water=value;stop();}}
    public boolean allowsWater(){return water;}
    public Route getPath(){return route;}
    public boolean isDone(){return search==null&&(route==null||route.index>=route.nodes.size());}
    public void stop(){search=null;route=null;destination=null;segmentTarget=null;player.haltInputs();}
    public boolean moveTo(double x,double y,double z,double speed){
        var target=BlockPos.containing(x,Math.ceil(y-1e-4),z);this.speed=speed;
        if(!target.equals(destination)){destination=target;route=null;beginSegment();}
        return true;
    }
    private void beginSegment(){
        if(destination==null)return;
        var start=TravelTerrain.supportedStart(player); segmentTarget=destination;
        if(start.distSqr(destination)>28*28){
            var delta=Vec3.atBottomCenterOf(destination).subtract(player.position());
            var toward=player.position().add(delta.normalize().scale(20));
            io.github.yufeiyufei888.hearthcrew.runtime.WorldEvents.requestExplorationChunks(player,BlockPos.containing(toward));
            var goals=TravelTerrain.frontier(player,Vec3.atBottomCenterOf(destination),24);
            if(goals.isEmpty()){search=null;segmentTarget=null;segmentRetry=player.serverLevel().getGameTime()+20;return;}
            search=new Search(start,goals,water);return;
        }
        search=new Search(start,destination,water);
    }
    public boolean moveTo(Entity target,double speed){return moveTo(target.getX(),target.getY(),target.getZ(),speed);}
    public boolean moveTo(Route path,double speed){this.route=new Route(path.nodes);this.search=null;this.speed=speed;this.destination=path.nodes.isEmpty()?player.blockPosition():path.nodes.get(path.nodes.size()-1);this.segmentTarget=destination;return true;}
    public Route createPath(BlockPos target,int tolerance){
        var probe=probes.get(target);long now=player.serverLevel().getGameTime();
        if(probe==null||probe.origin.distSqr(player.blockPosition())>4||probe.swimming!=water||probe.done&&now-probe.completedAt>40){
            if(probes.size()>=64)probes.remove(probes.keySet().iterator().next());
            probe=new Search(TravelTerrain.supportedStart(player),target,water);probes.put(target.immutable(),probe);
        }return probe.route;
    }
    public void tick(){
        if(destination!=null&&route==null&&search==null&&player.serverLevel().getGameTime()>=segmentRetry){segmentRetry=player.serverLevel().getGameTime()+100;beginSegment();}
        long now=player.serverLevel().getGameTime();if(now!=budgetTick){budgetTick=now;spentNodes=0;}int budget=64-spentNodes;
        if(search!=null){int spend=Math.min(32,budget);int before=search.expanded;search.step(spend);int used=search.expanded-before;spentNodes+=used;budget-=used;if(search.done){route=search.route;if(route!=null)segmentTarget=route.destination();search=null;}}
        var pending=java.util.stream.Stream.concat(groups.values().stream(),probes.values().stream()).filter(p->!p.done).toList();
        if(!pending.isEmpty()){int first=Math.floorMod(probeCursor++,pending.size());for(int i=0;i<pending.size()&&budget>0;i++){int spend=Math.min(8,budget);var probe=pending.get((first+i)%pending.size());int before=probe.expanded;probe.step(spend);int used=probe.expanded-before;spentNodes+=used;if(probe.done)probe.completedAt=player.serverLevel().getGameTime();budget-=used;}}
        if(route==null)return;
        if(route.index>=route.nodes.size()){
            if(destination!=null&&segmentTarget!=null&&!destination.equals(segmentTarget)){route=null;beginSegment();return;}
            if(destination!=null&&player.position().distanceToSqr(point(destination))>.01&&passable(destination,water))player.drive(point(destination),speed,water);
            return;
        }
        var next=route.nodes.get(route.index);var point=point(next);
        if(player.position().distanceToSqr(point)<.12 && (water||player.onClimbable()||TravelTerrain.landedAt(player,next,.35))){route.index++;if(route.index<route.nodes.size()){next=route.nodes.get(route.index);point=point(next);}else return;}
        // Keep flat strides flowing without changing physical speed or final arrival tolerance.
        if(!water&&player.onGround()&&route.index+1<route.nodes.size()){
            var after=route.nodes.get(route.index+1);var afterPoint=point(after);
            var heading=afterPoint.subtract(point);var remaining=point.subtract(player.position());
            if(Math.abs(point.y-player.getY())<.1&&Math.abs(afterPoint.y-point.y)<.01&&remaining.horizontalDistanceSqr()<.81
                &&Math.abs(remaining.x*heading.z-remaining.z*heading.x)<.2
                &&TravelTerrain.transition(player,next,after)&&TravelTerrain.blockers(player,afterPoint).isEmpty()){
                var start=TravelTerrain.supportedStart(player);
                if(start.equals(next)||TravelTerrain.transition(player,start,next)){route.index++;next=after;point=afterPoint;}
            }
        }
        if(!passable(next,water)){route=null;beginSegment();player.haltInputs();return;}
        if(!TravelTerrain.blockers(player,point).isEmpty()){player.haltInputs();return;}
        if(!TravelTerrain.transition(player,TravelTerrain.supportedStart(player),next) && player.onGround() && player.blockPosition().distSqr(next)>4){route=null;beginSegment();player.haltInputs();return;}
        player.drive(point,speed,water);
    }
    private Vec3 point(BlockPos pos){return TravelTerrain.standable(player,pos)?TravelTerrain.feetPoint(player,pos):Vec3.atBottomCenterOf(pos);}
    public String movementType(){if(route==null||route.index>=route.nodes.size())return search!=null?"searching":"idle";var p=point(route.nodes.get(route.index));double dy=p.y-player.getY();return water?player.isInWater()?"swim":"shore":player.onClimbable()?"climb":dy>.6?"jump":dy>.05?"step":dy<-.6?"controlled_drop":"walk";}
    private boolean passable(BlockPos p,boolean swimming){
        if(!player.level().hasChunkAt(p)||!player.level().getWorldBorder().isWithinBounds(p)||TravelTerrain.dangerous(player,p)||TravelTerrain.dangerous(player,p.below()))return false;
        if(TravelTerrain.standable(player,p))return true;
        if(!TravelTerrain.clear(player,p))return false;
        if(player.level().getBlockState(p).is(net.minecraft.tags.BlockTags.CLIMBABLE))return true;
        return swimming&&player.level().getFluidState(p).is(FluidTags.WATER)&&!player.level().getFluidState(p.above()).is(FluidTags.LAVA);
    }
    public static final class Route {
        final List<BlockPos> nodes;int index;
        Route(List<BlockPos> nodes){this.nodes=nodes;}
        public net.minecraft.world.level.pathfinder.Node getNode(int i){var p=nodes.get(i);return new net.minecraft.world.level.pathfinder.Node(p.getX(),p.getY(),p.getZ());}
        public BlockPos destination(){return nodes.isEmpty()?null:nodes.getLast();}
        public boolean canReach(){return true;}
        public int getNextNodeIndex(){return index;}
        public int getNodeCount(){return nodes.size();}
        public boolean isDone(){return index>=nodes.size();}
    }
    private final class Search {
        record Node(BlockPos pos,double cost,double score){}
        final BlockPos origin,target;final List<BlockPos> goals;final boolean swimming;
        final PriorityQueue<Node> open=new PriorityQueue<>(Comparator.comparingDouble(Node::score));
        final Map<BlockPos,Double> costs=new HashMap<>();final Map<BlockPos,BlockPos> previous=new HashMap<>();
        int expanded;boolean done;Route route;long completedAt;final long created=player.serverLevel().getGameTime();
        Search(BlockPos from,BlockPos to,boolean water){this(from,List.of(to),water);}
        Search(BlockPos from,List<BlockPos> targets,boolean water){origin=from.immutable();goals=List.copyOf(targets);target=goals.isEmpty()?origin:goals.getFirst();swimming=water;open.add(new Node(origin,0,estimate(origin)));costs.put(origin,0d);if(goals.isEmpty())done=true;}
        double estimate(BlockPos p){return goals.stream().mapToDouble(g->Math.abs(p.getX()-g.getX())+Math.abs(p.getZ()-g.getZ())+Math.abs(p.getY()-g.getY())).min().orElse(0);}
        void step(int budget){for(int i=0;i<budget&&!done;i++){
            if(open.isEmpty()||expanded>=4096){done=true;if(Boolean.getBoolean("hearthcrew.isolatedGameTest"))System.out.println("NAV_FAIL origin="+origin+" target="+target+" expanded="+expanded);return;}
            expanded++;var node=open.poll();if(node.cost>costs.getOrDefault(node.pos,Double.POSITIVE_INFINITY))continue;
            if(goals.contains(node.pos)&&passable(node.pos,swimming)){var path=new ArrayList<BlockPos>();var at=node.pos;while(!at.equals(origin)){path.add(at);at=previous.get(at);}Collections.reverse(path);if(path.isEmpty())path.add(node.pos);route=new Route(path);done=true;return;}
            var neighbors=new ArrayList<BlockPos>();
            for(var dir:List.of(net.minecraft.core.Direction.NORTH,net.minecraft.core.Direction.SOUTH,net.minecraft.core.Direction.EAST,net.minecraft.core.Direction.WEST)){
                for(int dy:new int[]{0,1,-1,-2,-3}){var p=node.pos.relative(dir).offset(0,dy,0);
                    if(passable(p,swimming)&&TravelTerrain.transition(player,node.pos,p)){neighbors.add(p);break;}
                }
            }
            if(swimming||player.level().getBlockState(node.pos).is(net.minecraft.tags.BlockTags.CLIMBABLE))for(int dy:new int[]{-1,1}){var p=node.pos.offset(0,dy,0);if(passable(p,swimming))neighbors.add(p);}
            for(var p:neighbors){if(p.distSqr(origin)>32*32)continue;double cost=node.cost+1+Math.abs(p.getY()-node.pos.getY())*.3;
                if(cost<costs.getOrDefault(p,Double.POSITIVE_INFINITY)){costs.put(p,cost);previous.put(p,node.pos);open.add(new Node(p,cost,cost+estimate(p)));}}
        }}
    }
}
