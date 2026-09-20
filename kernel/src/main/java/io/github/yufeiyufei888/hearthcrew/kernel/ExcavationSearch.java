package io.github.yufeiyufei888.hearthcrew.kernel;
import java.util.*;
import java.util.function.*;
/** Incremental, bounded search. A rejected edge is never made traversable by guessing. */
public final class ExcavationSearch {
 public record Cell(int x,int y,int z) { public Cell offset(int x,int y,int z){return new Cell(this.x+x,this.y+y,this.z+z);} public long distance2(Cell b){long x=this.x-b.x,y=this.y-b.y,z=this.z-b.z;return x*x+y*y+z*z;} }
 public enum State { SEARCHING,FOUND,BLOCKED,BUDGET_EXHAUSTED }
 private record Label(Cell at,int broken,Set<Cell> removed) {}
 private record Node(Cell at,Node parent,int broken,double cost,double rank,Set<Cell> removed) {}
 @FunctionalInterface public interface RouteEdge {Set<Cell> breaks(Cell from,Cell to,Set<Cell> removed);}
 private RouteEdge routeEdge; private BiPredicate<Cell,Set<Cell>> routeGoal;
 private final PriorityQueue<Node> open=new PriorityQueue<>(Comparator.comparingDouble(Node::rank));
 private final Map<Label,Double> costs=new HashMap<>(); private final Cell origin,goal; private final int budget;
 private final Predicate<Cell> reached; private final Predicate<Cell> reserved; private final ToIntBiFunction<Cell,Cell> edge; private int expanded; private List<Cell> path=List.of(); private State state=State.SEARCHING;
 public ExcavationSearch(Cell origin,Cell goal,int budget,ToIntBiFunction<Cell,Cell> edge){
  this(origin,goal,budget,edge,p->p.y()==goal.y()&&Math.abs(p.x()-goal.x())+Math.abs(p.z()-goal.z())==1,p->p.equals(goal));
 }
 public ExcavationSearch(Cell origin,Cell goal,int budget,ToIntBiFunction<Cell,Cell> edge,Predicate<Cell> reached,Predicate<Cell> reserved){
  if(budget<0||budget>64)throw new IllegalArgumentException("break budget 0..64");
  this.reached=reached;this.reserved=reserved;
  this.origin=origin;this.goal=goal;this.budget=budget;this.edge=edge;open.add(new Node(origin,null,0,0,0,Set.of()));costs.put(new Label(origin,0,Set.of()),0.0);
 }
 public ExcavationSearch(Cell origin,Cell goal,int budget,RouteEdge edge,Predicate<Cell> reached,Predicate<Cell> reserved){
  this(origin,goal,budget,(a,b)->0,reached,reserved);this.routeEdge=edge;
 }
 public ExcavationSearch(Cell origin,Cell goal,int budget,RouteEdge edge,BiPredicate<Cell,Set<Cell>> reached,Predicate<Cell> reserved){
  this(origin,goal,budget,edge,p->false,reserved);this.routeGoal=reached;
 }
 public State step(){return step(64);}
 public State step(int allowance){
  if(state!=State.SEARCHING)return state;
  for(int work=0;work<Math.clamp(allowance,0,64)&&!open.isEmpty()&&expanded<4096;work++){
   Node n=open.poll();if(n.cost()>costs.getOrDefault(new Label(n.at(),n.broken(),n.removed()),Double.POSITIVE_INFINITY))continue;expanded++;
   if(routeGoal==null?reached.test(n.at()):routeGoal.test(n.at(),n.removed())){
    var route=new ArrayList<Cell>();for(Node v=n;v.parent()!=null;v=v.parent())route.add(v.at());Collections.reverse(route);path=List.copyOf(route);return state=State.FOUND;
   }
   for(int[] d:new int[][]{{1,0},{-1,0},{0,1},{0,-1}})for(int y:new int[]{0,1,-1}){
    Cell next=n.at().offset(d[0],y,d[1]);if(next.distance2(origin)>1024||reserved.test(next))continue;
    boolean revisits=false;for(Node ancestor=n;ancestor!=null;ancestor=ancestor.parent())if(ancestor.at().equals(next)){revisits=true;break;}if(revisits)continue;
    Set<Cell> removed=n.removed();int breaks;
    if(routeEdge!=null){var changes=routeEdge.breaks(n.at(),next,n.removed());if(changes==null)continue;var union=new HashSet<>(n.removed());union.addAll(changes);removed=Set.copyOf(union);breaks=removed.size()-n.removed().size();}
    else breaks=edge.applyAsInt(n.at(),next);
    if(breaks<0||n.broken()+breaks>budget)continue;
    double cost=n.cost()+1+breaks*3+Math.abs(y)*.25;if(cost>=costs.getOrDefault(new Label(next,n.broken()+breaks,removed),Double.POSITIVE_INFINITY))continue;
    costs.put(new Label(next,n.broken()+breaks,removed),cost);open.add(new Node(next,n,n.broken()+breaks,cost,cost+5*Math.sqrt(next.distance2(goal)),removed));
   }
  }
  if(open.isEmpty())state=State.BLOCKED;else if(expanded>=4096)state=State.BUDGET_EXHAUSTED;return state;
 }
 public List<Cell> path(){return path;} public int expanded(){return expanded;}
}
