package io.github.yufeiyufei888.hearthcrew.kernel.preparation;

import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.yufeiyufei888.hearthcrew.kernel.preparation.PreparationGraph.*;

class PreparationGraphTest {
    static Method craft(String id,String out,int count,Need... inputs){return new Method(id,out,count,List.of(inputs),new Cost(0,0,0,1),64,true);}
    static PreparationGraph graph(Method... methods){return new PreparationGraph(List.of(methods),Limits.defaults());}
    @Test void completeAlternativeWinsOverAvailableFirstStep() {
        var planner=graph(craft("expensive-first-step","diamond",1,Need.item("coal",1)),
            craft("diamond-pick","usable-pick",1,Need.item("diamond",3),Need.item("unavailable-binding",1)),
            craft("stone-pick","usable-pick",1,Need.item("cobble",3),Need.item("stick",2),Need.condition("table")));
        var r=planner.plan("usable-pick",1,Map.of("coal",3,"cobble",11,"stick",5,"table",1));
        assertEquals("READY",r.status());assertEquals(List.of("stone-pick"),r.plan().steps().stream().map(Step::method).toList());
    }
    @Test void alternativeIngredientsAllocateJointlyRatherThanReuseInventory() {
        var planner=graph(craft("recipe","result",1,new Need(List.of("oak","birch"),1,true),Need.item("oak",1)));
        var r=planner.plan("result",1,Map.of("oak",1,"birch",1));
        assertEquals("READY",r.status());assertEquals(Map.of("oak",1,"birch",1),r.plan().steps().getFirst().inputs());
        assertEquals("BLOCKED",planner.plan("result",1,Map.of("oak",1)).status());
    }
    @Test void sharedWorkstationMaterialIsNotCountedTwice() {
        var planner=graph(craft("table-item","table-item",1,Need.item("plank",4)),
            craft("place-table","table",1,Need.item("table-item",1)),
            craft("tool","tool",1,Need.condition("table"),Need.item("plank",3)));
        assertEquals("BLOCKED",planner.plan("tool",1,Map.of("plank",4)).status());
        assertEquals("READY",planner.plan("tool",1,Map.of("plank",7)).status());
    }
    @Test void unknownPathsAndCyclesRemainDistinct() {
        assertEquals("BLOCKED",graph(craft("a","a",1,Need.item("b",1)),craft("b","b",1,Need.item("a",1))).plan("a",1,Map.of()).status());
        assertEquals("UNKNOWN",graph(new Method("unchecked-path","a",1,List.of(),new Cost(1,1,0,10),64,false)).plan("a",1,Map.of()).status());
    }
    @Test void fullCostsChooseExistingMaterialsBeforeNewExcavation() {
        var planner=graph(new Method("mine-iron","iron",1,List.of(),new Cost(1,1,0,10),64,true),
            craft("iron-pick","pick",1,Need.item("iron",3),Need.item("stick",2)),craft("stone-pick","pick",1,Need.item("cobble",3),Need.item("stick",2)));
        assertEquals("stone-pick",planner.plan("pick",1,Map.of("cobble",11,"stick",5)).plan().steps().getFirst().method());
    }
    @Test void preparationDestructionBudgetAppliesAcrossDependencies() {
        var planner=graph(new Method("source-a","a",1,List.of(),new Cost(1,1,0,1),64,true),new Method("source-b","b",1,List.of(),new Cost(1,1,0,1),64,true),
            craft("both","result",1,Need.item("a",33),Need.item("b",32)));
        assertEquals("BLOCKED",planner.plan("result",1,Map.of()).status());
    }
    @Test void batchCanUseMixedAlternativesWithoutStealingLaterIngredient() {
        var planner=graph(craft("mixed","result",1,new Need(List.of("oak","birch"),4,true),Need.item("oak",3)));
        var r=planner.plan("result",1,Map.of("oak",4,"birch",3));
        assertEquals("READY",r.status());assertEquals(Map.of("oak",4,"birch",3),r.plan().steps().getFirst().inputs());
    }
    @Test void unavailableRecipeFamiliesDoNotExhaustPlanningBeforeAvailableWood() {
        var catalog=new ArrayList<Method>();var alternatives=new ArrayList<String>();
        for(int i=0;i<200;i++){String plank="plank-"+i;alternatives.add(plank);catalog.add(craft("planks-"+i,plank,4,Need.item("missing-log-"+i,1)));}
        alternatives.add("birch-plank");catalog.add(craft("birch-planks","birch-plank",4,Need.item("birch-log",1)));
        catalog.add(new Method("harvest-birch","birch-log",1,List.of(),new Cost(1,1,0,10),4,true));
        catalog.add(craft("table","result",1,new Need(alternatives,4,true)));
        var planner=new PreparationGraph(catalog,new Limits(6,16,64,64,64));
        var result=planner.plan("result",1,Map.of());
        assertEquals("READY",result.status());assertFalse(result.alternativesTruncated());
        assertEquals(1,result.plan().cost().breaks());
    }
}
