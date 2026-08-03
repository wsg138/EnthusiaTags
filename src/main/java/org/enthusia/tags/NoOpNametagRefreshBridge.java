package org.enthusia.tags;

import java.util.UUID;

final class NoOpNametagRefreshBridge implements NametagRefreshBridge {
    static final NoOpNametagRefreshBridge INSTANCE = new NoOpNametagRefreshBridge();

    private NoOpNametagRefreshBridge() {
    }

    @Override
    public void refresh(UUID playerId) {
    }

    @Override
    public boolean isAvailable() {
        return false;
    }
}
