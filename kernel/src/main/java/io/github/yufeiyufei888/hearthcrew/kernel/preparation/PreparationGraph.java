package io.github.yufeiyufei888.hearthcrew.kernel.preparation;

import java.util.*;

/** Bounded AND/OR planning over an immutable, server-verified capability/recipe snapshot.
 * A plan is returned only after every prerequisite has been allocated. This class never executes it. */
public final class PreparationGraph {
    public record Need(List<String> alternatives,int count,boolean consume) {
        public Need {alternatives=List.copyOf(new LinkedHashSet<>(alternatives));if(alternatives.isEmpty()||count<1)throw new IllegalArgumentException("need");}
        public static Need item(String item,int count){return new Need(List.of(item),count,true);}
        public static Need condition(String fact){return new Need(List.of(fact),1,false);}
    }
    public record Cost(long resources,long breaks,long placements,long ticks) implements Comparable<Cost> {
        public Cost {if(resources<0||breaks<0||placements<0||ticks<0)throw new IllegalArgumentException("negative cost");}
        public Cost plus(Cost c,int times){return new Cost(Math.addExact(resources,Math.multiplyExact(c.resources,times)),Math.addExact(breaks,Math.multiplyExact(c.breaks,times)),Math.addExact(placements,Math.multiplyExact(c.placements,times)),Math.addExact(ticks,Math.multiplyExact(c.ticks,times)));}
        public int compareTo(Cost c){int n=Long.compare(resources,c.resources);if(n==0)n=Long.compare(breaks+placements,c.breaks+c.placements);return n==0?Long.compare(ticks,c.ticks):n;}
    }
    public record Method(String id,String output,int outputCount,List<Need> needs,Cost cost,int maximumRuns,boolean verified) {
        public Method {needs=List.copyOf(needs);if(id.isBlank()||output.isBlank()||outputCount<1||maximumRuns<1)throw new IllegalArgumentException("method");}
    }
    public record Step(String method,int runs,String output,int quantity,Map<String,Integer> inputs) {public Step{inputs=Map.copyOf(inputs);}}
    public record Plan(List<Step> steps,Cost cost,Map<String,Integer> remaining) {public Plan{steps=List.copyOf(steps);remaining=Map.copyOf(remaining);}}
    public record Result(String status,Plan plan,Set<String> conditions,int searched,boolean alternativesTruncated) {public Result{conditions=Set.copyOf(conditions);}}
    public record Limits(int depth,int steps,int preparationBreaks,int searches,int alternatives) {
        public Limits {if(depth<1||steps<1||preparationBreaks<0||searches<1||alternatives<1)throw new IllegalArgumentException("limits");}
        public static Limits defaults(){return new Limits(6,16,64,4096,64);}
    }
    private static final Cost ZERO=new Cost(0,0,0,0);
    private final Map<String,List<Method>> methods=new HashMap<>();
    private final Limits limits;
    private final java.util.function.BooleanSupplier credit;
    private final Set<String> conditions=new LinkedHashSet<>();
    private int searched;
    private boolean truncated,unknown;
    private Set<String> potentiallyAvailable=Set.of();
    private record State(Map<String,Integer> stock,Map<String,Integer> used,List<Step> steps,Cost cost) {}
    private record Allocation(State state,Map<String,Integer> inputs) {}

