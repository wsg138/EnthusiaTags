package org.enthusia.tags;

import org.bukkit.Bukkit;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;
import org.enthusia.tags.cosmetics.CosmeticsCommand;
import org.enthusia.tags.cosmetics.CosmeticsListener;
import org.enthusia.tags.cosmetics.CosmeticsService;
import org.enthusia.tags.rewards.RewardListener;
import org.enthusia.tags.rewards.RewardService;
import org.enthusia.tags.rewards.RewardTracker;
import org.enthusia.tags.rewards.RewardsCommand;

public final class EnthusiaTagsPlugin extends JavaPlugin {
    private TagService tagService;
    private Messages messages;
    private RewardService rewardService;
    private RewardTracker rewardTracker;
    private CosmeticsService cosmeticsService;

    @Override
    public void onEnable() {
        messages = new Messages(this);
        messages.reload();

        tagService = new TagService(this, messages);
        cosmeticsService = new CosmeticsService(this, messages);
        rewardService = new RewardService(this, tagService, messages);
        rewardTracker = new RewardTracker(rewardService);

        tagService.enable();
        cosmeticsService.enable();
        rewardService.enable();
        rewardTracker.start(this);

        Bukkit.getServicesManager().register(TagService.class, tagService, this, ServicePriority.Normal);
        registerListeners();
        registerCommands();
    }

    @Override
    public void onDisable() {
        Bukkit.getServicesManager().unregister(tagService);
        if (rewardTracker != null) {
            rewardTracker.stop();
        }
        if (rewardService != null) {
            rewardService.disable();
        }
        if (cosmeticsService != null) {
            cosmeticsService.disable();
        }
        if (tagService != null) {
            tagService.disable();
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

    public void reloadAllFiles() {
        reloadConfig();
        messages.reload();
        tagService.reloadAll();
        rewardService.reload();
        cosmeticsService.reload();
    }

    private void registerListeners() {
        Bukkit.getPluginManager().registerEvents(new TagListener(tagService, rewardService), this);
        Bukkit.getPluginManager().registerEvents(new CosmeticsListener(cosmeticsService, tagService, messages, rewardService), this);
        RewardsCommand rewardsCommand = new RewardsCommand(rewardService, tagService, messages, this);
        Bukkit.getPluginManager().registerEvents(new RewardListener(rewardService, rewardsCommand.getRewardMenu()), this);
        Bukkit.getPluginManager().registerEvents(rewardTracker, this);

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
                sender.sendMessage(net.kyori.adventure.text.Component.text("Usage: /enthusiatags reload"));
                return true;
            });
            root.setTabCompleter((sender, command, alias, args) -> args.length == 1 && sender.hasPermission("enthusia.tags.admin")
                ? java.util.List.of("reload")
                : java.util.List.of());
        }
    }
}
