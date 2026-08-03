package org.enthusia.tags;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class NametagRefreshIntegrationTest {
    @Test
    void optionalUnlimitedNametagsAbsenceUsesNoOpAndWarnsOnce() {
        List<String> warnings = new ArrayList<>();
        NametagRefreshBridge bridge = NametagRefreshBridgeFactory.create(
            false,
            () -> fail("bridge must not be created when dependency is absent"),
            warnings::add
        );

        assertFalse(bridge.isAvailable());
        bridge.refresh(UUID.randomUUID());
        assertEquals(List.of(NametagRefreshBridgeFactory.UNAVAILABLE_WARNING), warnings);
    }

    @Test
    void incompatibleUnlimitedNametagsInitializationUsesNoOpAndWarnsOnce() {
        List<String> warnings = new ArrayList<>();
        NametagRefreshBridge bridge = NametagRefreshBridgeFactory.create(
            true,
            () -> { throw new NoClassDefFoundError("incompatible api"); },
            warnings::add
        );

        assertFalse(bridge.isAvailable());
        assertEquals(1, warnings.size());
        assertTrue(warnings.getFirst().startsWith(NametagRefreshBridgeFactory.UNAVAILABLE_WARNING));
    }

    @Test
    void refreshTimeLinkageFailureDisablesBridgeAndWarnsOnce() {
        List<String> warnings = new ArrayList<>();
        AtomicInteger attempts = new AtomicInteger();
        NametagRefreshBridge bridge = NametagRefreshBridgeFactory.create(
            true,
            () -> new NametagRefreshBridge() {
                @Override
                public void refresh(UUID playerId) {
                    attempts.incrementAndGet();
                    throw new NoSuchMethodError("forceRefresh");
                }

                @Override
                public boolean isAvailable() {
                    return true;
                }
            },
            warnings::add
        );

        UUID playerId = UUID.randomUUID();
        bridge.refresh(playerId);
        bridge.refresh(playerId);

        assertFalse(bridge.isAvailable());
        assertEquals(1, attempts.get());
        assertEquals(1, warnings.size());
        assertTrue(warnings.getFirst().contains("NoSuchMethodError"));
    }

    @Test
    void everyMutationReasonRequestsRefreshAndShutdownStopsRefreshes() {
        RecordingBridge bridge = new RecordingBridge();
        NametagRefreshCoordinator coordinator = new NametagRefreshCoordinator(bridge);
        UUID playerId = UUID.randomUUID();

        for (TagRefreshReason reason : TagRefreshReason.values()) {
            coordinator.request(playerId, reason);
        }
        coordinator.close();
        coordinator.request(playerId, TagRefreshReason.SELECTED);

        assertEquals(TagRefreshReason.values().length, bridge.refreshes.size(),
            "closed coordinators must not emit additional refreshes");
    }

    private static final class RecordingBridge implements NametagRefreshBridge {
        private final List<UUID> refreshes = new ArrayList<>();

        @Override
        public void refresh(UUID playerId) {
            refreshes.add(playerId);
        }

        @Override
        public boolean isAvailable() {
            return true;
        }
    }
}
