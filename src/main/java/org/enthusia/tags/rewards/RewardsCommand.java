package org.enthusia.tags.rewards;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.enthusia.tags.EnthusiaTagsPlugin;
import org.enthusia.tags.Messages;
import org.enthusia.tags.TagService;

import java.util.Collections;
import java.util.List;

public final class RewardsCommand implements CommandExecutor, TabCompleter {
    private final RewardMenu rewardMenu;
    private final Messages messages;
    private final EnthusiaTagsPlugin plugin;
    private final RewardService rewardService;

    public RewardsCommand(RewardService rewardService, TagService tagService, Messages messages, EnthusiaTagsPlugin plugin) {
        this.rewardMenu = new RewardMenu(rewardService, tagService);
        this.rewardService = rewardService;
        this.messages = messages;
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("enthusia.tags.admin")) {
                sender.sendMessage(message("no-permission"));
                return true;
            }
            plugin.reloadAllFiles();
            sender.sendMessage(message("config-reloaded"));
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(message("players-only"));
            return true;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("open")) {
            RewardDefinition reward = rewardService.getRewards().get(args[1].toLowerCase(java.util.Locale.ROOT));
            if (reward == null) {
                player.sendMessage(Component.text("Unknown reward."));
                return true;
            }
            player.openInventory(rewardMenu.createCategory(player, reward.getCategory()));
            return true;
        }
        player.openInventory(rewardMenu.create(player));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1 && sender.hasPermission("enthusia.tags.admin")) {
            return List.of("reload", "open");
        }
        return Collections.emptyList();
    }

    public RewardMenu getRewardMenu() {
        return rewardMenu;
    }

    private Component message(String key) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(messages.get(key));
    }
}
