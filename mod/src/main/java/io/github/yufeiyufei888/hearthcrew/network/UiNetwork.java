package io.github.yufeiyufei888.hearthcrew.network;

import io.netty.buffer.ByteBuf;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.EncoderException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.VarInt;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.server.level.ServerPlayer;
import net.neoforged.neoforge.network.PacketDistributor;
import net.neoforged.neoforge.network.event.RegisterPayloadHandlersEvent;
import net.neoforged.neoforge.network.handling.IPayloadContext;
import net.neoforged.neoforge.network.registration.HandlerThread;
import net.neoforged.neoforge.network.registration.PayloadRegistrar;
import io.github.yufeiyufei888.hearthcrew.HearthCrew;

/**
 * Common, bounded UI payloads. The handlers are deliberately injected by the
 * server/client integration layers so this class has no client-only references
 * and performs no controller or filesystem I/O.
 */
public final class UiNetwork {
    public static final int MAX_REQUEST_BYTES = 8 * 1024;
    public static final int MAX_COMMAND_CHARS = 512;
    public static final int MAX_SNAPSHOT_JSON_BYTES = 60 * 1024;

    private static final int MAX_REQUEST_TEXT_BYTES = 4096 * 4;
    private static volatile BiConsumer<ServerPlayer, Request> serverHandler;
    private static volatile Consumer<Snapshot> clientHandler;

    private UiNetwork() {}

    public enum Operation {
        STATUS("status"),
        JOIN("join"),
        DETAIL("detail"), HISTORY("history"),
        COMMAND("command"), TASK("task"), RETRY("retry"),
        PAUSE("pause"),
        RESUME("resume"),
        STOP("stop"),
        AUTO_ON("auto_on"),
        AUTO_OFF("auto_off"),
        STANDBY("standby");

        private final String wireName;

        Operation(String wireName) {
            this.wireName = wireName;
        }

        public String wireName() {
            return wireName;
        }

        private static Operation parse(String value) {
            for (Operation operation : values()) {
                if (operation.wireName.equals(value)) return operation;
            }
            throw new DecoderException("unknown HearthCrew UI operation");
        }
    }

    public record Request(UUID requestId, Operation operation, String message) implements CustomPacketPayload {
        public static final Type<Request> TYPE = new Type<>(HearthCrew.id("ui_request"));
        private static final StreamCodec<ByteBuf, Operation> OPERATION_CODEC =
                ByteBufCodecs.stringUtf8(8).map(Operation::parse, Operation::wireName);
        private static final StreamCodec<RegistryFriendlyByteBuf, Request> FIELDS = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, Request::requestId,
                OPERATION_CODEC, Request::operation,
                boundedUtf8(MAX_REQUEST_TEXT_BYTES, 4096), Request::message,
                Request::new);
        public static final StreamCodec<RegistryFriendlyByteBuf, Request> STREAM_CODEC =
                bounded(FIELDS, MAX_REQUEST_BYTES);

        public Request {
            Objects.requireNonNull(requestId, "requestId");
            Objects.requireNonNull(operation, "operation");
            Objects.requireNonNull(message, "message");
            if (message.length() > ((operation==Operation.TASK||operation==Operation.RETRY)?4096:MAX_COMMAND_CHARS)) {
                throw new IllegalArgumentException("UI command message exceeds 512 characters");
            }
            if ((operation == Operation.COMMAND || operation == Operation.TASK || operation == Operation.RETRY || operation == Operation.DETAIL || operation == Operation.HISTORY) ? message.isBlank() : !message.isEmpty()) {
                throw new IllegalArgumentException("only command accepts a non-empty UI message");
            }
            if (message.getBytes(StandardCharsets.UTF_8).length > MAX_REQUEST_TEXT_BYTES) {
                throw new IllegalArgumentException("UI command message exceeds UTF-8 byte limit");
            }
        }

