package io.github.yufeiyufei888.hearthcrew.client.ui;

import com.google.gson.JsonParser;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;

/** Public speech only. No model reasoning or UI polling is rendered here. */
public final class PublicChat {
    private static final LinkedHashSet<String> seen = new LinkedHashSet<>();
    private static final Map<String, String> speakers = new LinkedHashMap<>();
    private static boolean enabled = true;
    private static boolean loaded;
    private PublicChat() {}
    private static Path preference() { return Minecraft.getInstance().gameDirectory.toPath().resolve("config/hearthcrew-chat.txt"); }
    public static boolean enabled() {
        if (!loaded) {
            loaded = true;
            try { if (Files.isRegularFile(preference())) enabled = !Files.readString(preference()).strip().equals("off"); }
            catch (java.io.IOException ignored) { }
        }
        return enabled;
    }
    public static void toggle() {
        enabled = !enabled();
        try { Files.createDirectories(preference().getParent()); Files.writeString(preference(), enabled ? "on" : "off"); }
        catch (java.io.IOException failure) { Minecraft.getInstance().gui.getChat().addMessage(Component.literal("[炉火伙伴] 聊天显示偏好保存失败，本次切换仍有效。")); }
    }
    public static void clear() { seen.clear(); speakers.clear(); }
    public static void accept(String json) {
        var minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.level == null) return;
        try {
            var data = JsonParser.parseString(json).getAsJsonObject();
            String id = data.get("messageId").getAsString(), world = data.get("worldId").getAsString();
            if (!seen.add(world + ":" + id)) return;
            if (seen.size() > 4096) seen.remove(seen.iterator().next());
            String sender = data.get("senderName").getAsString();
            speakers.put(id, sender);
            if (speakers.size() > 4096) speakers.remove(speakers.keySet().iterator().next());
            ClientUi.publicMessage(data);
            if (!enabled()) return;
            String reply = data.has("replyTo") ? "（回复 " + (data.has("replyName") ? data.get("replyName").getAsString() : speakers.getOrDefault(data.get("replyTo").getAsString(), "先前消息")) + "）" : "";
            String prefix = "[" + sender + " → " + data.get("targetName").getAsString() + "] " + reply;
            minecraft.gui.getChat().addMessage(Component.literal(prefix + data.get("message").getAsString()));
        } catch (RuntimeException invalid) { /* Reject malformed speech without disturbing the game. */ }
    }
}
