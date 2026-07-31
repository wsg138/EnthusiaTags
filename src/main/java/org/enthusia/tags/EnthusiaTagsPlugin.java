package org.enthusia.tags;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.enthusia.tags.api.TagVisibilityService;
import org.enthusia.tags.cosmetics.CosmeticsCommand;
import org.enthusia.tags.cosmetics.CosmeticsListener;
import org.enthusia.tags.cosmetics.CosmeticsService;
import org.enthusia.tags.rewards.NaturalBlockTracker;
import org.enthusia.tags.rewards.RewardListener;
import org.enthusia.tags.rewards.RewardService;
import org.enthusia.tags.rewards.RewardTracker;
import org.enthusia.tags.rewards.RewardsCommand;
import org.enthusia.tags.daily.DailyService;

public final class EnthusiaTagsPlugin extends JavaPlugin {
    private TagService tagService;
    private Messages messages;
    private RewardService rewardService;
    private RewardTracker rewardTracker;
    private NaturalBlockTracker naturalBlockTracker;
    private CosmeticsService cosmeticsService;
    private PerformanceMonitor performanceMonitor;
    private ConfigMigrator configMigrator;
    private DailyService dailyService;

    @Override
    public void onEnable() {
        performanceMonitor = new PerformanceMonitor(this);
        configMigrator = new ConfigMigrator(this);
        ConfigMigrator.MigrationReport migrationReport = configMigrator.migrateAll();
        messages = new Messages(this);
        messages.reload();
        reloadConfig();
        performanceMonitor.reload();

        tagService = new TagService(this, messages, performanceMonitor);
        cosmeticsService = new CosmeticsService(this, messages, performanceMonitor);
        rewardService = new RewardService(this, tagService, messages, performanceMonitor);
        dailyService = new DailyService(this);

        tagService.enable();
        cosmeticsService.enable();
        rewardService.enable();
        if (rewardService.isAvailable()) {
            rewardTracker = new RewardTracker(this, rewardService);
            rewardTracker.start(this);
            try {
                naturalBlockTracker = new NaturalBlockTracker(this, rewardService);
                rewardService.setNaturalBlockTrackingAvailable(true);
            } catch (java.sql.SQLException ex) {
                rewardService.setNaturalBlockTrackingAvailable(false);
                getLogger().severe("Natural ore rewards are locked because origin storage failed: "
                    + ex.getMessage());
            }
        }
        try {
            dailyService.enable();
        } catch (java.sql.SQLException ex) {
            getLogger().severe("Failed to enable daily rewards: " + ex.getMessage());
            dailyService = null;
        }

        Bukkit.getServicesManager().register(TagService.class, tagService, this, ServicePriority.Normal);
        Bukkit.getServicesManager().register(TagVisibilityService.class, tagService, this, ServicePriority.Normal);
        registerListeners();
        registerCommands();
        migrationReport.summaryLines().forEach(line -> getLogger().info("Startup summary: " + line));
    }

    @Override
    public void onDisable() {
        Bukkit.getServicesManager().unregister(tagService);
        if (rewardTracker != null) {
            rewardTracker.stop();
        }
        if (naturalBlockTracker != null) {
            try {
                naturalBlockTracker.close();
            } catch (java.sql.SQLException ex) {
                getLogger().warning("Failed to close natural ore tracking: " + ex.getMessage());
            }
        }
        if (dailyService != null) dailyService.disable();
        if (rewardService != null) {
            rewardService.disable();
        }
        if (cosmeticsService != null) {
            cosmeticsService.disable();
        }
        if (tagService != null) {
            tagService.disable();
        }
        if (performanceMonitor != null) {
            performanceMonitor.stop();
        }
    }

    public TagService getTagService() {
        return tagService;
    }

    public Messages getMessages() {
        return messages;
    }

    public RewardService getRewardService() {
        return rewardService;
    }

    public CosmeticsService getCosmeticsService() {
        return cosmeticsService;
    }

    public PerformanceMonitor getPerformanceMonitor() {
        return performanceMonitor;
    }

