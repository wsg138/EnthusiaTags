package org.enthusia.tags;

import java.util.UUID;
import java.util.function.Consumer;

final class FailSafeNametagRefreshBridge implements NametagRefreshBridge {
    private final NametagRefreshBridge delegate;
    private final Consumer<String> warningLogger;
    private boolean available = true;

    FailSafeNametagRefreshBridge(NametagRefreshBridge delegate, Consumer<String> warningLogger) {
        this.delegate = delegate;
        this.warningLogger = warningLogger;
    }

    @Override
    public void refresh(UUID playerId) {
        if (!available) {
            return;
        }
        try {
            delegate.refresh(playerId);
        } catch (LinkageError | RuntimeException ex) {
            available = false;
            warningLogger.accept(NametagRefreshBridgeFactory.unavailableMessage(ex));
        }
    }

    @Override
    public boolean isAvailable() {
        return available && delegate.isAvailable();
    }
}
