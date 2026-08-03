package org.enthusia.tags;

import java.util.UUID;

interface NametagRefreshBridge extends AutoCloseable {
    void refresh(UUID playerId);

    boolean isAvailable();

    @Override
    default void close() {
    }
}
