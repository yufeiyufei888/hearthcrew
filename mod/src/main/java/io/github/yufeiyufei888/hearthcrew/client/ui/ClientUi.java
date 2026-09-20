package io.github.yufeiyufei888.hearthcrew.client.ui;

import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.InputConstants.Type;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.github.yufeiyufei888.hearthcrew.HearthCrew;
import io.github.yufeiyufei888.hearthcrew.network.UiNetwork;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.SubscribeEvent;
import net.neoforged.fml.common.EventBusSubscriber;
import net.neoforged.neoforge.client.event.RegisterKeyMappingsEvent;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import org.lwjgl.glfw.GLFW;

/** Client-only UI state and the single request gate used by HearthCrewScreen. */
@EventBusSubscriber(modid = HearthCrew.ID, value = Dist.CLIENT)
public final class ClientUi {
    public static final KeyMapping OPEN_KEY = new KeyMapping(
            "key.hearthcrew.open", Type.KEYSYM, GLFW.GLFW_KEY_H, KeyMapping.CATEGORY_INTERFACE);
    private static final long REQUEST_TIMEOUT_TICKS = 200L;

    private static volatile UiSnapshot snapshot = UiSnapshot.empty();
    private static volatile String notice = "待连接";
    private static final io.github.yufeiyufei888.hearthcrew.kernel.UiRequests requests = new io.github.yufeiyufei888.hearthcrew.kernel.UiRequests();
    private static UUID lastSent;
    private static long noticeUntil;
    private static boolean controllerOffline;
    /**
     * Commands are shown immediately on the client and are reconciled with
     * the durable journal when the next view arrives.  This keeps the chat
     * useful while a controller request is still in flight (or is rejected
     * while the controller is offline).
     */
    private static final List<UiSnapshot.Message> localMessages = new ArrayList<>();
    private static final java.util.Map<String,UiSnapshot.Message> conversation=new java.util.LinkedHashMap<>();
    private static final java.util.Map<String,List<UiSnapshot.Stage>> stagePages=new java.util.HashMap<>();
    private static final java.util.Map<String,Integer> stageNext=new java.util.HashMap<>();
    private static long historyNext=0,lastSnapshotMs;
    public static long snapshotAgeSeconds(){return lastSnapshotMs==0?-1:(System.currentTimeMillis()-lastSnapshotMs)/1000;}
    public static List<UiSnapshot.Stage> stages(String bot){return stagePages.getOrDefault(bot,List.of());}
    public static int nextStages(String bot){return stageNext.getOrDefault(bot,0);}
    public static long nextHistory(){return historyNext;}
    public static void requestStages(String bot,boolean more){if(!snapshot.valid())return;int cursor=more?nextStages(bot):0;if(cursor<0)return;var q=new JsonObject();q.addProperty("worldId",snapshot.worldId());q.addProperty("botId",bot);q.addProperty("cursor",cursor);send("detail",q.toString());}
    public static void requestHistory(){if(!snapshot.valid()||historyNext<0)return;var q=new JsonObject();q.addProperty("worldId",snapshot.worldId());q.addProperty("cursor",historyNext);send("history",q.toString());}
    private static String messageKey(UiSnapshot.Message m){return !m.messageId().isBlank()&&!UiSnapshot.UNKNOWN.equals(m.messageId())?m.messageId():m.sequence();}
    private static void mergeMessages(List<UiSnapshot.Message> rows){for(var m:rows)if(java.util.Set.of("crew.chat","owner.command","role.shared").contains(m.type()))conversation.put(messageKey(m),m);}
    public static void publicMessage(JsonObject data){
        if(!snapshot.valid()||!snapshot.worldId().equals(data.get("worldId").getAsString()))return;
        var row=new JsonObject();row.addProperty("type","crew.chat");row.addProperty("messageId",data.get("messageId").getAsString());row.addProperty("requestId",data.get("messageId").getAsString());row.addProperty("role",data.get("senderName").getAsString());row.addProperty("message",data.get("message").getAsString());row.addProperty("origin","companion");row.addProperty("atUtc",java.time.Instant.now().toString());
        var recipients=new com.google.gson.JsonArray();recipients.add(data.get("targetName").getAsString());row.add("recipients",recipients);if(data.has("replyTo"))row.add("replyTo",data.get("replyTo"));if(data.has("replyName"))row.add("replyName",data.get("replyName"));var rows=new com.google.gson.JsonArray();rows.add(row);mergeMessages(UiSnapshot.messages(rows));
    }
    private static long clientTicks;
    private static long lastStatusRequest = Long.MIN_VALUE;
    private static boolean initialized;
    private static boolean worldActive;
    private static boolean autoAttempted,autoFinished;
    private static long autoDeadline;

