package org.enthusia.tags.daily;

import java.util.Objects;
import java.util.UUID;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

final class DailyInventoryHolder implements InventoryHolder {
    private static final UUID MENU_SESSION = new UUID(0L, 0L);

    private final boolean animation;
    private final int claimSlot;
    private final UUID sessionId;
    private final Inventory inventory;

    private DailyInventoryHolder(boolean animation, int claimSlot, UUID sessionId,
                                 int size, Component title) {
        this.animation = animation;
        this.claimSlot = claimSlot;
        this.sessionId = Objects.requireNonNull(sessionId, "sessionId");
        inventory = Bukkit.createInventory(this, size, Objects.requireNonNull(title, "title"));
    }

    static DailyInventoryHolder menu(int claimSlot, Component title) {
        return new DailyInventoryHolder(false, claimSlot, MENU_SESSION, 27, title);
    }

    static DailyInventoryHolder animation(UUID sessionId, Component title) {
        return new DailyInventoryHolder(true, -1, sessionId, 45, title);
    }

    boolean isAnimation() {
        return animation;
    }

    int claimSlot() {
        return claimSlot;
    }

    UUID sessionId() {
        return sessionId;
    }

    @Override
    public Inventory getInventory() {
        return inventory;
    }
}
