package io.github.yufeiyufei888.hearthcrew.entity;

import io.github.yufeiyufei888.hearthcrew.kernel.*;
import java.util.*;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.*;
import net.minecraft.resources.ResourceLocation;

/** Stores request identities, never a recipe for replaying saved world writes. */
final class ActionLedgerCodec {
    static final int MAX_ACTIONS = 65_536;
    private ActionLedgerCodec() {}
    static ListTag encode(Collection<ActionRequest<BodyOrder>> requests) {
        ListTag result = new ListTag();
        requests.stream().sorted(Comparator.comparing(request -> request.id().value())).forEach(request -> {
            CompoundTag entry = new CompoundTag();
            entry.putString("id", request.id().value()); entry.putString("priority", request.priority().name());
            entry.putLong("world", request.epoch().worldGeneration()); entry.putLong("session", request.epoch().sessionGeneration());
            entry.putLong("body", request.epoch().bodyGeneration());
            entry.putLong("submitted", request.submittedGameTick()); entry.putLong("deadline", request.deadlineGameTick());
            BodyOrder order = request.payload(); entry.putString("kind", order.kind().name()); entry.putInt("count", order.count());
            if (order.position() != null) entry.putLong("position", order.position().asLong());
            if (order.target() != null) entry.putUUID("target", order.target());
            if (order.resource() != null) entry.putString("resource", order.resource().toString());
            if (!order.steps().isEmpty()) {
                ListTag steps = new ListTag();
                for (BodyOrder.BuildStep step : order.steps()) {
                    CompoundTag encoded = new CompoundTag();
                    CompoundTag position = new CompoundTag();
                    position.putInt("x", step.position().getX());
                    position.putInt("y", step.position().getY());
                    position.putInt("z", step.position().getZ());
                    encoded.put("position", position);
                    encoded.putString("block", step.block().toString());
                    steps.add(encoded);
                }
                entry.put("steps", steps);
            }
            var prep=new CompoundTag();prep.putBoolean("enabled",order.preparation().enabled());prep.putInt("maxDepth",order.preparation().maxDepth());prep.putInt("maxSteps",order.preparation().maxSteps());prep.putInt("maxBreaks",order.preparation().maxBreaks());entry.put("preparation",prep);
            entry.putInt("radius",order.radius()); entry.putInt("accessBudget",order.accessBudget());
            if(!order.candidates().isEmpty()){var candidates=new ListTag();order.candidates().forEach(c->candidates.add(StringTag.valueOf(c.toString())));entry.put("candidates",candidates);}
            if(!order.actions().isEmpty()){
                var children=new ArrayList<ActionRequest<BodyOrder>>();for(int i=0;i<order.actions().size();i++)children.add(new ActionRequest<>(ActionId.of("step-"+i),request.priority(),order.actions().get(i),request.epoch(),request.submittedGameTick(),request.deadlineGameTick()));entry.put("actions",encode(children));
            }
            result.add(entry);
        });
        return result;
    }
    static List<ActionRequest<BodyOrder>> decode(ListTag entries) {
        if (entries.size() > MAX_ACTIONS) throw new IllegalArgumentException("action ledger exceeds capacity");
        List<ActionRequest<BodyOrder>> requests = new ArrayList<>();
        for (Tag value : entries) {
            CompoundTag entry = (CompoundTag)value;
            List<BodyOrder.BuildStep> steps = List.of();
            if (entry.contains("steps")) {
                if (!entry.contains("steps", Tag.TAG_LIST)) throw new IllegalArgumentException("invalid build steps tag");
                ListTag encodedSteps = entry.getList("steps", Tag.TAG_COMPOUND);
                if (encodedSteps.size() > 16) throw new IllegalArgumentException("build steps exceed capacity");
                List<BodyOrder.BuildStep> decoded = new ArrayList<>();
                for (Tag encodedValue : encodedSteps) {
                    if (!(encodedValue instanceof CompoundTag encoded) || !encoded.contains("position", Tag.TAG_COMPOUND)
                            || !encoded.contains("block", Tag.TAG_STRING)) throw new IllegalArgumentException("invalid build step");
                    CompoundTag position = encoded.getCompound("position");
                    if (!position.contains("x", Tag.TAG_INT) || !position.contains("y", Tag.TAG_INT)
                            || !position.contains("z", Tag.TAG_INT)) throw new IllegalArgumentException("invalid build step position");
                    decoded.add(new BodyOrder.BuildStep(new BlockPos(position.getInt("x"), position.getInt("y"), position.getInt("z")),
                            ResourceLocation.parse(encoded.getString("block"))));
                }
                steps = List.copyOf(decoded);
            }
            List<BodyOrder> children=List.of();if(entry.contains("actions")){var list=entry.getList("actions",Tag.TAG_COMPOUND);if(list.isEmpty()||list.size()>8||list.stream().anyMatch(t->((CompoundTag)t).contains("actions")))throw new IllegalArgumentException("invalid nested sequence");children=decode(list).stream().map(ActionRequest::payload).toList();}
            List<ResourceLocation> candidates=new ArrayList<>();if(entry.contains("candidates")){var list=entry.getList("candidates",Tag.TAG_STRING);if(list.isEmpty()||list.size()>16)throw new IllegalArgumentException("invalid collection candidates");for(var tag:list)candidates.add(ResourceLocation.parse(tag.getAsString()));}
            BodyOrder order = new BodyOrder(BodyOrder.Kind.valueOf(entry.getString("kind")),
                    entry.contains("position") ? BlockPos.of(entry.getLong("position")) : null,
                    entry.hasUUID("target") ? entry.getUUID("target") : null, entry.getInt("count"),
                    entry.contains("resource") ? ResourceLocation.parse(entry.getString("resource")) : null, steps,children,candidates,entry.contains("radius")?entry.getInt("radius"):32,entry.contains("accessBudget")?entry.getInt("accessBudget"):0,entry.contains("preparation")?new BodyOrder.Preparation(entry.getCompound("preparation").getBoolean("enabled"),entry.getCompound("preparation").getInt("maxDepth"),entry.getCompound("preparation").getInt("maxSteps"),entry.getCompound("preparation").getInt("maxBreaks")):BodyOrder.Preparation.disabled());
            requests.add(new ActionRequest<>(ActionId.of(entry.getString("id")), ActionPriority.valueOf(entry.getString("priority")), order,
                    new WorldEpoch(entry.getLong("world"), entry.getLong("session"), entry.getLong("body")),
                    entry.getLong("submitted"), entry.getLong("deadline")));
        }
        return List.copyOf(requests);
    }
}
