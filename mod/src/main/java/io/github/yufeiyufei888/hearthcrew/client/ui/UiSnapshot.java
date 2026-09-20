package io.github.yufeiyufei888.hearthcrew.client.ui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Immutable client-side projection of bridge ui-view schema version 2.
 * Unknown, missing, and malformed values remain visible as {@code unknown};
 * this class never invents a game state.
 */
public final class UiSnapshot {
    public static final String UNKNOWN = "unknown";

    public record Position(String x, String y, String z, boolean known) {
        static Position unknown() { return new Position(UNKNOWN, UNKNOWN, UNKNOWN, false); }
    }

    public record InventorySlot(int slot, String item, int count) {}

    /** A single read-only equipment slot. An empty known slot is represented by item=unknown and count=0. */
    public record EquippedItem(String item, int count) {}

    public record Companion(String botId, String name, String role, String dimension,
                            Position position, String health, String food, String action,
                            List<InventorySlot> inventory, int selectedSlot,
                            Map<String, EquippedItem> equipment, boolean equipmentKnown,
                            boolean autonomyEnabled, String activity, String goal, String waitReason, String recoverySummary) {
        /** Source-compatible constructor for older fixtures and snapshots. */
        public Companion(String botId, String name, String role, String dimension,
                         Position position, String health, String food, String action,
                         List<InventorySlot> inventory) {
            this(botId, name, role, dimension, position, health, food, action, inventory,
                    -1, Map.of(), false, false, UNKNOWN, UNKNOWN, UNKNOWN, UNKNOWN);
        }
    }

    public record BuildStep(Position position, String block) {}

    public record Task(String taskId, String botId, String intentId, String kind, String state,
                       String actionId, String bodyGeneration, String position, String target,
                       String count, String resource, List<BuildStep> steps) {}

    public record Message(String sequence, String requestId, String type, String role, String botId,
                          List<String> recipients, String message, String state,
                          String taskId, String actionId, String messageId, String atUtc, String origin, String replyTo, String replyName, String statusReason) {
        public Message(String sequence,String requestId,String type,String role,String botId,List<String> recipients,String message,String state,String taskId,String actionId){this(sequence,requestId,type,role,botId,recipients,message,state,taskId,actionId,requestId,java.time.Instant.now().toString(),"owner.command".equals(type)?"player":"companion",UNKNOWN,UNKNOWN,UNKNOWN);}
    }
    public record Work(String botId,String name,String state,String goal,String fullGoal,String stage,String fullStage,String step,String progress,String waitReason,String source,String completion,List<String> pending) {}
    public record Stage(String key,String title,String summary,String state,String reason,String completion,List<String> attempts) {}
    private List<Work> work=List.of();
    private String worldId=UNKNOWN,updatedAt=UNKNOWN;
    public List<Work> work(){return work;}
    public String worldId(){return worldId;}
    public String updatedAt(){return updatedAt;}
    public static List<Stage> stages(JsonArray source){var result=new ArrayList<Stage>();for(var raw:source){var row=object(raw);if(row!=null)result.add(new Stage(text(row.get("key")),text(row.get("title")),text(row.get("summary")),text(row.get("state")),text(row.get("reason")),text(row.get("completion")),strings(array(row,"attempts"))));}return List.copyOf(result);}
    private static List<String> strings(JsonArray array){var values=new ArrayList<String>();for(var x:array)values.add(text(x));return List.copyOf(values);}
    private static List<Work> work(JsonArray source){var result=new ArrayList<Work>();for(var raw:source){var row=object(raw);if(row!=null)result.add(new Work(text(row.get("botId")),text(row.get("name")),text(row.get("state")),text(row.get("goal")),text(row.get("fullGoal")),text(row.get("stage")),text(row.get("fullStage")),text(row.get("step")),text(row.get("progress")),text(row.get("waitReason")),text(row.get("source")),text(row.get("completion")),strings(array(row,"pending"))));if(result.size()==3)break;}return List.copyOf(result);}


    public record Layer(String status, Map<String, String> details) {}

    public record Role(String role, String botId, String status, String taskId,
                       String taskState, String model, String effort, String serviceTier,
                       boolean autonomyEnabled, String activity, String goal, String waitReason, String name, String threadId) {
        public Role(String role, String botId, String status, String taskId, String taskState, String model, String effort, String serviceTier,
                    boolean autonomyEnabled, String activity, String goal, String waitReason) {
            this(role, botId, status, taskId, taskState, model, effort, serviceTier, autonomyEnabled, activity, goal, waitReason, UNKNOWN, UNKNOWN);
        }
        /** Source-compatible constructor for older diagnostic fixtures. */
        public Role(String role, String botId, String status, String taskId,
                    String taskState, String model, String effort, String serviceTier) {
            this(role, botId, status, taskId, taskState, model, effort, serviceTier,
                    false, UNKNOWN, UNKNOWN, UNKNOWN);
        }
    }

