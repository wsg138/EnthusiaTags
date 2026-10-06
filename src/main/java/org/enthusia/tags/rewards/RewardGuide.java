package org.enthusia.tags.rewards;

import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.enthusia.tags.advancements.domain.GoldEligibility;

/** Voluntary chat navigation only. Existing menus remain authoritative for claims. */
final class RewardGuide {
    private static final String PERMISSION = "enthusia.tags.rewards";
    private static final String CONFIG = "rewards.guide.";
    private static final List<String> PATHS = List.of("build", "social", "combat");
    private static final int MAX_PENDING = 128;
    private final JavaPlugin plugin;
    private final RewardService service;
    private final ConcurrentHashMap<UUID, Player> pending = new ConcurrentHashMap<>();

    RewardGuide(JavaPlugin plugin, RewardService service) {
        this.plugin = plugin;
        this.service = service;
    }

    boolean allowed(Player player) {
        return player.isOnline() && player.hasPermission(PERMISSION) && service.isAvailable()
            && plugin.getConfig().getBoolean(CONFIG + "enabled", false);
    }

    void open(Player player, String path) {
        if (!allowed(player)) {
            player.sendMessage(Component.text("The reward guide is unavailable. Use /rewards to browse rewards."));
            return;
        }
        if (path == null) {
            showPaths(player);
            return;
        }
        String selected = path.toLowerCase(Locale.ROOT);
        if (!PATHS.contains(selected)) {
            player.sendMessage(Component.text("Use /rewards guide build, social or combat."));
            return;
        }
        if (pending.size() >= MAX_PENDING || pending.putIfAbsent(player.getUniqueId(), player) != null) return;
        RewardDefinition target = findGoal(player, selected);
        if (target == null) {
            pending.remove(player.getUniqueId(), player);
            player.sendMessage(Component.text("No new goal is available for this path right now. Use /rewards to browse your progress."));
            return;
        }
        preview(player, target);
    }

    private RewardDefinition findGoal(Player player, String path) {
        return plugin.getConfig().getStringList(CONFIG + "paths." + path).stream().limit(16)
            .map(id -> service.getRewards().get(id.toLowerCase(Locale.ROOT)))
            .filter(java.util.Objects::nonNull).filter(goal -> service.isGuideGoalReady(player, goal))
            .findFirst().orElse(null);
    }

    private void showPaths(Player player) {
        player.sendMessage(Component.text("Choose a goal: Build and earn, Social and explore, or Fight and compete.", NamedTextColor.GOLD));
        for (String choice : PATHS) {
            String name = switch (choice) {
                case "build" -> "Build and earn";
                case "social" -> "Social and explore";
                default -> "Fight and compete";
            };
            Component label = Component.text("[" + name + "]", NamedTextColor.GREEN);
            if (findGoal(player, choice) != null) {
                label = label.clickEvent(ClickEvent.runCommand("/rewards guide " + choice));
            } else {
                label = Component.text(name + ": no new goal available right now", NamedTextColor.GRAY);
            }
            player.sendMessage(label);
        }
    }

    private void preview(Player player, RewardDefinition target) {
        long generation = service.getGuideGeneration();
        player.sendMessage(Component.text("Checking your goal...", NamedTextColor.GRAY));
        service.previewGuideGold(player, target).whenComplete((gold, failure) -> {
            try {
                plugin.getServer().getScheduler().runTask(plugin, () -> {
                    pending.remove(player.getUniqueId(), player);
                    Player current = plugin.getServer().getPlayer(player.getUniqueId());
                    if (!canRender(current, player, generation, target) || failure != null) return;
                    render(current, target, gold == null ? GoldEligibility.UNKNOWN : gold);
                });
            } catch (org.bukkit.plugin.IllegalPluginAccessException stopped) {
                pending.remove(player.getUniqueId(), player);
            }
        });
    }

    boolean canRender(Player current, Player original, long generation, RewardDefinition target) {
        return current != null && current == original && allowed(current) && generation == service.getGuideGeneration()
            && service.getRewards().get(target.getId()) == target && service.isGuideGoalReady(current, target);
    }

    private void render(Player player, RewardDefinition goal, GoldEligibility gold) {
        var legacy = LegacyComponentSerializer.legacyAmpersand();
        player.sendMessage(legacy.deserialize(goal.getName()));
        goal.getDescription().forEach(line -> player.sendMessage(legacy.deserialize(line)));
        for (RewardCriterion criterion : goal.getCriteria()) {
            player.sendMessage(Component.text(criterion.getLabel() + ": " + service.formatProgress(player, criterion), NamedTextColor.GRAY));
        }
        renderActions(player, goal, gold);
        player.sendMessage(Component.text("[Open goal]", NamedTextColor.GREEN)
            .clickEvent(ClickEvent.runCommand("/rewards open " + goal.getId())));
    }

    private void renderActions(Player player, RewardDefinition goal, GoldEligibility gold) {
        var legacy = LegacyComponentSerializer.legacyAmpersand();
        for (RewardAction action : goal.getActions()) {
            if (!action.isGoldNetworkLimited() || gold == GoldEligibility.ALLOWED) {
                player.sendMessage(legacy.deserialize(service.formatAction(action)));
            }
        }
        if (goal.getActions().stream().anyMatch(RewardAction::isGoldNetworkLimited)) {
            String text = switch (gold) {
                case ALLOWED -> "Gold eligibility is checked again when you claim. Completing a goal does not pay automatically.";
                case BLOCKED -> "Gold is unavailable for this goal on this account. Other rewards may still be available.";
                case UNKNOWN -> "Gold eligibility is unavailable right now. Check the reward screen for details.";
            };
            player.sendMessage(Component.text(text, NamedTextColor.GRAY));
        }
    }
}
