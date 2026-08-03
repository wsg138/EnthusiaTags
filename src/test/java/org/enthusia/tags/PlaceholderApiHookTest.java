package org.enthusia.tags;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class PlaceholderApiHookTest {
    @Test
    void evaluatesExternalPlaceholdersOnceAndSkipsOwnExpansion() {
        AtomicInteger calls = new AtomicInteger();
        PlaceholderApiHook hook = new PlaceholderApiHook((player, token) -> {
            calls.incrementAndGet();
            return switch (token) {
                case "%rank%" -> "&a<red>Player";
                case "%nested%" -> "%rank%";
                default -> token;
            };
        });

        String result = hook.apply(null, "%rank% / %nested% / %enthusiatags_selected_mm%");

        assertEquals(2, calls.get());
        assertTrue(result.contains("<green>"));
        assertTrue(result.contains("\\<red>Player"));
        assertTrue(result.contains("%rank%"), "nested values must not be recursively expanded");
        assertFalse(result.contains("enthusiatags_selected_mm"));
    }
}
