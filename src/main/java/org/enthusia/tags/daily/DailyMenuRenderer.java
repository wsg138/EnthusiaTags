package org.enthusia.tags.daily;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.plugin.java.JavaPlugin;

final class DailyMenuRenderer {
    static final int[] DAY_SLOTS = {10, 11, 12, 13, 14, 15, 16};
    static final Component TITLE = Component.text("Daily Rewards", NamedTextColor.GOLD);
    private static final String CURRENCY_LABEL_PATH = "daily.currency-label";

    private final JavaPlugin plugin;

    DailyMenuRenderer(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    Inventory createMenu(DailyMenuModel.View view) {
        int claimSlot = view.claimIndex() < 0 ? -1 : DAY_SLOTS[view.claimIndex()];
        DailyInventoryHolder holder = DailyInventoryHolder.menu(claimSlot);
        Inventory inventory = Bukkit.createInventory(holder, 27, TITLE);
        holder.attach(inventory);

        inventory.setItem(3, statItem(Material.CLOCK, "Current Streak", view.currentStreak(),
            NamedTextColor.AQUA));
        inventory.setItem(5, statItem(Material.BEACON, "Best Streak", view.bestStreak(),
            NamedTextColor.GOLD));
        for (int index = 0; index < view.days().size(); index++) {
            inventory.setItem(DAY_SLOTS[index], dayItem(view.days().get(index)));
        }
        return inventory;
    }

    ItemStack statItem(Material material, String label, int value, NamedTextColor color) {
        String duration = value == 1 ? "1 day" : value + " days";
        return item(material, Component.text(label, color),
            List.of(Component.text(duration, NamedTextColor.WHITE)), false);
    }

    ItemStack dayItem(DailyMenuModel.Day day) {
        DailyMenuModel.Status status = day.status();
        List<Component> lore = new ArrayList<>(3);
        lore.add(Component.text("Amount: ", NamedTextColor.GRAY)
            .append(Component.text(formatAmount(day.amount()) + " " + currencyLabel(), NamedTextColor.GOLD)));
        if (day.rolling()) {
            lore.add(Component.text("Day 7+ streak reward", NamedTextColor.LIGHT_PURPLE));
        }
        lore.add(Component.text(statusText(status), statusColor(status)));

        Material material = day.rolling() ? Material.NETHER_STAR : statusMaterial(status);
        return item(material, Component.text("Day " + day.number(), statusColor(status)),
            lore, status.claimable());
    }

    ItemStack decorativeItem(Material material, String name, NamedTextColor color, boolean glowing) {
        return item(material, Component.text(name, color), List.of(), glowing);
    }

    ItemStack blankPane(Material material) {
        return item(material, Component.text(" "), List.of(), false);
    }

    String rewardText(double amount) {
        return formatAmount(amount) + " " + currencyLabel();
    }

    String currencyLabel() {
        String configured = plugin.getConfig().getString(CURRENCY_LABEL_PATH, "Raw Gold");
        return configured == null || configured.isBlank() ? "Raw Gold" : configured.trim();
    }

    private Material statusMaterial(DailyMenuModel.Status status) {
        return switch (status) {
            case CLAIMED -> Material.EMERALD;
            case CLAIMABLE -> Material.GOLD_INGOT;
            case UPCOMING -> Material.GRAY_DYE;
            case RETRY -> Material.YELLOW_DYE;
            case PROCESSING -> Material.CLOCK;
            case RECONCILIATION -> Material.REDSTONE;
        };
    }

    private NamedTextColor statusColor(DailyMenuModel.Status status) {
        return switch (status) {
            case CLAIMED -> NamedTextColor.GREEN;
            case CLAIMABLE -> NamedTextColor.GOLD;
            case UPCOMING -> NamedTextColor.GRAY;
            case RETRY, PROCESSING -> NamedTextColor.YELLOW;
            case RECONCILIATION -> NamedTextColor.RED;
        };
    }

    private String statusText(DailyMenuModel.Status status) {
        return switch (status) {
            case CLAIMED -> "Claimed";
            case CLAIMABLE -> "Available now — click to claim";
            case UPCOMING -> "Upcoming";
            case RETRY -> "Previous deposit failed — click to retry";
            case PROCESSING -> "Claim is still being processed";
            case RECONCILIATION -> "Claim requires staff reconciliation";
        };
    }

    private ItemStack item(Material material, Component name, List<Component> lore, boolean glowing) {
        ItemStack stack = new ItemStack(material);
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return stack;
        }
        meta.displayName(name);
        if (!lore.isEmpty()) {
            meta.lore(lore);
        }
        if (glowing) {
            meta.addEnchant(Enchantment.UNBREAKING, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        }
        stack.setItemMeta(meta);
        return stack;
    }

    private String formatAmount(double amount) {
        return BigDecimal.valueOf(amount).stripTrailingZeros().toPlainString();
    }
}
