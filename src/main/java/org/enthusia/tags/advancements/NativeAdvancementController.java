package org.enthusia.tags.advancements;

import io.github.badgersmc.advancements.pilot.ProjectionService;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;
import org.enthusia.tags.advancements.domain.PendingCelebrations;
import org.enthusia.tags.advancements.domain.RewardCelebrationOwnership;
import org.enthusia.tags.rewards.RewardAction;
import org.enthusia.tags.rewards.RewardDefinition;
import org.enthusia.tags.rewards.RewardService;

/** Main-thread, bounded display projection. No reward delivery or SQL in this adapter. */
public final class NativeAdvancementController implements Listener, AutoCloseable {
    private final JavaPlugin plugin;
    private final RewardService rewards;
    private final ProjectionService projection;
    private final CelebrationGateway celebrations;
    private Set<String> quietKeys = Set.of();
    private final Map<UUID, Set<String>> pendingCelebrations = new HashMap<>();
    private final ArrayDeque<UUID> queue = new ArrayDeque<>();
    private Map<String, RewardDefinition> definitions;
    private NativeMilestonePresentation presentation;
    private BukkitTask task;
    private final ProviderRewardTracker providers;
    private final boolean ownsProviders;
    private WarzoneAdvancementBridge duels;
    private CommendAdvancementBridge commend;
    private ExpressAdvancementBridge express;
    private DiaryAdvancementBridge diary;
    private long nextWarning;
    private boolean registered;

    public NativeAdvancementController(JavaPlugin plugin, RewardService rewards) {
        this(plugin, rewards, null);
    }