    private ClientUi() {}

    /** Called by the mod client event-bus wiring. Safe to call repeatedly. */
    public static void registerKeys(RegisterKeyMappingsEvent event) {
        event.register(OPEN_KEY);
    }

    /** Installs the read-only network projection exactly once. */
    public static synchronized void initialize() {
        if (initialized) return;
        initialized = true;
        UiNetwork.setChatHandler(packet -> Minecraft.getInstance().execute(() -> PublicChat.accept(packet.json())));
        UiNetwork.setClientHandler((Consumer<UiNetwork.Snapshot>) response -> {
            Minecraft minecraft = Minecraft.getInstance();
            minecraft.execute(() -> accept(response));
        });
    }

    @SubscribeEvent
    public static void clientTick(ClientTickEvent.Post event) {
        initialize();
        clientTicks++;
        Minecraft minecraft = Minecraft.getInstance();
        boolean connected = minecraft.player != null && minecraft.getConnection() != null;
        if (!connected) {
            if (worldActive || requests.foregroundPending() || snapshot.valid()) clearSessionState();
            worldActive = false;autoAttempted=false;autoFinished=false;autoDeadline=0;
            return;
        }
        if(!worldActive){autoDeadline=System.nanoTime()+60_000_000_000L;}
        worldActive = true;
        if(minecraft.hasSingleplayerServer()&&!autoFinished){
            if(System.nanoTime()>=autoDeadline){autoFinished=true;setNotice("控制器60秒内未完成新连接，请使用启动控制器按钮并查看诊断");}
            else if(snapshot.valid()&&!controllerOffline&&lastSnapshotMs>0){autoFinished=true;}
            else {
                if(controllerOffline&&!autoAttempted){autoAttempted=true;ControllerLauncher.start();}
                if(statusDue()&&!requests.foregroundPending())requestStatus(false);
            }
        }
        for(var expired:requests.expire(clientTicks)) {
            if("status".equals(expired.operation()))continue;
            if(isTaskMessage(expired.operation())) {
                updateLocalState(expired.id(),"UNKNOWN");
                addLocalMessage(new UiSnapshot.Message("local-"+UUID.randomUUID(),"","role.failure","系统","",List.of(),"命令回包超时，是否已接受待核对；未自动重发，请先查看任务与对话记录","UNKNOWN","",""));
            }
            setNotice("请求回包超时（"+expired.operation()+"），未自动重发");
        }
        if (minecraft.screen == null && OPEN_KEY.consumeClick()) open();
        if (minecraft.screen instanceof HearthCrewScreen && statusDue() && !requests.foregroundPending()) {
            requestStatus(false);
        }
    }

