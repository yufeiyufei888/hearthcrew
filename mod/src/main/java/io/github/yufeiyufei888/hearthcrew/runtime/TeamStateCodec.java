package io.github.yufeiyufei888.hearthcrew.runtime;

import com.google.gson.*;
import io.github.yufeiyufei888.hearthcrew.kernel.team.ResourceKey;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.Tag;

/** Versioned, bounded world data. It contains observations and identities, never replay instructions. */
final class TeamStateCodec {
    private static final int MAX_BYTES = 8 * 1024 * 1024;
    private static final Gson JSON = new GsonBuilder().enableComplexMapKeySerialization()
            .registerTypeAdapter(ResourceKey.Address.class, new AddressAdapter()).create();
    private TeamStateCodec() {}
    static CompoundTag encode(CrewTeamService.Saved saved) {
        byte[] bytes = JSON.toJson(saved).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_BYTES) throw new IllegalArgumentException("team state exceeds persistence capacity");
        CompoundTag tag = new CompoundTag(); tag.putInt("version", 1); tag.putByteArray("payload", bytes); return tag;
    }
    static boolean fits(CrewTeamService.Saved saved) {
        return JSON.toJson(saved).getBytes(StandardCharsets.UTF_8).length <= MAX_BYTES;
    }
    static CrewTeamService.Saved decode(CompoundTag tag) {
        if (!tag.contains("version", Tag.TAG_INT) || tag.getInt("version") != 1 || !tag.contains("payload", Tag.TAG_BYTE_ARRAY))
            throw new IllegalArgumentException("unrecognized team archive");
        byte[] bytes = tag.getByteArray("payload");
        if (bytes.length == 0 || bytes.length > MAX_BYTES) throw new IllegalArgumentException("invalid team archive length");
        try {
            String text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
            CrewTeamService.Saved saved = JSON.fromJson(text, CrewTeamService.Saved.class);
            if (saved == null) throw new IllegalArgumentException("empty team archive");
            return saved;
        } catch (java.nio.charset.CharacterCodingException invalid) { throw new IllegalArgumentException("invalid team archive encoding", invalid); }
    }
    static String definitionJson(CrewTeamService.Work work) {
        JsonObject definition = JSON.toJsonTree(work).getAsJsonObject();
        // Preserve primitive task fingerprints from archives written before BUILD existed.
        if (work.steps().isEmpty()) definition.remove("steps");
        return JSON.toJson(definition);
    }
    private static final class AddressAdapter implements JsonSerializer<ResourceKey.Address>, JsonDeserializer<ResourceKey.Address> {
        @Override public JsonElement serialize(ResourceKey.Address value, java.lang.reflect.Type type, JsonSerializationContext context) {
            JsonObject result = new JsonObject(); result.addProperty("type", value.getClass().getSimpleName());
            result.add("value", context.serialize(value, value.getClass())); return result;
        }
        @Override public ResourceKey.Address deserialize(JsonElement json, java.lang.reflect.Type type, JsonDeserializationContext context) {
            JsonObject object = json.getAsJsonObject();
            Class<? extends ResourceKey.Address> address = switch (object.get("type").getAsString()) {
                case "BlockAddress" -> ResourceKey.BlockAddress.class;
                case "ContainerAddress" -> ResourceKey.ContainerAddress.class;
                case "ContainerSlotAddress" -> ResourceKey.ContainerSlotAddress.class;
                case "EntityAddress" -> ResourceKey.EntityAddress.class;
                case "EntitySlotAddress" -> ResourceKey.EntitySlotAddress.class;
                case "ItemQuantityAddress" -> ResourceKey.ItemQuantityAddress.class;
                default -> throw new JsonParseException("unknown team resource address");
            };
            return context.deserialize(object.get("value"), address);
        }
    }
}