    public void reloadAllFiles() {
        ConfigMigrator.MigrationReport migrationReport = configMigrator.migrateAll();
        reloadConfig();
        performanceMonitor.reload();
        messages.reload();
        tagService.reloadAll();
        rewardService.reload();
        if (dailyService != null) {
            dailyService.reload();
        }
        cosmeticsService.reload();
        migrationReport.summaryLines().forEach(line -> getLogger().info("Reload summary: " + line));
    }

    private void registerListeners() {
        Bukkit.getPluginManager().registerEvents(new TagListener(tagService, rewardService), this);
        Bukkit.getPluginManager().registerEvents(new CosmeticsListener(cosmeticsService, tagService, messages, rewardService), this);
        RewardsCommand rewardsCommand = new RewardsCommand(rewardService, tagService, messages, this);
        if (rewardService.isAvailable()) {
            Bukkit.getPluginManager().registerEvents(new RewardListener(rewardService, rewardsCommand.getRewardMenu()), this);
            Bukkit.getPluginManager().registerEvents(rewardTracker, this);
            if (naturalBlockTracker != null) {
                Bukkit.getPluginManager().registerEvents(naturalBlockTracker, this);
            }
        }
        if (dailyService != null) {
            Bukkit.getPluginManager().registerEvents(dailyService, this);
            PluginCommand daily = getCommand("daily");
            if (daily != null) daily.setExecutor(dailyService);
        }

        PluginCommand rewards = getCommand("rewards");
        if (rewards != null) {
            rewards.setExecutor(rewardsCommand);
            rewards.setTabCompleter(rewardsCommand);
        }
    }

    private void registerCommands() {
        TagsCommand tagsCommand = new TagsCommand(tagService, this);
        PluginCommand tags = getCommand("tags");
        if (tags != null) {
            tags.setExecutor(tagsCommand);
            tags.setTabCompleter(tagsCommand);
        }

        TagAdminCommand tagAdminCommand = new TagAdminCommand(tagService, this);
        PluginCommand tag = getCommand("tag");
        if (tag != null) {
            tag.setExecutor(tagAdminCommand);
            tag.setTabCompleter(tagAdminCommand);
        }

        CosmeticsCommand cosmeticsCommand = new CosmeticsCommand(cosmeticsService, tagService, messages, this);
        PluginCommand cosmetics = getCommand("cosmetics");
        if (cosmetics != null) {
            cosmetics.setExecutor(cosmeticsCommand);
            cosmetics.setTabCompleter(cosmeticsCommand);
        }

        PluginCommand root = getCommand("enthusiatags");
        if (root != null) {
            root.setExecutor((sender, command, label, args) -> {
                if (!sender.hasPermission("enthusia.tags.admin")) {
                    sender.sendMessage(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacyAmpersand()
                        .deserialize(messages.get("no-permission")));
                    return true;
                }
                if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
                    reloadAllFiles();
                    sender.sendMessage(net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacyAmpersand()
                        .deserialize(messages.get("config-reloaded")));
                    return true;
                }
                if (args.length == 1 && args[0].equalsIgnoreCase("performance")) {
                    performanceMonitor.sendTo(sender);
                    sender.sendMessage("Reward sync: " + rewardService.syncStatus());
                    return true;
                }
                if (args.length >= 2 && args[0].equalsIgnoreCase("rewards")) {
                    return rewardService.handleAdminCommand(sender, java.util.Arrays.copyOfRange(args, 1, args.length));
                }
                if (args.length >= 2 && args[0].equalsIgnoreCase("daily")) {
                    if (dailyService == null) {
                        sender.sendMessage(net.kyori.adventure.text.Component.text(
                            "Daily rewards are unavailable because storage did not initialize."));
                        return true;
                    }
                    return dailyService.handleAdminCommand(sender, java.util.Arrays.copyOfRange(args, 1, args.length));
                }
                sender.sendMessage(net.kyori.adventure.text.Component.text("Usage: /enthusiatags reload"));
                return true;
            });
            root.setTabCompleter((sender, command, alias, args) -> args.length == 1 && sender.hasPermission("enthusia.tags.admin")
                ? java.util.List.of("reload", "performance", "rewards", "daily")
                : java.util.List.of());
        }
    }
}