    public record Diagnostics(Layer game, Layer controller, Layer appServer, List<Role> roles) {
        static Diagnostics unknown() {
            Layer layer = new Layer(UNKNOWN, Map.of());
            return new Diagnostics(layer, layer, layer, List.of());
        }
    }

    private final int schemaVersion;
    private final boolean truncated;
    private final List<Companion> companions;
    private final List<Task> tasks;
    private final List<Message> messages;
    private final Diagnostics diagnostics;
    private final boolean valid;

    private UiSnapshot(int schemaVersion, boolean truncated, List<Companion> companions,
                       List<Task> tasks, List<Message> messages, Diagnostics diagnostics, boolean valid) {
        this.schemaVersion = schemaVersion;
        this.truncated = truncated;
        this.companions = List.copyOf(companions);
        this.tasks = List.copyOf(tasks);
        this.messages = List.copyOf(messages);
        this.diagnostics = diagnostics;
        this.valid = valid;
    }

    public static UiSnapshot empty() {
        return new UiSnapshot(2, false, List.of(), List.of(), List.of(), Diagnostics.unknown(), false);
    }

    public static UiSnapshot from(JsonObject root) {
        if (root == null || !root.has("schemaVersion") || integer(root.get("schemaVersion"), -1) != 2) {
            return empty();
        }
        try {
            List<Companion> companions = companions(array(root, "companions"));
            List<Task> tasks = tasks(array(root, "tasks"));
            List<Message> messages = messages(array(root, "messages"));
            var snapshot=new UiSnapshot(2, bool(root.get("truncated")), companions, tasks, messages, diagnostics(object(root, "diagnostics")), true);
            snapshot.work=work(array(root,"work"));snapshot.worldId=text(root.get("worldId"));snapshot.updatedAt=text(root.get("updatedAt"));return snapshot;
        } catch (RuntimeException malformed) {
            return empty();
        }
    }

    public int schemaVersion() { return schemaVersion; }
    public boolean truncated() { return truncated; }
    public List<Companion> companions() { return companions; }
    public List<Task> tasks() { return tasks; }
    public List<Message> messages() { return messages; }
    public Diagnostics diagnostics() { return diagnostics; }
    public boolean valid() { return valid; }

    public boolean paused() {
        String status = diagnostics.game().status();
        return "PAUSED".equalsIgnoreCase(status) || "STOPPED".equalsIgnoreCase(status) || "暂停".equals(status);
    }

    private static List<Companion> companions(JsonArray source) {
        List<Companion> result = new ArrayList<>();
        for (JsonElement element : source) {
            JsonObject row = object(element);
            if (row == null) continue;
            List<InventorySlot> inventory = new ArrayList<>();
            for (JsonElement slotElement : array(row, "inventory")) {
                JsonObject slot = object(slotElement);
                if (slot == null) continue;
                int index = integer(slot.get("slot"), -1);
                int count = integer(slot.get("count"), 0);
                if (index >= 0 && index < 36 && count > 0) inventory.add(new InventorySlot(index, text(slot.get("item")), count));
            }
            int selectedSlot = integer(row.get("selectedSlot"), -1);
            if (selectedSlot < 0 || selectedSlot > 8) selectedSlot = -1;
            boolean equipmentKnown = row.has("equipment") && row.get("equipment").isJsonObject();
            Map<String, EquippedItem> equipment = equipment(row.get("equipment"));
            result.add(new Companion(text(row.get("botId")), text(row.get("name")), text(row.get("role")),
                    text(row.get("dimension")), position(row.get("position")), text(row.get("health")),
                    text(row.get("food")), text(row.get("action")), inventory, selectedSlot, equipment,
                    equipmentKnown, bool(row.get("autonomyEnabled")), text(row.get("activity")),
                    text(row.get("goal")), text(row.get("waitReason")), text(row.get("recoverySummary"))));
            if (result.size() == 3) break;
        }
        return result;
    }

    private static List<Task> tasks(JsonArray source) {
        List<Task> result = new ArrayList<>();
        for (JsonElement element : source) {
            JsonObject row = object(element);
            if (row == null) continue;
            JsonObject parameters = object(row.get("parameters"));
            List<BuildStep> steps = new ArrayList<>();
            for (JsonElement stepElement : array(parameters, "steps")) {
                JsonObject step = object(stepElement);
                if (step != null) steps.add(new BuildStep(position(step.get("position")), text(step.get("block"))));
                if (steps.size() == 16) break;
            }
            result.add(new Task(text(row.get("taskId")), text(row.get("botId")), text(row.get("intentId")),
                    text(row.get("kind")), text(row.get("state")), text(row.get("actionId")),
                    text(row.get("bodyGeneration")), positionText(parameters == null ? null : parameters.get("position")), text(parameters == null ? null : parameters.get("target")),
                    text(parameters == null ? null : parameters.get("count")), text(parameters == null ? null : parameters.get("resource")), steps));
            if (result.size() == 24) break;
        }
        return result;
    }