    public NativeAdvancementController(JavaPlugin plugin, RewardService rewards, ProviderRewardTracker providers) {
        this.plugin = plugin;
        this.rewards = rewards;
        projection = Bukkit.getServicesManager().load(ProjectionService.class);
        if (projection == null) throw new IllegalStateException("EnthusiaAdvancements projection service unavailable; install the pilot companion build");
        celebrations = new CelebrationGateway(projection, plugin, plugin.getLogger());
        ownsProviders = providers == null;
        this.providers = ownsProviders ? new ProviderRewardTracker(plugin, rewards) : providers;
        duels = this.providers.duels();
        commend = this.providers.commend();
        express = this.providers.express();
        diary = this.providers.diary();
        rebuild();
        Bukkit.getPluginManager().registerEvents(this, plugin);
        rewards.setAdvancementNotifications((player, completed) -> {
            Set<String> pending = pendingCelebrations.computeIfAbsent(player.getUniqueId(), ignored -> new HashSet<>());
            boolean holidaysEnabled = Bukkit.getPluginManager().isPluginEnabled("EnthusiaHolidays");
            completed.stream()
                .filter(reward -> RewardCelebrationOwnership.tagsCelebrates(reward.getId(), holidaysEnabled))
                .forEach(reward -> pending.add(key(reward.getId())));
        });
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 20L, 20L);
    }

    public static String key(String rewardId) {
        // Injective encoding, stable across config reordering and punctuation in legacy IDs.
        return "tags/" + HexFormat.of().formatHex(rewardId.toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
    }

    private void rebuild() {
        List<ProjectionService.Node> nodes = new ArrayList<>();
        Map<String, List<RewardDefinition>> categories = new LinkedHashMap<>();
        Map<String, RewardDefinition> next = rewards.getRewards();
        for (RewardDefinition reward : next.values()) if (!reward.isHiddenUntilEarned())
            categories.computeIfAbsent(reward.getCategory(), ignored -> new ArrayList<>()).add(reward);
        int row = 0;
        for (List<RewardDefinition> group : categories.values()) {
            String fallbackParentId = null;
            for (int index = 0; index < group.size(); index++) {
                RewardDefinition reward = group.get(index);
                String frame = plugin.getConfig().getString("advancements.frames." + reward.getId(), "TASK").toUpperCase(Locale.ROOT);
                if (!List.of("TASK", "GOAL", "CHALLENGE").contains(frame)) frame = "TASK";
                var placement = AdvancementLayout.placement(
                    reward.getId(), fallbackParentId, index + 1, row * 4 + 1);
                String parent = placement.parentId() == null ? null : key(placement.parentId());
                nodes.add(new ProjectionService.Node(key(reward.getId()), parent, color(reward.getName()),
                    description(reward), reward.getIcon(), null, reward.getIconModel(), frame, placement.x(), placement.y()));
                fallbackParentId = reward.getId();
            }
            row++;
        }
        if (duels != null) {
            nodes.addAll(WarzoneAdvancementBridge.nodes(AdvancementLayout.warzoneBaseY(row)));
        }
        if (commend != null) {
            nodes.addAll(CommendAdvancementBridge.nodes(
                AdvancementLayout.reputationBaseY(row, duels != null)));
        }
        if (express != null) {
            nodes.addAll(ExpressAdvancementBridge.nodes(
                AdvancementLayout.expressBaseY(row, duels != null, commend != null)));
        }
        if (diary != null) {
            int diaryIconCustomModelData = plugin.getConfig().getInt(
                "advancements.diary-icon-custom-model-data", 815002);
            nodes.addAll(DiaryAdvancementBridge.nodes(
                AdvancementLayout.diaryBaseY(
                    row, duels != null, commend != null, express != null),
                diaryIconCustomModelData,
                plugin.getConfig().getString("advancements.diary-icon-item-model", "nexo:diary_book")));
        }
        ItemStack icon = new ItemStack(Material.PAPER);
        var meta = icon.getItemMeta();
        NativeTabPresentation.applyLogo(meta,
            plugin.getConfig().getInt("advancements.logo-custom-model-data", 815001),
            plugin.getConfig().getString("advancements.logo-item-model", "enthusia:advancement/logo"));
        icon.setItemMeta(meta);
        var publicRewards = next.values().stream().filter(reward -> !reward.isHiddenUntilEarned()).toList();
        nodes = NativeTabPresentation.organize(nodes, publicRewards);
        NativeMilestonePresentation nextPresentation = NativeMilestonePresentation.create(nodes, publicRewards);
        nodes = nextPresentation.nodes();
        projection.registerTree(plugin, "enthusia", icon, AdvancementNodeOrder.parentFirst(nodes));
        presentation = nextPresentation;
        quietKeys = next.values().stream().filter(reward -> !reward.isAnnounceCompletion())
            .map(reward -> key(reward.getId())).collect(java.util.stream.Collectors.toUnmodifiableSet());
        registered = true;
        definitions = next;
        plugin.getLogger().info("Enthusia native track: " + nodes.size() + " challenges; no reward execution in renderer.");
    }

    static List<String> description(RewardDefinition reward) {
        List<String> lines = new ArrayList<>();
        reward.getDescription().forEach(line -> lines.add(color(line)));
        lines.add("§7Requirements:");
        reward.getCriteria().forEach(criterion -> lines.add("§f" + color(criterion.getLabel()) + ": " + criterion.getAmount()));
        lines.add("§7Rewards:");
        if (reward.getActions().isEmpty()) lines.add("§fNone");
        reward.getActions().forEach(action -> lines.add("§f" + actionDescription(action)));
        if (reward.getActions().stream().anyMatch(RewardAction::isGoldNetworkLimited))
            lines.add("§7Gold: one account per challenge/IP.");
        lines.add("§eClaim with /rewards. Completion is not payment.");
        return List.copyOf(lines);
    }

    private static String actionDescription(RewardAction action) {
        String label = color(action.getLabel());
        return switch (action.getType()) {
            case MONEY -> action.getAmount() + " currency (Raw Gold-backed)" + labelSuffix(label);
            case ITEM -> action.getItemAmount() + "x " + labelOr(label, String.valueOf(action.getMaterial()));
            case TAG -> "Tag: " + labelOr(label, action.getValue());
            case COMMAND -> labelOr(label, "Configured unlock (see /rewards)");
            default -> labelOr(label, "Configured item reward (see /rewards)");
        };
    }
    private static String labelOr(String label, String fallback) {
        return label.isBlank() ? fallback : label;
    }
    private static String labelSuffix(String label) {
        return label.isBlank() ? "" : " - " + label;
    }
    private static String color(String value) {
        return value == null ? "" : ChatColor.translateAlternateColorCodes('&', value);
    }

    private void tick() {
        if (!rewards.isAvailable()) return;
        try {
            if (!prepareTree()) return;
            if (queue.isEmpty()) Bukkit.getOnlinePlayers().forEach(player -> queue.add(player.getUniqueId()));
            projectQueuedPlayers();
        } catch (RuntimeException | LinkageError ex) {
            warnAndRetry(ex);
        }
    }
    private boolean prepareTree() {
        if (!plugin.getConfig().getBoolean("advancements.enabled", true)) {
            if (registered) projection.removeTree(plugin, "enthusia");
            registered = false;
            pendingCelebrations.clear();
            return false;
        }
        if (!registered || definitions != rewards.getRewards()) rebuild();
        return true;
    }
    private void projectQueuedPlayers() {
        int limit = Math.max(1, Math.min(100, plugin.getConfig().getInt("advancements.players-per-tick", 25)));
        for (int count = 0; count < limit && !queue.isEmpty(); count++) {
            projectPlayer(Bukkit.getPlayer(queue.removeFirst()));
        }
    }
    private void projectPlayer(Player player) {
        if (player == null) return;
        rewards.queueProgressRefresh(player);
        if (!projection.ready(player)) return;
        Map<String, Integer> progress = collectProgress(player);
        projection.project(plugin, "enthusia", player, progress);
        celebratePending(player, progress);
    }
    private Map<String, Integer> collectProgress(Player player) {
        Map<String, Integer> progress = new LinkedHashMap<>();
        for (RewardDefinition reward : definitions.values()) {
            if (reward.isHiddenUntilEarned()) continue;
            int value = rewards.getAdvancementProgress(player, reward);
            if (value >= 0) progress.put(key(reward.getId()), value);
        }
        UUID id = player.getUniqueId();
        progress.putAll(providers.progress(id));
        pendingCelebrations.computeIfAbsent(id, ignored -> new HashSet<>()).addAll(providers.takeCelebrations(id));
        return presentation.progress(progress);
    }
    private void celebratePending(Player player, Map<String, Integer> progress) {
        Set<String> pending = pendingCelebrations.get(player.getUniqueId());
        if (pending == null) return;
        pending.removeIf(key -> !presentation.ownsCelebration(key));
        PendingCelebrations.deliver(
            pending, progress, key -> celebrations.celebrate(player, "enthusia", key, !quietKeys.contains(key)));
        if (pending.isEmpty()) pendingCelebrations.remove(player.getUniqueId());
    }
    private void warnAndRetry(Throwable error) {
        if (System.currentTimeMillis() >= nextWarning) {
            nextWarning = System.currentTimeMillis() + 60000;
            plugin.getLogger().warning("Native advancement projection will retry: " + error.getMessage());
        }
    }
    @EventHandler public void onQuit(PlayerQuitEvent event) {
        UUID id = event.getPlayer().getUniqueId();
        pendingCelebrations.remove(id);
        queue.removeIf(id::equals);
    }
    @Override public void close() {
        if (task != null) task.cancel();
        if (ownsProviders) providers.close();
        rewards.setAdvancementNotifications(null);
        HandlerList.unregisterAll(this);
        try {
            if (registered && Bukkit.getPluginManager().isPluginEnabled("EnthusiaAdvancements"))
                projection.removeTree(plugin, "enthusia");
        } catch (RuntimeException | LinkageError ex) {
            plugin.getLogger().warning("Native advancement tree removal failed during shutdown: " + ex.getMessage());
        } finally {
            registered = false;
            pendingCelebrations.clear();
            queue.clear();
        }
    }
}
