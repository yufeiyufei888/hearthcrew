package io.github.yufeiyufei888.hearthcrew.gametest;

import io.github.yufeiyufei888.hearthcrew.HearthCrew;
import io.github.yufeiyufei888.hearthcrew.network.UiNetwork;
import io.netty.buffer.Unpooled;
import io.netty.handler.codec.DecoderException;
import io.netty.handler.codec.EncoderException;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestHelper;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.VarInt;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

@GameTestHolder(HearthCrew.ID)
@PrefixGameTestTemplate(false)
public final class UiPayloadGameTests {
    @GameTest(template = "p0_empty", timeoutTicks = 40, batch = "hearthcrew_ui_payload")
    public static void chineseUiPayloadRoundTripUsesExactIdentities(GameTestHelper helper) {
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), helper.getLevel().registryAccess());
        try {
            var request = new UiNetwork.Request(UUID.randomUUID(), UiNetwork.Operation.COMMAND, "一起采集木头，然后建造营地");
            UiNetwork.Request.STREAM_CODEC.encode(buffer, request);
            if (!request.equals(UiNetwork.Request.STREAM_CODEC.decode(buffer)) || buffer.isReadable())
                helper.fail("UI request did not round-trip exact Chinese text and identity");
            buffer.clear();
            var snapshot = new UiNetwork.Snapshot(request.requestId(), "{\"ok\":true,\"message\":\"炉火伙伴已连接\"}");
            UiNetwork.Snapshot.STREAM_CODEC.encode(buffer, snapshot);
            if (!snapshot.equals(UiNetwork.Snapshot.STREAM_CODEC.decode(buffer)) || buffer.isReadable())
                helper.fail("UI snapshot did not round-trip exact JSON");
        } finally { buffer.release(); }
        helper.succeed();
    }

    @GameTest(template = "p0_empty", timeoutTicks = 40, batch = "hearthcrew_ui_payload")
    public static void uiRejectsMalformedUtf8UnknownOperationsAndOversizeBeforeAllocation(GameTestHelper helper) {
        var buffer = new RegistryFriendlyByteBuf(Unpooled.buffer(), helper.getLevel().registryAccess());
        try {
            buffer.writeLong(0); buffer.writeLong(0);
            VarInt.write(buffer, 2); buffer.writeByte(0xC3); buffer.writeByte(0x28);
            reject(helper, () -> UiNetwork.Snapshot.STREAM_CODEC.decode(buffer), "malformed UTF-8 accepted");
            buffer.clear(); buffer.writeLong(0); buffer.writeLong(0);
            VarInt.write(buffer, UiNetwork.MAX_SNAPSHOT_JSON_BYTES + 1);
            reject(helper, () -> UiNetwork.Snapshot.STREAM_CODEC.decode(buffer), "oversized declared length accepted");
            buffer.clear(); buffer.writeLong(0); buffer.writeLong(0);
            buffer.writeUtf("teleport", 8); buffer.writeUtf("");
            reject(helper, () -> UiNetwork.Request.STREAM_CODEC.decode(buffer), "unknown UI operation accepted");
            reject(helper, () -> new UiNetwork.Snapshot(UUID.randomUUID(), "中".repeat(21_000)), "UTF-8 bytes were counted as characters");
            reject(helper, () -> new UiNetwork.Request(UUID.randomUUID(), UiNetwork.Operation.COMMAND, "   "), "blank command accepted");
            reject(helper, () -> new UiNetwork.Request(UUID.randomUUID(), UiNetwork.Operation.STOP, "extra"), "control accepted a hidden command");
        } finally { buffer.release(); }
        helper.succeed();
    }

    private static void reject(GameTestHelper helper, Runnable operation, String message) {
        boolean rejected = false;
        try { operation.run(); }
        catch (IllegalArgumentException | DecoderException | EncoderException expected) { rejected = true; }
        if (!rejected) helper.fail(message);
    }
}
