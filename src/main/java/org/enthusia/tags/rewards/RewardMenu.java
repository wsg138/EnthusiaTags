package org.enthusia.tags.rewards;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.enthusia.tags.TagService;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public final class RewardMenu {
    private final RewardService rewardService;
    private final TagService tagService;
    private final NamespacedKey rewardKey;
    private final NamespacedKey categoryKey;
    private final NamespacedKey backKey;
    private final NamespacedKey nextKey;
    private final NamespacedKey prevKey;

    public RewardMenu(RewardService rewardService, TagService tagService) {
        this.rewardService = rewardService;
        this.tagService = tagService;
        this.rewardKey = new NamespacedKey(tagService.getPlugin(), "reward_id");
        this.categoryKey = new NamespacedKey(tagService.getPlugin(), "reward_category");
        this.backKey = new NamespacedKey(tagService.getPlugin(), "reward_back");
        this.nextKey = new NamespacedKey(tagService.getPlugin(), "reward_next");
        this.prevKey = new NamespacedKey(tagService.getPlugin(), "reward_prev");
    }

    public Inventory create(Player player) {
        RewardMenuHolder holder = new RewardMenuHolder(rewardService);
        Component title = LegacyComponentSerializer.legacyAmpersand()
            .deserialize(rewardService.getMessage("rewards-gui-title"));
        Inventory inventory = Bukkit.createInventory(holder, 27, title);
        holder.setInventory(inventory);

        int slot = 10;
        Map<String, RewardCategory> categories = rewardService.getConfig().categories();
        for (RewardCategory category : categories.values()) {
            if (slot >= 17) {
                break;
            }
            inventory.setItem(slot++, createCategoryItem(category));
        }
        return inventory;
    }

    public Inventory createCategory(Player player, String categoryId) {
        return createCategory(player, categoryId, 0);
    }

    public Inventory createFocused(Player player, RewardDefinition target) {
        int index = 0;
        for (RewardDefinition reward : rewardService.getRewards().values()) {
            if (!reward.getCategory().equalsIgnoreCase(target.getCategory())) continue;
            if (reward.getId().equalsIgnoreCase(target.getId())) {
                return createCategory(player, target.getCategory(), index / 45, target.getId());
            }
            index++;
        }
        return create(player);
    }

    public Inventory createCategory(Player player, String categoryId, int page) {
        return createCategory(player, categoryId, page, null);
    }

    private Inventory createCategory(Player player, String categoryId, int page, String focusedRewardId) {
        RewardMenuHolder holder = new RewardMenuHolder(rewardService, categoryId, page);
        RewardCategory category = rewardService.getConfig().categories().get(categoryId);
        String titleText = category == null
            ? rewardService.getMessage("rewards-gui-title")
            : rewardService.getMessage("rewards-category-title").replace("{category}", category.name());
        Component title = LegacyComponentSerializer.legacyAmpersand().deserialize(titleText);
        Inventory inventory = Bukkit.createInventory(holder, 54, title);
        holder.setInventory(inventory);

        List<RewardDefinition> list = new ArrayList<>();
        for (RewardDefinition reward : rewardService.getRewards().values()) {
            if (!reward.getCategory().equalsIgnoreCase(categoryId)) {
                continue;
            }
            list.add(reward);
        }
        int pageSize = 45;
        int start = Math.max(0, page) * pageSize;
        int end = Math.min(list.size(), start + pageSize);
        int slot = 0;
        long renderStart = System.nanoTime();
        RewardService.ProgressSnapshot snapshot = rewardService.getProgressSnapshot(player);
        for (int i = start; i < end; i++) {
            inventory.setItem(slot++, createRewardItem(player, list.get(i), snapshot,
                list.get(i).getId().equalsIgnoreCase(focusedRewardId == null ? "" : focusedRewardId)));
        }
        if (tagService.getPlugin() instanceof org.enthusia.tags.EnthusiaTagsPlugin plugin) {
            plugin.getPerformanceMonitor().add("rewards.gui.items-rendered", end - start);
            plugin.getPerformanceMonitor().recordDurationMillis("rewards.gui.render",
                java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - renderStart));
        }
        inventory.setItem(45, createPrevItem());
        inventory.setItem(49, createBackItem());
        inventory.setItem(53, createNextItem());
        return inventory;
    }

    public NamespacedKey getRewardKey() {
        return rewardKey;
    }

    public NamespacedKey getCategoryKey() {
        return categoryKey;
    }

    public NamespacedKey getBackKey() {
        return backKey;
    }

    public NamespacedKey getNextKey() {
        return nextKey;
    }

    public NamespacedKey getPrevKey() {
        return prevKey;
    }

    private ItemStack createRewardItem(Player player, RewardDefinition reward, RewardService.ProgressSnapshot snapshot,
                                       boolean focused) {
        ItemStack stack = new ItemStack(reward.getIcon());
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(LegacyComponentSerializer.legacyAmpersand().deserialize(reward.getName()));

        List<Component> lore = new ArrayList<>();
        for (String line : reward.getDescription()) {
            lore.add(LegacyComponentSerializer.legacyAmpersand().deserialize(line));
        }

        RewardEvaluation evaluation = rewardService.evaluate(player, reward, snapshot);
        boolean claimed = evaluation.status() == RewardStatus.CLAIMED;
        boolean complete = evaluation.claimable();
        if (focused) {
            lore.add(LegacyComponentSerializer.legacyAmpersand()
                .deserialize(rewardService.getMessage("rewards-status-focused")));
            meta.addEnchant(Enchantment.UNBREAKING, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        }
        if (claimed) {
            lore.add(LegacyComponentSerializer.legacyAmpersand()
                .deserialize(rewardService.getMessage("rewards-status-claimed")));
        } else if (evaluation.status() == RewardStatus.ITEM_QUEUED) {
            lore.add(LegacyComponentSerializer.legacyAmpersand()
                .deserialize(rewardService.getMessage("rewards-status-queued")));
        } else if (evaluation.status() == RewardStatus.REQUIRES_RECONCILIATION) {
            lore.add(LegacyComponentSerializer.legacyAmpersand()
                .deserialize(rewardService.getMessage("rewards-status-reconciliation")));
        } else if (evaluation.status() == RewardStatus.DELIVERY_FAILED) {
            lore.add(LegacyComponentSerializer.legacyAmpersand()
                .deserialize(rewardService.getMessage("rewards-status-retryable")));
        } else if (evaluation.status() == RewardStatus.CLAIM_PENDING) {
            lore.add(LegacyComponentSerializer.legacyAmpersand()
                .deserialize(rewardService.getMessage("rewards-status-pending")));
        } else if (complete) {
            lore.add(LegacyComponentSerializer.legacyAmpersand()
                .deserialize(rewardService.getMessage("rewards-status-claimable")));
            meta.addEnchant(Enchantment.UNBREAKING, 1, true);
            meta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
        } else {
            lore.add(LegacyComponentSerializer.legacyAmpersand()
                .deserialize(rewardService.getMessage("rewards-status-locked")));
        }

        lore.add(LegacyComponentSerializer.legacyAmpersand()
            .deserialize(rewardService.getMessage("rewards-progress-title")));
        for (RewardCriterion criterion : reward.getCriteria()) {
            long progress = rewardService.getProgress(player, criterion, snapshot);
            boolean done = progress >= criterion.getAmount();
            String line = rewardService.getMessage("rewards-progress-line")
                .replace("{label}", criterion.getLabel())
                .replace("{color}", done ? "&a" : "&c")
                .replace("{progress}", rewardService.formatProgress(progress, criterion));
            lore.add(LegacyComponentSerializer.legacyAmpersand().deserialize(line));
        }

        if (!reward.getActions().isEmpty()) {
            lore.add(LegacyComponentSerializer.legacyAmpersand()
                .deserialize(rewardService.getMessage("rewards-rewards-title")));
            for (RewardAction action : reward.getActions()) {
                lore.add(LegacyComponentSerializer.legacyAmpersand()
                    .deserialize(formatActionLine(action)));
            }
        }

        meta.lore(lore);
        meta.getPersistentDataContainer().set(rewardKey, PersistentDataType.STRING, reward.getId().toLowerCase(Locale.ROOT));
        stack.setItemMeta(meta);
        return stack;
    }

    private String formatActionLine(RewardAction action) {
        return switch (action.getType()) {
            case TAG -> {
                String tagName = action.getValue();
                var tag = tagService.getRegistry().get(action.getValue());
                if (tag != null) {
                    tagName = tag.getDisplayName();
                }
                yield rewardService.getMessage("rewards-rewards-line-tag")
                    .replace("{tag}", tagName);
            }
            case MONEY -> rewardService.getMessage("rewards-rewards-line-money")
                .replace("{amount}", formatAmount(action.getAmount()));
            case ITEM -> rewardService.getMessage("rewards-rewards-line-item")
                .replace("{amount}", String.valueOf(action.getItemAmount()))
                .replace("{item}", itemDisplayName(action));
            case COMMAND -> rewardService.getMessage("rewards-rewards-line-unlock")
                .replace("{unlock}", actionLabel(action, "rewards-rewards-unlock-default"));
        };
    }

    private String itemDisplayName(RewardAction action) {
        if (action.getDisplayName() != null && !action.getDisplayName().isBlank()) {
            return action.getDisplayName();
        }
        if (action.getMaterial() == null) {
            return rewardService.getMessage("rewards-rewards-item-default");
        }
        return titleCase(action.getMaterial().name());
    }

    private String actionLabel(RewardAction action, String fallbackKey) {
        if (action.getLabel() != null && !action.getLabel().isBlank()) {
            return action.getLabel();
        }
        return rewardService.getMessage(fallbackKey);
    }

    private String formatAmount(double amount) {
        return BigDecimal.valueOf(amount).stripTrailingZeros().toPlainString();
    }

    private String titleCase(String value) {
        String[] words = value.toLowerCase(Locale.ROOT).split("_");
        StringBuilder result = new StringBuilder();
        for (String word : words) {
            if (word.isBlank()) {
                continue;
            }
            if (result.length() > 0) {
                result.append(' ');
            }
            result.append(Character.toUpperCase(word.charAt(0))).append(word.substring(1));
        }
        return result.toString();
    }

    private ItemStack createCategoryItem(RewardCategory category) {
        ItemStack stack = new ItemStack(category.icon() == null ? org.bukkit.Material.PAPER : category.icon());
        ItemMeta meta = stack.getItemMeta();
        String name = rewardService.getMessage("rewards-category-title")
            .replace("{category}", category.name());
        meta.displayName(LegacyComponentSerializer.legacyAmpersand().deserialize(name));
        meta.getPersistentDataContainer().set(categoryKey, PersistentDataType.STRING, category.id());
        stack.setItemMeta(meta);
        return stack;
    }

    private ItemStack createBackItem() {
        ItemStack stack = new ItemStack(org.bukkit.Material.BARRIER);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(LegacyComponentSerializer.legacyAmpersand()
            .deserialize(rewardService.getMessage("rewards-back")));
        meta.getPersistentDataContainer().set(backKey, PersistentDataType.BYTE, (byte) 1);
        stack.setItemMeta(meta);
        return stack;
    }

    private ItemStack createNextItem() {
        ItemStack stack = new ItemStack(org.bukkit.Material.ARROW);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(LegacyComponentSerializer.legacyAmpersand()
            .deserialize(rewardService.getMessage("rewards-next")));
        meta.getPersistentDataContainer().set(nextKey, PersistentDataType.BYTE, (byte) 1);
        stack.setItemMeta(meta);
        return stack;
    }

    private ItemStack createPrevItem() {
        ItemStack stack = new ItemStack(org.bukkit.Material.ARROW);
        ItemMeta meta = stack.getItemMeta();
        meta.displayName(LegacyComponentSerializer.legacyAmpersand()
            .deserialize(rewardService.getMessage("rewards-prev")));
        meta.getPersistentDataContainer().set(prevKey, PersistentDataType.BYTE, (byte) 1);
        stack.setItemMeta(meta);
        return stack;
    }
}
