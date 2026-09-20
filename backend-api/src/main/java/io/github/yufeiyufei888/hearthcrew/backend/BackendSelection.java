package io.github.yufeiyufei888.hearthcrew.backend;

/** Readable before Minecraft/Mixin target classes load. Build marker selects the release backend. */
public final class BackendSelection {
    private BackendSelection() {}
    public static String mode() {
        String explicit=System.getProperty("hearthcrew.executionBackend");
        if(explicit!=null)return explicit;
        return BackendSelection.class.getResource("/hearthcrew/numen-release.marker")!=null?"numen":"legacy";
    }
}
