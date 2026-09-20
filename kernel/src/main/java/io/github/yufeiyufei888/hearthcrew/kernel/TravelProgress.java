package io.github.yufeiyufei888.hearthcrew.kernel;

/** Progress is measured toward a goal, never by drifting away from an old position. */
public final class TravelProgress {
    public enum Result { ADVANCING, REPLAN, BLOCKED }
    private long lastProgress;
    private double bestDistance, bestHeight;
    private int furthestNode;
    private boolean replanned;
    public TravelProgress(long tick, double distance, double height) {
        lastProgress=tick;bestDistance=distance;bestHeight=height;
    }
    public Result update(long tick, double distance, double height, int pathNode) {
        if (distance < bestDistance - 0.35 || height < bestHeight - 0.5
                || (pathNode > furthestNode && distance < bestDistance + 0.25)) {
            bestDistance=Math.min(bestDistance,distance); bestHeight=Math.min(bestHeight,height);
            furthestNode=Math.max(furthestNode,pathNode);lastProgress=tick;
        }
        if (tick-lastProgress < 100) return Result.ADVANCING;
        if (!replanned) {replanned=true;lastProgress=tick;return Result.REPLAN;}
        return Result.BLOCKED;
    }
    public boolean replanned() {return replanned;}
}