    public PreparationGraph(Collection<Method> catalog,Limits limits) {
        this(catalog,limits,()->true);
    }
    public PreparationGraph(Collection<Method> catalog,Limits limits,java.util.function.BooleanSupplier credit) {
        this.credit=credit;this.limits=limits;var identities=new HashSet<String>();
        for(var method:catalog){if(!identities.add(method.id()))throw new IllegalArgumentException("duplicate method");methods.computeIfAbsent(method.output(),k->new ArrayList<>()).add(method);}
        for(var options:methods.values())options.sort(Comparator.comparing(Method::cost).thenComparing(Method::id));
    }
    /** Stock contains only eligible quantities: the server adapter filters tool durability/qualification. */
    public Result plan(String item,int count,Map<String,Integer> available) {
        if(count<1||available.values().stream().anyMatch(n->n<0))throw new IllegalArgumentException("inventory");
        searched=0;truncated=unknown=false;conditions.clear();
        // Structural reachability only prunes impossible recipe branches. It does not reserve stock
        // or prove quantities: the full allocation below still checks every shared prerequisite.
        var possible=new HashSet<String>();available.forEach((key,value)->{if(value>0)possible.add(key);});
        boolean changed;
        do {changed=false;for(var options:methods.values())for(var method:options)
            if(method.needs().stream().allMatch(n->n.alternatives().stream().anyMatch(possible::contains)))changed|=possible.add(method.output());
        } while(changed);
        potentiallyAvailable=Set.copyOf(possible);
        var result=ensure(item,count,new State(Map.copyOf(available),Map.of(),List.of(),ZERO),0,Set.of());
        var best=result.stream().min(Comparator.comparing(State::cost)).orElse(null);
        return new Result(best!=null?"READY":unknown||truncated?"UNKNOWN":"BLOCKED",
            best==null?null:new Plan(best.steps(),best.cost(),best.stock()),conditions,searched,truncated);
    }
    private boolean search() {if(!credit.getAsBoolean()){truncated=true;conditions.add("PLANNING_CANCELLED");return false;}if(++searched<=limits.searches())return true;truncated=true;conditions.add("PLANNING_BUDGET");return false;}
    private List<State> ensure(String item,int count,State initial,int depth,Set<String> ancestors) {
        if(initial.stock().getOrDefault(item,0)>=count)return List.of(initial);
        if(!search())return List.of();
        if(!potentiallyAvailable.contains(item)){conditions.add("NO_SATISFIABLE_ACQUISITION:"+item);return List.of();}
        if(depth>=limits.depth()){conditions.add("DEPENDENCY_DEPTH:"+item);return List.of();}
        if(ancestors.contains(item)){conditions.add("RECIPE_CYCLE:"+item);return List.of();}
        var chain=new HashSet<>(ancestors);chain.add(item);var solutions=new ArrayList<State>();
        var choices=methods.getOrDefault(item,List.of());
        if(choices.isEmpty())conditions.add("NO_REGISTERED_ACQUISITION:"+item);
        for(var method:choices) {
            if(!search())break;
            if(!method.verified()){unknown=true;conditions.add("UNVERIFIED_METHOD:"+method.id());continue;}
            int missing=count-initial.stock().getOrDefault(item,0),runs=(missing-1)/method.outputCount()+1;
            if((long)initial.used().getOrDefault(method.id(),0)+runs>method.maximumRuns()){conditions.add("METHOD_CAPACITY:"+method.id());continue;}
            List<Allocation> allocations=List.of(new Allocation(initial,Map.of()));
            for(var need:method.needs()) {
                var expanded=new ArrayList<Allocation>();
                for(var allocation:allocations) {
                    var options=new ArrayList<>(need.alternatives());
                    options.removeIf(s->!potentiallyAvailable.contains(s));
                    if(options.isEmpty()){conditions.add("UNAVAILABLE_INGREDIENT:"+method.id());continue;}
                    options.sort(Comparator.<String>comparingInt(s->-allocation.state().stock().getOrDefault(s,0)).thenComparing(s->s));
                    if(need.consume()&&options.size()>1) {
                        expanded.addAll(allocate(options,0,Math.multiplyExact(need.count(),runs),allocation,depth+1,chain));continue;
                    }
                    for(var option:options) {
                        if(!search())break;
                        int required=need.consume()?Math.multiplyExact(need.count(),runs):need.count();
                        // Reusable facilities/tools are conditions, not another material conversion.
                        for(var state:ensure(option,required,allocation.state(),need.consume()?depth+1:depth,chain)) {
                            var stock=new HashMap<>(state.stock());var inputs=new TreeMap<>(allocation.inputs());
                            if(need.consume()){stock.merge(option,-required,Integer::sum);inputs.merge(option,required,Integer::sum);}
                            expanded.add(new Allocation(new State(stock,state.used(),state.steps(),state.cost()),inputs));
                        }
                    }
                }
                expanded.sort(Comparator.comparing(a->a.state().cost()));
                if(expanded.size()>limits.alternatives()){truncated=true;expanded.subList(limits.alternatives(),expanded.size()).clear();}
                allocations=expanded;if(allocations.isEmpty())break;
            }
            for(var allocation:allocations) {
                var state=allocation.state();var cost=state.cost().plus(method.cost(),runs);
                if(state.steps().size()>=limits.steps()||cost.breaks()>limits.preparationBreaks()){conditions.add("PREPARATION_LIMIT:"+method.id());continue;}
                // Prerequisites can themselves use a method; account against the final allocation.
                int usage=state.used().getOrDefault(method.id(),0)+runs;if(usage>method.maximumRuns())continue;
                var stock=new HashMap<>(state.stock());stock.merge(item,Math.multiplyExact(method.outputCount(),runs),Math::addExact);
                var used=new HashMap<>(state.used());used.put(method.id(),usage);var steps=new ArrayList<>(state.steps());
                steps.add(new Step(method.id(),runs,item,method.outputCount()*runs,allocation.inputs()));solutions.add(new State(stock,used,steps,cost));
            }
        }
        solutions.sort(Comparator.comparing(State::cost));
        if(solutions.size()>limits.alternatives()){truncated=true;solutions.subList(limits.alternatives(),solutions.size()).clear();}
        return solutions;
    }
    private List<Allocation> allocate(List<String> options,int index,int remaining,Allocation input,int depth,Set<String> ancestors) {
        if(remaining==0)return List.of(input);
        if(index>=options.size()||!search())return List.of();
        String item=options.get(index);var result=new ArrayList<Allocation>();
        int max=methods.containsKey(item)?remaining:Math.min(remaining,input.state().stock().getOrDefault(item,0));
        for(int quantity=max;quantity>=0;quantity--) {
            if(!search())break;
            for(var state:quantity==0?List.of(input.state()):ensure(item,quantity,input.state(),depth,ancestors)) {
                var stock=new HashMap<>(state.stock());var inputs=new TreeMap<>(input.inputs());
                if(quantity>0){stock.merge(item,-quantity,Integer::sum);inputs.merge(item,quantity,Integer::sum);}
                result.addAll(allocate(options,index+1,remaining-quantity,new Allocation(new State(stock,state.used(),state.steps(),state.cost()),inputs),depth,ancestors));
            }
            if(result.size()>limits.alternatives()){truncated=true;result.sort(Comparator.comparing(a->a.state().cost()));result.subList(limits.alternatives(),result.size()).clear();}
        }
        return result;
    }
}
