package org.enthusia.tags.api;

import java.util.UUID;

/** Runtime-only, owner-scoped suppression of a player's rendered tag. */
public interface TagVisibilityService {
    void suppress(UUID playerId, Object owner);

    void unsuppress(UUID playerId, Object owner);

    boolean isSuppressed(UUID playerId);
}
