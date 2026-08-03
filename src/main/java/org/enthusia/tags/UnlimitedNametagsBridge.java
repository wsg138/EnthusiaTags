package org.enthusia.tags;

import org.alexdev.unlimitednametags.api.UNTPaperAPI;

import java.util.UUID;

final class UnlimitedNametagsBridge implements NametagRefreshBridge {
    private final UNTPaperAPI api;

    UnlimitedNametagsBridge() {
        this.api = UNTPaperAPI.getInstance();
    }

    @Override
    public void refresh(UUID playerId) {
        api.forceRefresh(playerId, true);
    }

    @Override
    public boolean isAvailable() {
        return true;
    }
}