    public static void open() {
        initialize();
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.player == null || minecraft.getConnection() == null) {
            clearSessionState();
            return;
        }
        requestStatus(true);
        minecraft.setScreen(new HearthCrewScreen());
    }

    public static UiSnapshot snapshot() { return snapshot; }
    public static String notice() { return notice; }
    public static boolean controllerOffline() { return controllerOffline; }
    public static void setNotice(String value) { notice = value == null || value.isBlank() ? "" : value; noticeUntil=clientTicks+100; }
    public static boolean requestPending() { return requests.foregroundPending(); }
    public static String pendingOperation() { return requests.foregroundOperation(); }

    /** Returns the durable conversation plus client-side messages awaiting reconciliation. */
    public static synchronized List<UiSnapshot.Message> chatMessages() {
        mergeMessages(snapshot.messages());
        var result=new java.util.LinkedHashMap<>(conversation);
        for(var local:localMessages)if("owner.command".equals(local.type())&&!result.containsKey(messageKey(local)))result.put(messageKey(local),local);
        var sorted=new ArrayList<>(result.values());sorted.sort(java.util.Comparator.comparing(UiSnapshot.Message::atUtc).thenComparing(UiSnapshot.Message::sequence));return List.copyOf(sorted);
    }

    public static void requestStatus(boolean force) {
        initialize();
        if (requests.foregroundPending()) return;
        if (!force && !statusDue()) return;
        if (send("status", "")) lastStatusRequest = clientTicks;
    }

    public static boolean sendCommand(String message) {return sendCommand(message,"");}
    public static boolean sendCommand(String message,String botId) {
        if (message == null || message.isBlank()) {
            notice = "请输入命令";
            return false;
        }
        if (message.length() > 512) {
            notice = "命令超过 512 个字符";
            return false;
        }
        if (requests.foregroundPending()) {
            notice = "上一条请求仍在等待回包";
            return false;
        }
        var target=snapshot.companions().stream().filter(c->c.botId().equals(botId)).findFirst();
        if(!botId.isBlank()&&target.isEmpty()){notice="指定伙伴未同步；未改为小队发送";return false;}
        var payload=new com.google.gson.JsonObject();payload.addProperty("botId",botId);payload.addProperty("message",message);
        boolean sent = send(botId.isBlank()?"command":"task", botId.isBlank()?message:payload.toString());
        if (sent) addLocalMessage(new UiSnapshot.Message(
                "local-" + lastSent,
                lastSent.toString(), "owner.command", "team", botId,
                target.isPresent()?List.of(target.get().name()):List.of(), message, "SENDING", "", ""));
        return sent;
    }

    public static boolean retryPlanning(String botId){if(botId.isBlank()){notice="请先点击伙伴详情";return false;}if(requests.foregroundPending()){notice="请等待当前请求回包";return false;}var p=new com.google.gson.JsonObject();p.addProperty("botId",botId);return send("retry",p.toString());}
    private static boolean isTaskMessage(String operation){return "command".equals(operation)||"task".equals(operation);}
    public static boolean joinCompanions(){if(requests.foregroundPending()){notice="请等待当前请求完成后再加入伙伴";return false;}return send("join","");}

    public static boolean sendControl(String operation) {
        if (!"pause".equals(operation) && !"resume".equals(operation) && !"stop".equals(operation)
                && !"auto_on".equals(operation) && !"auto_off".equals(operation) && !"standby".equals(operation)) {
            notice = "未知控制操作";
            return false;
        }
        boolean urgentStop = "stop".equals(operation);
        if (requests.foregroundPending() && !urgentStop) {
            notice = "上一条请求仍在等待回包";
            return false;
        }
        return send(operation, "");
    }

    private static boolean send(String operationName, String message) {
        UiNetwork.Operation operation;
        try {
            operation = UiNetwork.Operation.valueOf(operationName.toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException invalidOperation) {
            notice = "未知操作：" + operationName;
            return false;
        }
        var ticket=requests.begin(operationName,clientTicks,REQUEST_TIMEOUT_TICKS);
        if(ticket==null){if(!"status".equals(operationName))setNotice("上一条操作仍在等待确认，请勿重复发送");return false;}
        UUID requestId=ticket.id();lastSent=requestId;
        if(!"status".equals(operationName))setNotice("等待 "+operationName+" 回包");
        try { UiNetwork.sendRequest(new UiNetwork.Request(requestId,operation,message)); }
        catch(RuntimeException error){requests.finish(requestId);if(!"status".equals(operationName))setNotice("发送失败："+error.getClass().getSimpleName());return false;}
        return true;
    }

    private static void accept(UiNetwork.Snapshot response) {
        if (response == null || response.requestId() == null) return;
        UUID requestId = response.requestId();
        var ticket=requests.finish(requestId);if(ticket==null)return;
        UiSnapshot candidate;
        JsonObject envelope = null;
        boolean failed = false;
        String error = "";
        try {
            JsonElement parsed = JsonParser.parseString(response.json());
            if (parsed.isJsonObject()) {
                envelope = parsed.getAsJsonObject();
                JsonElement ok = envelope.get("ok");
                failed = ok != null && ok.isJsonPrimitive() && !ok.getAsBoolean();
                JsonElement errorElement = envelope.get("error");
                error = errorElement != null && errorElement.isJsonPrimitive() ? errorElement.getAsString() : "";
                JsonElement view = envelope.get("view");
                candidate = view != null && view.isJsonObject()
                        ? UiSnapshot.from(view.getAsJsonObject())
                        : UiSnapshot.from(envelope);
            } else {
                candidate = UiSnapshot.empty();
            }
        } catch (RuntimeException malformed) {
            candidate = UiSnapshot.empty();
        }
        {
            try { if(envelope!=null&&envelope.has("page")&&envelope.get("page").isJsonObject()){
                var p=envelope.getAsJsonObject("page");if(snapshot.worldId().equals(p.get("worldId").getAsString())){
                    if("history".equals(p.get("type").getAsString())){mergeMessages(UiSnapshot.messages(p.getAsJsonArray("rows")));historyNext=p.get("nextCursor").isJsonNull()?-1:p.get("nextCursor").getAsLong();}
                    else if("detail".equals(p.get("type").getAsString())){String bot=p.get("botId").getAsString();var merged=new java.util.LinkedHashMap<String,UiSnapshot.Stage>();if(p.get("cursor").getAsInt()>0)for(var stage:stages(bot))merged.put(stage.key(),stage);for(var stage:UiSnapshot.stages(p.getAsJsonArray("rows")))merged.put(stage.key(),stage);stagePages.put(bot,List.copyOf(merged.values()));stageNext.put(bot,p.get("nextCursor").isJsonNull()?-1:p.get("nextCursor").getAsInt());}
                }
            }
            } catch(RuntimeException malformedPage){setNotice("历史回包格式无效，请刷新重试");return;}
            if (candidate.valid() && requests.applyView(ticket)) { if(snapshot.valid()&&!snapshot.worldId().equals(candidate.worldId())){conversation.clear();stagePages.clear();stageNext.clear();historyNext=0;localMessages.clear();}snapshot = candidate; controllerOffline=false;lastSnapshotMs=System.currentTimeMillis();mergeMessages(candidate.messages()); }
            boolean incompatible=envelope!=null&&envelope.has("view")&&!candidate.valid();
            if(failed && error.toLowerCase(java.util.Locale.ROOT).contains("controller is offline"))controllerOffline=true;
            String operation = ticket.operation();
            boolean knownAcknowledgement=envelope!=null&&envelope.has("ok")&&envelope.get("ok").isJsonPrimitive()&&envelope.getAsJsonPrimitive("ok").isBoolean();
            if (isTaskMessage(operation)) updateLocalState(requestId, knownAcknowledgement?(failed ? "FAILED" : "ACCEPTED"):"UNKNOWN",failed?readableError(error):"");

            if(incompatible)setNotice("UI协议不兼容，请同步更新Mod与控制器至0.3.2");
            else if(isTaskMessage(operation)&&!knownAcknowledgement)setNotice("命令回包格式无效，接受状态待核对；未自动重发");
            else if(!"status".equals(operation)||clientTicks>=noticeUntil)setNotice(failed ? "请求失败：" + readableError(error)
                    : "join".equals(operation) && envelope!=null && envelope.has("message") ? envelope.get("message").getAsString() : candidate.valid() ? operation + " 已收到回包" : operation + " 已收到回包，等待可用状态");
        }
    }

    private static boolean statusDue() {
        return lastStatusRequest == Long.MIN_VALUE || clientTicks - lastStatusRequest >= 20;
    }

    private static String readableError(String error) {
        if (error == null || error.isBlank()) return "控制器拒绝了请求";
        return switch (error) {
            case "LOGIN_REQUIRED" -> "尚未完成 Codex 登录";
            case "GAME_DISCONNECTED" -> "游戏连接已断开";
            case "INVALID_COMMAND" -> "命令无效";
            case "BUSY" -> "小队仍在处理上一项任务";
            default -> error.length() > 96 ? error.substring(0, 96) + "…" : error;
        };
    }

    private static void clearSessionState() {
        PublicChat.clear();conversation.clear();stagePages.clear();stageNext.clear();historyNext=0;lastSnapshotMs=0;
        snapshot = UiSnapshot.empty();
        synchronized (ClientUi.class) { localMessages.clear(); }
        requests.clear();lastSent=null;noticeUntil=0;controllerOffline=false;
        lastStatusRequest = Long.MIN_VALUE;
        notice = "待连接";
    }

    private static synchronized void addLocalMessage(UiSnapshot.Message message) {
        localMessages.add(message);
        if (localMessages.size() > 30) localMessages.subList(0, localMessages.size() - 30).clear();
    }

    private static synchronized void updateLocalState(UUID requestId, String state) {updateLocalState(requestId,state,"");}
    private static synchronized void updateLocalState(UUID requestId, String state,String reason) {
        String sequence = requestId == null ? "" : "local-" + requestId;
        for (int index = 0; index < localMessages.size(); index++) {
            UiSnapshot.Message message = localMessages.get(index);
            if (sequence.equals(message.sequence())) {
                localMessages.set(index, new UiSnapshot.Message(message.sequence(), message.requestId(), message.type(), message.role(),
                        message.botId(), message.recipients(), message.message(), state, message.taskId(), message.actionId(),message.messageId(),message.atUtc(),message.origin(),message.replyTo(),message.replyName(),reason.isBlank()?message.statusReason():reason));
                return;
            }
        }
    }
}
