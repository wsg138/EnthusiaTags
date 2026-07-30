package org.enthusia.tags.daily;

import java.util.Objects;
import java.util.UUID;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

final class DailyInventoryHolder implements InventoryHolder {
    enum Mode { MENU, ANIMATION }

    private final Mode mode;
    private final int claimSlot;
    private final UUID sessionId;
    private Inventory inventory;

    private DailyInventoryHolder(Mode mode, int claimSlot, UUID sessionId) {
        this.mode = Objects.requireNonNull(mode, "mode");
        this.claimSlot = claimSlot;
        this.sessionId = sessionId;
    }

    static DailyInventoryHolder menu(int claimSlot) {
        return new DailyInventoryHolder(Mode.MENU, claimSlot, null);
    }

    static DailyInventoryHolder animation(UUID sessionId) {
        return new DailyInventoryHolder(Mode.ANIMATION, -1,
            Objects.requireNonNull(sessionId, "sessionId"));
    }

    void attach(Inventory inventory) {
        if (this.inventory != null) {
            throw new IllegalStateException("Daily inventory holder is already attached");
        }
        this.inventory = Objects.requireNonNull(inventory, "inventory");
    }

    boolean isAnimation() {
        return mode == Mode.ANIMATION;
    }

    int claimSlot() {
        return claimSlot;
    }

    UUID sessionId() {
        return sessionId;
    }

    @Override
    public Inventory getInventory() {
        return Objects.requireNonNull(inventory, "Daily inventory holder is not attached");
    }
}
