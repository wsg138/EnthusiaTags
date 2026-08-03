package org.enthusia.tags;

import java.util.UUID;

interface NametagRefreshBridge {
    void refresh(UUID playerId);

    boolean isAvailable();
}