    public static List<Message> messages(JsonArray source) {
        List<Message> result = new ArrayList<>();
        int start = Math.max(0, source.size() - 30);
        for (int index = start; index < source.size(); index++) {
            JsonObject row = object(source.get(index));
            if (row == null) continue;
            List<String> recipients = new ArrayList<>();
            for (JsonElement recipient : array(row, "recipients")) recipients.add(text(recipient));
            result.add(new Message(text(row.get("sequence")), text(row.get("requestId")), text(row.get("type")), text(row.get("role")),
                    text(row.get("botId")), recipients, text(row.get("message")), text(row.get("state")),
                    text(row.get("taskId")), text(row.get("actionId")),text(row.get("messageId")),text(row.get("atUtc")),text(row.get("origin")),text(row.get("replyTo")),text(row.get("replyName")),text(row.get("statusReason"))));
        }
        return result;
    }

    private static Diagnostics diagnostics(JsonObject source) {
        if (source == null) return Diagnostics.unknown();
        List<Role> roles = new ArrayList<>();
        for (JsonElement element : array(source, "roles")) {
            JsonObject row = object(element);
            if (row == null) continue;
            roles.add(new Role(text(row.get("role")), text(row.get("botId")), text(row.get("status")),
                    text(row.get("taskId")), text(row.get("taskState")), text(row.get("model")),
                    text(row.get("effort")), text(row.get("serviceTier")), bool(row.get("autonomyEnabled")),
                    text(row.get("activity")), text(row.get("goal")), text(row.get("waitReason")), text(row.get("name")), text(row.get("threadId"))));
            if (roles.size() == 3) break;
        }
        return new Diagnostics(layer(object(source, "game")), layer(object(source, "controller")),
                layer(object(source, "appServer")), roles);
    }

    private static Layer layer(JsonObject source) {
        if (source == null) return new Layer(UNKNOWN, Map.of());
        Map<String, String> details = new LinkedHashMap<>();
        JsonObject detail = object(source.get("details"));
        if (detail != null) for (Map.Entry<String, JsonElement> entry : detail.entrySet()) details.put(entry.getKey(), text(entry.getValue()));
        return new Layer(text(source.get("status")), details);
    }

    private static Position position(JsonElement element) {
        JsonObject source = object(element);
        if (source == null) return Position.unknown();
        String x = text(source.get("x"));
        String y = text(source.get("y"));
        String z = text(source.get("z"));
        return new Position(x, y, z, !UNKNOWN.equals(x) && !UNKNOWN.equals(y) && !UNKNOWN.equals(z));
    }

    private static String positionText(JsonElement element) {
        Position value = position(element);
        return value.known() ? value.x() + ", " + value.y() + ", " + value.z() : UNKNOWN;
    }

    private static String value(JsonObject object, String key) {
        return object == null ? UNKNOWN : text(object.get(key));
    }

    private static Map<String, EquippedItem> equipment(JsonElement element) {
        JsonObject source = object(element);
        if (source == null) return Map.of();
        Map<String, EquippedItem> result = new LinkedHashMap<>();
        for (String key : List.of("head", "chest", "legs", "feet", "offhand")) {
            JsonElement value = source.get(key);
            JsonObject item = object(value);
            if (item == null) {
                result.put(key, new EquippedItem(UNKNOWN, 0));
            } else {
                int count = integer(item.get("count"), 0);
                result.put(key, new EquippedItem(text(item.get("item")), Math.max(0, count)));
            }
        }
        return java.util.Collections.unmodifiableMap(result);
    }

    private static JsonArray array(JsonObject object, String key) {
        if (object == null || !object.has(key) || !object.get(key).isJsonArray()) return new JsonArray();
        return object.getAsJsonArray(key);
    }

    private static JsonObject object(JsonObject object, String key) {
        return object == null ? null : object(object.get(key));
    }

    private static JsonObject object(JsonElement element) {
        return element != null && element.isJsonObject() ? element.getAsJsonObject() : null;
    }

    private static String text(JsonElement element) {
        if (element == null || element.isJsonNull()) return UNKNOWN;
        try {
            if (element.isJsonPrimitive()) return element.getAsString().isEmpty() ? UNKNOWN : element.getAsString().substring(0, Math.min(256, element.getAsString().length()));
        } catch (RuntimeException ignored) { }
        return UNKNOWN;
    }

    private static int integer(JsonElement element, int fallback) {
        try { return element != null && element.isJsonPrimitive() ? element.getAsInt() : fallback; }
        catch (RuntimeException ignored) { return fallback; }
    }

    private static boolean bool(JsonElement element) {
        try { return element != null && element.isJsonPrimitive() && element.getAsBoolean(); }
        catch (RuntimeException ignored) { return false; }
    }
}
