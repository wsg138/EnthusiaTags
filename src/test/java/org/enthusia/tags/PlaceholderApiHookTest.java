package org.enthusia.tags;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class PlaceholderApiHookTest {
    @Test
    void evaluatesExternalPlaceholdersOnceAndSkipsOwnExpansion() {
        AtomicInteger calls = new AtomicInteger();
        PlaceholderApiHook hook = hook(calls);

        String result = hook.apply(null,
            "%rank% / %nested% / %enthusiatags_selected_mm%", true);

        assertEquals(2, calls.get());
        assertTrue(result.contains("<green>"));
        assertTrue(result.contains("\\<red>Player"));
        assertTrue(result.contains("%rank%"), "nested values must not be recursively expanded");
        assertFalse(result.contains("enthusiatags_selected_mm"));
    }

    @Test
    void leavesExternalTokensForTheConsumerWhenRequestedOffThread() {
        AtomicInteger calls = new AtomicInteger();
        PlaceholderApiHook hook = hook(calls);

        String result = hook.apply(null, "%rank%", false);

        assertEquals("%rank%", result);
        assertEquals(0, calls.get());
    }

    private static PlaceholderApiHook hook(AtomicInteger calls) {
        return new PlaceholderApiHook((player, token) -> {
            calls.incrementAndGet();
            return switch (token) {
                case "%rank%" -> "&a<red>Player";
                case "%nested%" -> "%rank%";
                default -> token;
            };
        });
    }
}
