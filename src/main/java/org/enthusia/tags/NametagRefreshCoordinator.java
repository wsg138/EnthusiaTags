package org.enthusia.tags;

import java.util.Collection;
import java.util.UUID;

final class NametagRefreshCoordinator implements AutoCloseable {
    private final NametagRefreshBridge bridge;

    NametagRefreshCoordinator(NametagRefreshBridge bridge) {
        this.bridge = bridge;
    }

    void request(UUID playerId, TagRefreshReason reason) {
        if (playerId != null) {
            bridge.refresh(playerId);
        }
    }

    void requestAll(Collection<UUID> playerIds, TagRefreshReason reason) {
        for (UUID playerId : playerIds) {
            request(playerId, reason);
        }
    }

    boolean isAvailable() {
        return bridge.isAvailable();
    }

    @Override
    public void close() {
        bridge.close();
    }
}
