package io.github.yufeiyufei888.hearthcrew.gametest;

import io.github.yufeiyufei888.hearthcrew.HearthCrew;
import io.github.yufeiyufei888.hearthcrew.network.UiNetwork;
import io.github.yufeiyufei888.hearthcrew.runtime.GameUiService;
import java.util.Map;
import java.util.UUID;
import net.minecraft.gametest.framework.GameTest;
import net.minecraft.gametest.framework.GameTestAssertException;
import net.minecraft.gametest.framework.GameTestHelper;
import net.neoforged.neoforge.gametest.GameTestHolder;
import net.neoforged.neoforge.gametest.PrefixGameTestTemplate;

/**
 * Server-side UI bookkeeping gates. These tests intentionally do not claim a
 * real singleplayer client/controller round trip; that belongs to the later
 * payload and production interop gates.
 */
@GameTestHolder(HearthCrew.ID)
@PrefixGameTestTemplate(false)
public final class P2UiGameTests {
    private P2UiGameTests() {}

    @GameTest(template = "p0_empty", timeoutTicks = 40, batch = "hearthcrew_p2_ui")
    public static void unknownClaimAndCompletionAreStale(GameTestHelper helper) {
        GameUiService service = new GameUiService(helper.getLevel().getServer(), () -> 17L,
                () -> true, ignored -> true);
        UUID requestId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        UUID uiSession = UUID.randomUUID();
        try {
            Map<String, Object> claim = service.claim(requestId, ownerId, uiSession, 17L);
            if (!"STALE".equals(claim.get("decision"))) {
                throw new GameTestAssertException("unknown UI claim was not rejected as STALE: " + claim);
            }
            Map<String, Object> completion = service.complete(requestId, ownerId, uiSession, 17L,
                    "{\"ok\":true}");
            if (!"STALE".equals(completion.get("decision"))) {
                throw new GameTestAssertException("unknown UI completion was not rejected as STALE: " + completion);
            }
        } finally {
            service.close();
        }
        helper.succeedWhen(() -> {});
    }

    @GameTest(template = "p0_empty", timeoutTicks = 40, batch = "hearthcrew_p2_ui")
    public static void closedServiceRejectsPayloadsWithoutWorldEffects(GameTestHelper helper) {
        GameUiService service = new GameUiService(helper.getLevel().getServer(), () -> 18L,
                () -> true, ignored -> true);
        service.close();
        Map<String, Object> result = service.request(null,
                new UiNetwork.Request(UUID.randomUUID(), UiNetwork.Operation.STATUS, ""));
        if (!"CLOSED".equals(result.get("decision"))) {
            throw new GameTestAssertException("closed UI service accepted a payload: " + result);
        }
        helper.succeedWhen(() -> {});
    }
}