        @Override
        public Type<Request> type() {
            return TYPE;
        }
    }

    public record Snapshot(UUID requestId, String json) implements CustomPacketPayload {
        public static final Type<Snapshot> TYPE = new Type<>(HearthCrew.id("ui_snapshot"));
        private static final StreamCodec<RegistryFriendlyByteBuf, Snapshot> FIELDS = StreamCodec.composite(
                UUIDUtil.STREAM_CODEC, Snapshot::requestId,
                boundedUtf8(MAX_SNAPSHOT_JSON_BYTES, MAX_SNAPSHOT_JSON_BYTES), Snapshot::json,
                Snapshot::new);
        public static final StreamCodec<RegistryFriendlyByteBuf, Snapshot> STREAM_CODEC =
                bounded(FIELDS, MAX_SNAPSHOT_JSON_BYTES + 64);

        public Snapshot {
            Objects.requireNonNull(requestId, "requestId");
            Objects.requireNonNull(json, "json");
            if (json.getBytes(StandardCharsets.UTF_8).length > MAX_SNAPSHOT_JSON_BYTES) {
                throw new IllegalArgumentException("UI snapshot JSON exceeds 60 KiB");
            }
        }

        @Override
        public Type<Snapshot> type() {
            return TYPE;
        }
    }

    /** Called by the mod constructor on the mod event bus. */
    public static void register(RegisterPayloadHandlersEvent event) {
        PayloadRegistrar registrar = event.registrar("1").executesOn(HandlerThread.MAIN);
        registrar.playToServer(Request.TYPE, Request.STREAM_CODEC, UiNetwork::handleServer);
        registrar.playToClient(Snapshot.TYPE, Snapshot.STREAM_CODEC, UiNetwork::handleClient);
        registrar.playToClient(Chat.TYPE, Chat.STREAM_CODEC, (packet, context) -> { if (chatHandler != null) chatHandler.accept(packet); });
    }

    /** Unsolicited public speech, independent of a UI request or open screen. */
    public record Chat(String json) implements CustomPacketPayload {
        public static final Type<Chat> TYPE = new Type<>(HearthCrew.id("public_chat"));
        public static final StreamCodec<RegistryFriendlyByteBuf, Chat> STREAM_CODEC =
                UiNetwork.<RegistryFriendlyByteBuf>boundedUtf8(8192, 4096).map(Chat::new, Chat::json);
        @Override public Type<Chat> type() { return TYPE; }
    }
    private static Consumer<Chat> chatHandler;
    public static void setChatHandler(Consumer<Chat> handler) { chatHandler = handler; }
    public static void sendChat(ServerPlayer player, String json) { PacketDistributor.sendToPlayer(player, new Chat(json)); }

    public static void setServerHandler(BiConsumer<ServerPlayer, Request> handler) {
        serverHandler = handler;
    }

    public static void setClientHandler(Consumer<Snapshot> handler) {
        clientHandler = handler;
    }

    public static void sendRequest(Request request) {
        PacketDistributor.sendToServer(request);
    }

    public static void sendSnapshot(ServerPlayer player, Snapshot snapshot) {
        PacketDistributor.sendToPlayer(player, snapshot);
    }

    private static void handleServer(Request request, IPayloadContext context) {
        if (!(context.player() instanceof ServerPlayer player)) return;
        BiConsumer<ServerPlayer, Request> handler = serverHandler;
        if (handler == null) {
            sendSnapshot(player, new Snapshot(request.requestId(),
                    "{\"ok\":false,\"error\":\"UI_SERVER_HANDLER_UNAVAILABLE\"}"));
            return;
        }
        handler.accept(player, request);
    }

    private static void handleClient(Snapshot snapshot, IPayloadContext context) {
        Consumer<Snapshot> handler = clientHandler;
        if (handler != null) handler.accept(snapshot);
    }

    private static <B extends ByteBuf> StreamCodec<B, String> boundedUtf8(int maxBytes, int maxChars) {
        return StreamCodec.of((buffer, value) -> {
            if (value == null || value.length() > maxChars) {
                throw new EncoderException("UI string exceeds character limit");
            }
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            if (bytes.length > maxBytes) {
                throw new EncoderException("UI string exceeds UTF-8 byte limit");
            }
            VarInt.write(buffer, bytes.length);
            buffer.writeBytes(bytes);
        }, buffer -> {
            int length = VarInt.read(buffer);
            if (length < 0 || length > maxBytes || length > buffer.readableBytes()) {
                throw new DecoderException("UI string exceeds encoded bounds");
            }
            byte[] bytes = new byte[length];
            buffer.readBytes(bytes);
            String value = new String(bytes, StandardCharsets.UTF_8);
            if (value.length() > maxChars
                    || !Arrays.equals(bytes, value.getBytes(StandardCharsets.UTF_8))) {
                throw new DecoderException("invalid or oversized UI UTF-8 string");
            }
            return value;
        });
    }

    private static <B extends ByteBuf, T> StreamCodec<B, T> bounded(StreamCodec<B, T> delegate, int maxBytes) {
        return StreamCodec.of((buffer, value) -> {
            int start = buffer.writerIndex();
            delegate.encode(buffer, value);
            int written = buffer.writerIndex() - start;
            if (written > maxBytes) {
                buffer.writerIndex(start);
                throw new EncoderException("UI payload exceeds encoded bounds");
            }
        }, buffer -> {
            int start = buffer.readerIndex();
            T value = delegate.decode(buffer);
            if (buffer.readerIndex() - start > maxBytes) {
                throw new DecoderException("UI payload exceeds encoded bounds");
            }
            return value;
        });
    }
}
