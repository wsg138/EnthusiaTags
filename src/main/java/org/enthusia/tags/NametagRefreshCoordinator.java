package org.enthusia.tags;

import java.util.Objects;
import java.util.UUID;

final class NametagRefreshCoordinator implements AutoCloseable {
    private final NametagRefreshBridge bridge;
    private boolean closed;

    NametagRefreshCoordinator(NametagRefreshBridge bridge) {
        this.bridge = bridge;
    }

    void request(UUID playerId, TagRefreshReason reason) {
        Objects.requireNonNull(reason, "reason");
        if (!closed && playerId != null) {
            bridge.refresh(playerId);
        }
    }

    @Override
    public void close() {
        closed = true;
    }
}
