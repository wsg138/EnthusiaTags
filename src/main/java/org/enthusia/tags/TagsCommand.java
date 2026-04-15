package org.enthusia.tags;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.enthusia.tags.cosmetics.CosmeticsMenu;

import java.util.Collections;
import java.util.List;

public final class TagsCommand implements CommandExecutor, TabCompleter {
    private final TagMenu tagMenu;
    private final TagService tagService;
    private final EnthusiaTagsPlugin plugin;

    public TagsCommand(TagService tagService, EnthusiaTagsPlugin plugin) {
        this.tagService = tagService;
        this.tagMenu = new TagMenu(tagService);
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
        if (args.length == 1 && args[0].equalsIgnoreCase("cosmetics")) {
            if (!sender.hasPermission("enthusia.cosmetics.use")) {
                sender.sendMessage(message("no-permission"));
                return true;
            }
            if (!(sender instanceof Player player)) {
                sender.sendMessage(message("players-only"));
                return true;
            }
            CosmeticsMenu menu = new CosmeticsMenu(plugin.getCosmeticsService(), tagService, plugin.getMessages());
            player.openInventory(menu.createMain(player));
            return true;
        }
        if (!(sender instanceof Player player)) {
            sender.sendMessage(message("players-only"));
            return true;
        }
        player.openInventory(tagMenu.create(player));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1) {
            if (sender.hasPermission("enthusia.tags.admin")) {
                return List.of("reload", "cosmetics");
            }
            return List.of("cosmetics");
        }
        return Collections.emptyList();
    }

    public TagMenu getTagMenu() {
        return tagMenu;
    }

    private Component message(String key) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(tagService.getMessages().get(key));
    }
}
