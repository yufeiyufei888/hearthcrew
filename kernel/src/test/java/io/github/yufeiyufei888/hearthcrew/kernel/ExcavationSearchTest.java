package io.github.yufeiyufei888.hearthcrew.kernel;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static io.github.yufeiyufei888.hearthcrew.kernel.ExcavationSearch.*;
class ExcavationSearchTest {
    @Test void sharedTickAllowanceCannotExpandWhenNavigationSpentIt() {
        var s=new ExcavationSearch(new Cell(0,64,0),new Cell(9,60,0),16,(a,b)->0,p->p.equals(new Cell(9,60,0)),p->false);
        assertEquals(State.SEARCHING,s.step(0));assertEquals(0,s.expanded());
        s.step(7);assertTrue(s.expanded()<=7);int before=s.expanded();s.step(57);assertTrue(s.expanded()-before<=57);
    }
    private State finish(ExcavationSearch s) {State state;do{int before=s.expanded();state=s.step();assertTrue(s.expanded()-before<=64);}while(state==State.SEARCHING);assertTrue(s.expanded()<=4096);return state;}
    @Test void opensAShortTunnelButStopsBesideOre() {
        var s=new ExcavationSearch(new Cell(0,64,0),new Cell(5,64,0),8,(a,b)->b.y()==64&&b.z()==0&&b.x()>0?2:-1);
        assertEquals(State.FOUND,finish(s));assertEquals(new Cell(4,64,0),s.path().getLast());assertEquals(4,s.path().size());
    }
    @Test void budgetDoesNotAuthorizeAnExtraBlock() {
        var s=new ExcavationSearch(new Cell(0,64,0),new Cell(5,64,0),7,(a,b)->b.y()==64&&b.z()==0&&b.x()>0?2:-1);
        assertEquals(State.BLOCKED,finish(s));assertTrue(s.path().isEmpty());
    }
    @Test void staircaseChangesHeightOneLevelAtATime() {
        var s=new ExcavationSearch(new Cell(0,64,0),new Cell(4,61,0),12,(a,b)->b.z()==0&&b.x()==a.x()+1&&b.y()==a.y()-1?2:-1);
        assertEquals(State.FOUND,finish(s));assertEquals(new Cell(3,61,0),s.path().getLast());
    }
    @Test void forbiddenEdgesStayForbiddenAndThereIsNoVerticalShortcut() {
        var allowed=Set.of(new Cell(0,63,0),new Cell(0,62,0));
        var s=new ExcavationSearch(new Cell(0,64,0),new Cell(1,62,0),64,(a,b)->allowed.contains(b)?0:-1);
        assertEquals(State.BLOCKED,finish(s));
    }
    @Test void hugeSearchStopsAtBudgetAndNeverEscapesRadius() {
        var s=new ExcavationSearch(new Cell(0,64,0),new Cell(100,64,0),64,(a,b)->{assertTrue(b.distance2(new Cell(0,64,0))<=1024);return 0;});
        assertEquals(State.BUDGET_EXHAUSTED,finish(s));assertEquals(4096,s.expanded());
    }
    @Test void actualChangedTerrainRequiresNewSearch() {
        var blocked=new HashSet<Cell>();
        var start=new Cell(0,64,0);var goal=new Cell(3,64,0);
        var first=new ExcavationSearch(start,goal,8,(a,b)->b.y()==64&&b.z()==0&&!blocked.contains(b)?1:-1);
        assertEquals(State.FOUND,finish(first));blocked.add(new Cell(1,64,0));
        var again=new ExcavationSearch(start,goal,8,(a,b)->b.y()==64&&b.z()==0&&!blocked.contains(b)?1:-1);
        assertEquals(State.BLOCKED,finish(again));
    }
    @Test void correctDropsBeforeSpeedThenDurabilityAndMainHandIsNotPrivileged() {
        var choices=List.of(new HarvestChoice.Candidate(0,false,1,Integer.MAX_VALUE),new HarvestChoice.Candidate(19,true,6,100),new HarvestChoice.Candidate(22,false,20,500),new HarvestChoice.Candidate(30,true,6,200));
        assertEquals(30,HarvestChoice.choose(true,choices));
        assertEquals(-1,HarvestChoice.choose(true,List.of(new HarvestChoice.Candidate(0,false,1,100),new HarvestChoice.Candidate(19,true,8,0))));
        assertEquals(0,HarvestChoice.choose(false,List.of(new HarvestChoice.Candidate(0,false,1,Integer.MAX_VALUE))));
    }
    @Test void plannedRemovalIsVisibleToFollowingEdgesAndGoalWithoutDoubleCharging() {
        var wall=new Cell(1,65,0);var origin=new Cell(0,64,0);var end=new Cell(3,64,0);
        RouteEdge edge=(a,b,removed)->b.z()!=0||b.y()!=64||b.x()!=a.x()+1?null:b.x()==1?Set.of(wall):removed.contains(wall)?Set.of(wall):null;
        var search=new ExcavationSearch(origin,end,1,edge,(cell,removed)->cell.equals(end)&&removed.contains(wall),p->false);
        assertEquals(State.FOUND,finish(search));assertEquals(end,search.path().getLast());
    }
}
