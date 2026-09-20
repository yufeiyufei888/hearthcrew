package io.github.yufeiyufei888.hearthcrew.kernel;
import java.util.List;
/** Inventory-wide choice; all suitability facts are supplied by the actual block/item APIs. */
public final class HarvestChoice {
    public record Candidate(int slot, boolean correctDrops, float speed, int remaining) {}
    public static int choose(boolean requiresCorrectTool, List<Candidate> candidates) {
        int best=-1, life=-1;float speed=-1;
        for(var c:candidates) {
            if(c.remaining()<=0 || requiresCorrectTool&&!c.correctDrops())continue;
            if(c.speed()>speed || c.speed()==speed&&c.remaining()>life){best=c.slot();speed=c.speed();life=c.remaining();}
        }
        return best;
    }
    private HarvestChoice(){}
}
