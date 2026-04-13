package org.enthusia.tags;

import org.bukkit.Bukkit;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.java.JavaPlugin;

public class EnthusiaTagsPlugin extends JavaPlugin {
    private TagService tagService;
    private Messages messages;
    private org.enthusia.tags.rewards.RewardService rewardService;
    private org.enthusia.tags.rewards.RewardTracker rewardTracker;
    private org.enthusia.tags.cosmetics.CosmeticsService cosmeticsService;

    @Override
    public void onEnable() {
        messages = new Messages(this);
        messages.reload();
        tagService = new TagService(this, messages);
        tagService.enable();
        cosmeticsService = new org.enthusia.tags.cosmetics.CosmeticsService(this, messages);
        cosmeticsService.enable();
        rewardService = new org.enthusia.tags.rewards.RewardService(this, tagService, messages);
        rewardService.enable();
        rewardTracker = new org.enthusia.tags.rewards.RewardTracker(rewardService, tagService);
        rewardTracker.start(this);
        Bukkit.getServicesManager().register(TagService.class, tagService, this, ServicePriority.Normal);
        Bukkit.getPluginManager().registerEvents(new TagListener(tagService, rewardService), this);
        Bukkit.getPluginManager().registerEvents(
            new org.enthusia.tags.cosmetics.CosmeticsListener(cosmeticsService, tagService, messages), this);
        TagsCommand tagsCommand = new TagsCommand(tagService, this);
        getCommand("tags").setExecutor(tagsCommand);
        getCommand("tags").setTabCompleter(tagsCommand);
        TagAdminCommand adminCommand = new TagAdminCommand(tagService, this);
        getCommand("tag").setExecutor(adminCommand);
        getCommand("tag").setTabCompleter(adminCommand);
        org.enthusia.tags.cosmetics.CosmeticsCommand cosmeticsCommand =
            new org.enthusia.tags.cosmetics.CosmeticsCommand(cosmeticsService, tagService, messages);
        getCommand("cosmetics").setExecutor(cosmeticsCommand);
        getCommand("cosmetics").setTabCompleter(cosmeticsCommand);
        org.enthusia.tags.rewards.RewardsCommand rewardsCommand =
            new org.enthusia.tags.rewards.RewardsCommand(rewardService, tagService, messages);
        getCommand("rewards").setExecutor(rewardsCommand);
        getCommand("rewards").setTabCompleter(rewardsCommand);
        Bukkit.getPluginManager().registerEvents(
            new org.enthusia.tags.rewards.RewardListener(rewardService, rewardsCommand.getRewardMenu()),
            this);
        Bukkit.getPluginManager().registerEvents(rewardTracker, this);
    }

    @Override
    public void onDisable() {
        if (tagService != null) {
            Bukkit.getServicesManager().unregister(tagService);
            tagService.disable();
        }
        if (rewardTracker != null) {
            rewardTracker.stop();
        }
        if (rewardService != null) {
            rewardService.disable();
        }
        if (cosmeticsService != null) {
            cosmeticsService.disable();
        }
    }

    public TagService getTagService() {
        return tagService;
    }

    public Messages getMessages() {
        return messages;
    }

    public org.enthusia.tags.rewards.RewardService getRewardService() {
        return rewardService;
    }

    public org.enthusia.tags.cosmetics.CosmeticsService getCosmeticsService() {
        return cosmeticsService;
    }

    public void reloadAllFiles() {
        messages.reload();
        if (tagService != null) {
            tagService.reloadAll();
        }
        if (rewardService != null) {
            rewardService.reload();
        }
        if (cosmeticsService != null) {
            cosmeticsService.reload();
        }
    }
}
