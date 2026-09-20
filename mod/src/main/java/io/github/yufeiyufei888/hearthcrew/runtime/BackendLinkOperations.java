package io.github.yufeiyufei888.hearthcrew.runtime;

import com.google.gson.*;
import io.github.yufeiyufei888.hearthcrew.backend.*;
import io.github.yufeiyufei888.hearthcrew.kernel.ActionPriority;
import net.minecraft.core.*;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.*;
import java.util.*;
import java.util.function.LongSupplier;

/** Protocol adapter for the new backend, sharing transport/UI but never a legacy executor. */
final class BackendLinkOperations {
    private static final Gson JSON=new Gson();
    private final MinecraftServer server;
    private final LongSupplier session;
    private final Map<UUID,Binding> bindings=new HashMap<>();
    private final Set<String> chats=new LinkedHashSet<>();
    private record Binding(String intentId,String origin,long intentGeneration,long bodyGeneration,long sessionEpoch,long controlRevision) {}
    BackendLinkOperations(MinecraftServer server,LongSupplier session){this.server=server;this.session=session;}
    static JsonObject descriptor(){var p=BackendWorld.provider();var descriptor=JSON.toJsonTree(Map.of("id",p.id(),"version",p.version(),"bodyType","ServerPlayer","executionProtocol",6,"actions",p.capabilities().stream().map(Enum::name).sorted().toList(),"continuousTasks",true,"preparationCheckpoints",true,"pauseResume",true,"bodyGenerations",true,"terminalFinality",true)).getAsJsonObject();descriptor.addProperty("navigationProgressVersion",1);descriptor.addProperty("exactMoveCompletion",true);descriptor.addProperty("boundedNoProgress",true);descriptor.addProperty("facilityStanceVerification",true);return descriptor;}
    JsonElement handle(String op,JsonObject request,JsonObject payload){
        if(op.equals("status")||op.equals("reconcile"))return snapshot();
        if(op.equals("team.status"))return JSON.toJsonTree(WorldEvents.team().snapshot());
        var b=selected(payload);var id=b.body().getUUID();var data=CrewWorldData.get(server);var ledger=BackendActions.get(server);
        if(!op.equals("observe"))generation(request,payload,b);
        return switch(op){
            case "observe"->observe(payload,b);
            case "intent.bind"->{
                String intent=text(request,"intentId",128),origin=text(payload,"origin",16);long revision=number(payload,"intentGeneration");
                if(!Set.of("owner","autonomous").contains(origin))throw new IllegalArgumentException("Invalid intent origin");
                available(b,origin);var prior=bindings.get(id);
                if(prior!=null&&(revision<prior.intentGeneration()||revision==prior.intentGeneration()&&(!intent.equals(prior.intentId())||!origin.equals(prior.origin()))))throw new IllegalArgumentException("Conflicting intent generation");
                var active=b.snapshot();if(active.state().equals("RUNNING")&&(prior==null||!intent.equals(prior.intentId()))&&!active.actionId().startsWith(intent+":"))throw new IllegalStateException("Body belongs to another active intent");
                var bound=new Binding(intent,origin,revision,active.identity().generation(),session.getAsLong(),data.controlRevision(id));bindings.put(id,bound);yield JSON.toJsonTree(bound);
            }
            case "action.submit"->{var binding=binding(request,payload,b);available(b,binding.origin());var order=ModLink.parseBackendOrder(payload);
                var input=new CompanionBackend.Request(b.snapshot().identity(),text(request,"actionId",128),order,binding.origin().equals("owner")?ActionPriority.OWNER:ActionPriority.PERSONAL);
                yield BackendActions.wire(ledger.dispatch(server,b,input));}
            case "action.cancel"->{String action=text(payload,"actionId",128);var current=b.snapshot();if(current.actionId().equals(action))b.cancel();ledger.reconcile(server,b);yield ledger.find(id,action).map(BackendActions::wire).orElseThrow(()->new IllegalArgumentException("Unknown action; current work was not cancelled"));}
            case "control"->{if(request.has("intentId"))binding(request,payload,b);String operation=text(payload,"operation",16);BackendWorld.control(server,id,operation);ledger.reconcile(server,b);yield JSON.toJsonTree(Map.of("count",1,"operation",operation));}
            case "action.history"->history(id,payload.has("beforeSequence")?number(payload,"beforeSequence"):Long.MAX_VALUE,payload.has("limit")?bounded(payload,"limit",1,64):16);
            case "action.reconcile_history"->{ledger.reconcileHistory(server,b);yield JSON.toJsonTree(Map.of("restored",false,"reason","native ledger is authoritative; caller-provided success is never imported"));}
            case "chat.publish"->chat(request,payload,b);
            default->throw new IllegalArgumentException("BACKEND_OPERATION_UNAVAILABLE:"+op);
        };
    }
    private JsonObject history(UUID id,long before,int limit){
        var ledger=BackendActions.get(server);var entries=ledger.page(id,before,limit);var rows=new JsonArray();int bytes=2;long cursor=before;boolean truncated=false;
        for(var e:entries){var row=BackendActions.wire(e);int size=row.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
            if(bytes+size>65536){truncated=true;break;}rows.add(row);bytes+=size;cursor=e.sequence();}
        if(!ledger.page(id,cursor,1).isEmpty())truncated=true;
        var ascending=new JsonArray();for(int i=rows.size()-1;i>=0;i--)ascending.add(rows.get(i));
        var result=new JsonObject();result.add("entries",ascending);result.addProperty("beforeSequence",cursor);result.addProperty("truncated",truncated);
        if(truncated&&rows.isEmpty())result.addProperty("error","HISTORY_ENTRY_TOO_LARGE: retained on server");return result;
    }
    private static int bounded(JsonObject o,String field,int min,int max){long n=number(o,field);if(n<min||n>max)throw new IllegalArgumentException(field+" must be "+min+".."+max);return (int)n;}
    private CompanionBackend selected(JsonObject payload){return BackendWorld.roster(server).body(UUID.fromString(text(payload,"botId",36)));}
    private void generation(JsonObject request,JsonObject payload,CompanionBackend b){long expected=number(request.has("bodyGeneration")?request:payload,"bodyGeneration");if(expected!=b.snapshot().identity().generation())throw new IllegalArgumentException("STALE_BODY_GENERATION");}
    private void available(CompanionBackend b,String origin){var id=b.body().getUUID();var r=BackendWorld.roster(server);var c=r.control(id);var d=CrewWorldData.get(server);
        if(c.paused()||c.stopped()||d.standby(id)||origin.equals("autonomous")&&!d.autonomyEnabled(id)||!Objects.toString(b.diagnostics().get("fault"),"").isEmpty())throw new IllegalStateException("Body paused, waiting for owner, or needs fault reconciliation");}
    private Binding binding(JsonObject request,JsonObject payload,CompanionBackend b){var current=bindings.get(b.body().getUUID());if(current==null||!current.intentId().equals(text(request,"intentId",128))||current.intentGeneration()!=number(payload,"intentGeneration")||current.bodyGeneration()!=b.snapshot().identity().generation()||current.sessionEpoch()!=session.getAsLong()||current.controlRevision()!=CrewWorldData.get(server).controlRevision(b.body().getUUID()))throw new IllegalStateException("Intent binding expired; observe and bind again");return current;}
    JsonObject snapshot(){
        var result=new JsonObject();result.addProperty("worldId",BackendWorld.roster(server).world().toString());result.addProperty("gameTick",server.overworld().getGameTime());result.addProperty("snapshotGeneration",server.overworld().getGameTime());result.addProperty("modVersion","0.3.2-experimental");result.addProperty("stage","0.3.2_DEVELOPMENT_UNACCEPTED");result.addProperty("executionProtocol",6);result.add("backend",descriptor());
        var caps=ActionCapabilities.definitions().deepCopy();for(String kind:new ArrayList<>(caps.keySet()))if(BackendWorld.provider().capabilities().stream().noneMatch(k->k.name().equals(kind)))caps.remove(kind);if(caps.has("COLLECT_RESOURCE")){var d=caps.getAsJsonObject("COLLECT_RESOURCE");var allowed=new JsonArray();for(var field:d.getAsJsonArray("allowed"))if(!field.getAsString().equals("position"))allowed.add(field);d.add("allowed",allowed);d.getAsJsonObject("example").remove("position");d.addProperty("fields",d.get("fields").getAsString()+" 范围以身体开始位置为中心，不接受position。");}if(caps.has("EXCAVATE")){var d=caps.getAsJsonObject("EXCAVATE");d.add("allowed",JSON.toJsonTree(List.of("position","count","accessBudget")));d.addProperty("fields","position为目标脚位；count与accessBudget取较小破坏预算，默认accessBudget16；32格内安全开路，不搭桥，不代表采得矿物。需要合适工具。 ");}result.add("capabilities",caps);
        result.add("team",JSON.toJsonTree(WorldEvents.team().snapshot()));var bots=new JsonArray();var ledger=BackendActions.get(server);var data=CrewWorldData.get(server);
        for(var backend:BackendWorld.live(server)){
            var p=backend.body();var identity=backend.snapshot().identity();var id=p.getUUID();var control=BackendWorld.roster(server).control(id);var b=new JsonObject();
            b.addProperty("botId",id.toString());b.addProperty("entityId",id.toString());b.addProperty("bodyGeneration",identity.generation());b.addProperty("bodyType","ServerPlayer");b.addProperty("name",p.getGameProfile().getName());b.addProperty("dimension",p.level().dimension().location().toString());b.add("position",position(p.getX(),p.getY(),p.getZ()));b.addProperty("health",p.getHealth());b.addProperty("food",p.getFoodData().getFoodLevel());b.addProperty("air",p.getAirSupply());b.addProperty("onGround",p.onGround());b.addProperty("selectedSlot",p.getInventory().selected);
            b.addProperty("paused",control.paused());b.addProperty("stopped",control.stopped());b.addProperty("standby",data.standby(id));b.addProperty("autonomyEnabled",data.autonomyEnabled(id));b.addProperty("controlRevision",data.controlRevision(id));b.add("intentBinding",bindings.containsKey(id)?JSON.toJsonTree(bindings.get(id)):JsonNull.INSTANCE);b.addProperty("recoveryInvalid",!Objects.toString(backend.diagnostics().get("fault"),"").isEmpty());
            var inventory=new JsonArray();var tools=new JsonArray();for(int slot=0;slot<36;slot++){var stack=p.getInventory().getItem(slot);if(stack.isEmpty())continue;var row=item(stack);row.addProperty("slot",slot);inventory.add(row);if(stack.isDamageableItem())tools.add(row.deepCopy());}b.add("inventory",inventory);b.add("tools",tools);b.add("mainHand",item(p.getMainHandItem()));
            var equip=new JsonObject();for(var slot:List.of(EquipmentSlot.HEAD,EquipmentSlot.CHEST,EquipmentSlot.LEGS,EquipmentSlot.FEET,EquipmentSlot.OFFHAND))equip.add(slot.getName(),item(p.getItemBySlot(slot)));b.add("equipment",equip);
            var state=backend.snapshot();var active=ledger.find(id,state.actionId()).filter(e->e.generation()==identity.generation()&&Set.of("RUNNING","ACCEPTED").contains(e.state())&&state.state().equals("RUNNING"));b.add("action",active.<JsonElement>map(BackendActions::wire).orElse(JsonNull.INSTANCE));b.add("execution",JSON.toJsonTree(backend.execution()));b.add("localSafety",JSON.toJsonTree(backend.diagnostics()));b.add("suspendedActionIds",new JsonArray());b.addProperty("ledgerVersion",2);b.addProperty("executionProtocol",6);b.addProperty("actionSequence",ledger.latestSequence(id));b.addProperty("snapshotGeneration",server.overworld().getGameTime());var history=history(id,Long.MAX_VALUE,8);b.add("actionJournal",history.remove("entries"));b.add("actionHistory",history);bots.add(b);
        }
        result.add("companions",bots);result.add("backendFaults",JSON.toJsonTree(BackendWorld.roster(server).faults()));return result;
    }
    private JsonObject observe(JsonObject payload,CompanionBackend b){
        if(payload.has("bodyGeneration"))generation(payload,payload,b);var result=snapshot();int radius=payload.has("radius")?bounded(payload,"radius",1,32):32;result.add("observation",NearbyObservation.scan(b.body(),radius));
        if(payload.has("recipes"))result.add("recipes",recipes(payload,b));
        if(payload.has("targets")){
            var requested=payload.getAsJsonArray("targets");if(requested.size()>32)throw new IllegalArgumentException("targets limit 32");var targets=new JsonArray();
            for(var raw:requested){var v=raw.getAsJsonObject();var p=BlockPos.containing(v.get("x").getAsDouble(),v.get("y").getAsDouble(),v.get("z").getAsDouble());var row=new JsonObject();row.add("position",position(p.getX(),p.getY(),p.getZ()));
                if(p.distSqr(b.body().blockPosition())>1024||!b.body().level().hasChunkAt(p))row.addProperty("state","unknown_or_outside_scope");
                else {var block=b.body().level().getBlockState(p);row.addProperty("block",BuiltInRegistries.BLOCK.getKey(block.getBlock()).toString());row.addProperty("protected",CrewWorldData.get(server).playerBlock(b.body().level(),p));if(Set.of("minecraft:crafting_table","minecraft:chest","minecraft:barrel","minecraft:furnace","minecraft:smoker","minecraft:blast_furnace").contains(BuiltInRegistries.BLOCK.getKey(block.getBlock()).toString())){var facility=BackendWorld.provider().inspectFacility(b.body(),p);row.addProperty("publicUsable",b.body().onGround()&&Set.of("observed","PUBLIC_WORKSTATION").contains(Objects.toString(facility.get("state"))));}row.addProperty("path","not_checked");row.addProperty("visibility","not_checked");row.addProperty("hasQualifiedTool",!block.requiresCorrectToolForDrops()||b.body().getInventory().items.stream().anyMatch(s->s.isCorrectToolForDrops(block)));}targets.add(row);
            }result.add("targetInspections",targets);
        }
        if(payload.has("containers")){
            var requested=payload.getAsJsonArray("containers");if(requested.size()>16)throw new IllegalArgumentException("containers limit 16");var entries=new JsonArray();
            for(var raw:requested){var v=raw.getAsJsonObject();int x=coordinate(v,"x"),y=coordinate(v,"y"),z=coordinate(v,"z");
                entries.add(JSON.toJsonTree(BackendWorld.provider().inspectFacility(b.body(),new BlockPos(x,y,z))));}result.add("containers",entries);
        }return result;
    }
    private static int coordinate(JsonObject o,String field){if(!o.has(field)||!o.get(field).isJsonPrimitive()||!o.getAsJsonPrimitive(field).isNumber())throw new IllegalArgumentException("Missing coordinate "+field);
        try{int n=o.get(field).getAsBigDecimal().intValueExact();if(Math.abs((long)n)>30_000_000)throw new ArithmeticException();return n;}catch(ArithmeticException e){throw new IllegalArgumentException("Invalid block coordinate "+field);}}
    private JsonArray recipes(JsonObject payload,CompanionBackend b){
        var wanted=new HashSet<String>();for(var id:payload.getAsJsonArray("recipes"))wanted.add(id.getAsString());if(wanted.size()>16)throw new IllegalArgumentException("recipes limit 16");int runs=payload.has("recipeCount")?bounded(payload,"recipeCount",1,64):1;if(runs<1||runs>64)throw new IllegalArgumentException("recipeCount 1..64");var rows=new JsonArray();
        for(var holder:server.getRecipeManager().getAllRecipesFor(RecipeType.CRAFTING)){
            var recipe=holder.value();var output=recipe.getResultItem(server.registryAccess());if(output.isEmpty()||!wanted.contains(BuiltInRegistries.ITEM.getKey(output.getItem()).toString()))continue;
            var row=new JsonObject();row.addProperty("recipeId",holder.id().toString());row.addProperty("output",BuiltInRegistries.ITEM.getKey(output.getItem()).toString());row.addProperty("count",output.getCount());boolean portable=recipe.canCraftInDimensions(2,2);row.addProperty("station",portable?"inventory_2x2":"minecraft:crafting_table");row.addProperty("stationState",portable?"portable":"unknown_requires_check");boolean supported=(recipe instanceof ShapedRecipe||recipe instanceof ShapelessRecipe)&&!recipe.isSpecial()&&recipe.getIngredients().stream().flatMap(i->Arrays.stream(i.getItems())).noneMatch(ItemStack::hasCraftingRemainingItem);row.addProperty("backendRecipeSupported",supported);
            var facts=io.github.yufeiyufei888.hearthcrew.gameplay.RecipeActions.assess(b.body(),recipe,runs);facts.forEach((k,v)->row.add(k,JSON.toJsonTree(v)));row.addProperty("craftableNow",supported&&portable&&Boolean.TRUE.equals(facts.get("materialsReady")));row.add("selfPreparation",JSON.toJsonTree(Map.of("state",supported&&portable&&Boolean.TRUE.equals(facts.get("materialsReady"))?"ready_step":"requires_validation","reason","Exact recipe/material facts; station path and preparation must be verified by the backend")));rows.add(row);if(rows.size()==32)break;
        }return rows;
    }
    private JsonElement chat(JsonObject request,JsonObject payload,CompanionBackend b){
        var bound=binding(request,payload,b);available(b,bound.origin());var member=BackendWorld.roster(server).member(b.body().getUUID());var owner=server.getPlayerList().getPlayer(member.owner());if(owner==null)throw new IllegalStateException("Owner unavailable");
        if(!text(payload,"worldId",128).equals(BackendWorld.roster(server).world().toString())||!text(payload,"senderId",36).equals(member.id().toString()))throw new IllegalArgumentException("Chat identity mismatch");
        String id=text(payload,"messageId",128),message=text(payload,"message",400).replaceAll("[\\p{Cntrl}§]"," ");if(message.codePointCount(0,message.length())>200)throw new IllegalArgumentException("Chat exceeds 200 characters");
        var recipients=payload.getAsJsonArray("recipientIds");if(recipients==null||recipients.size()>2)throw new IllegalArgumentException("Invalid recipients");var names=new ArrayList<String>();for(var raw:recipients){var peer=BackendWorld.roster(server).member(UUID.fromString(raw.getAsString()));if(!peer.owner().equals(member.owner())||peer.id().equals(member.id()))throw new IllegalArgumentException("Invalid recipient identity");names.add(peer.name());}
        if(!chats.contains(id)){var packet=new JsonObject();packet.addProperty("worldId",BackendWorld.roster(server).world().toString());packet.addProperty("messageId",id);packet.addProperty("senderName",member.name());packet.addProperty("message",message);packet.addProperty("targetName",names.isEmpty()?"你":names.size()==2?"小队":names.getFirst());if(payload.has("replyTo"))packet.addProperty("replyTo",text(payload,"replyTo",128));if(payload.has("replyName"))packet.addProperty("replyName",text(payload,"replyName",128));io.github.yufeiyufei888.hearthcrew.network.UiNetwork.sendChat(owner,packet.toString());chats.add(id);}return JSON.toJsonTree(Map.of("messageId",id,"delivered",true));
    }
    private static JsonObject item(ItemStack stack){var row=new JsonObject();row.addProperty("item",BuiltInRegistries.ITEM.getKey(stack.getItem()).toString());row.addProperty("count",stack.getCount());if(stack.isDamageableItem()){row.addProperty("damage",stack.getDamageValue());row.addProperty("remainingDurability",stack.getMaxDamage()-stack.getDamageValue());}return row;}
    private static JsonObject position(double x,double y,double z){return JSON.toJsonTree(Map.of("x",x,"y",y,"z",z)).getAsJsonObject();}
    private static String text(JsonObject o,String field,int max){if(!o.has(field)||!o.get(field).isJsonPrimitive()||!o.get(field).getAsJsonPrimitive().isString())throw new IllegalArgumentException("Missing string "+field);String value=o.get(field).getAsString();if(value.isBlank()||value.length()>max)throw new IllegalArgumentException("Invalid "+field);return value;}
    private static long number(JsonObject o,String field){if(!o.has(field)||!o.get(field).isJsonPrimitive()||!o.get(field).getAsJsonPrimitive().isNumber())throw new IllegalArgumentException("Missing integer "+field);try{long n=o.get(field).getAsBigDecimal().longValueExact();if(n<0)throw new ArithmeticException();return n;}catch(ArithmeticException invalid){throw new IllegalArgumentException("Invalid nonnegative integer "+field);}}
}
