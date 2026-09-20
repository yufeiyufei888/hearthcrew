package io.github.yufeiyufei888.hearthcrew.kernel;
import java.util.Map;
import java.util.Set;
/** Coordinates with unknown legacy ownership retain protection. */
public final class PlacementOwnership {
    private PlacementOwnership() {}
    public static void placed(Set<String> protectedBlocks, Map<String,String> owners, String key, String identity, boolean registeredCrew) {
        if (!registeredCrew) protectedBlocks.add(key);
        // Never turn an unknown old protected coordinate into a known crew-owned one.
        if (registeredCrew && protectedBlocks.contains(key) && !owners.containsKey(key)) return;
        owners.put(key,(registeredCrew ? "crew:" : "player:")+identity);
    }
    public static boolean removed(Set<String> protectedBlocks, Map<String,String> owners, String key, String expectedOwner) {
        if(expectedOwner == null || !expectedOwner.equals(owners.get(key)))return false;
        owners.remove(key);protectedBlocks.remove(key);return true;
    }
}
