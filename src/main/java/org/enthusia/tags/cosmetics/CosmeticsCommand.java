package org.enthusia.tags.cosmetics;

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

public final class CosmeticsCommand implements CommandExecutor, TabCompleter {
    private final CosmeticsMenu cosmeticsMenu;
    private final Messages messages;
    private final EnthusiaTagsPlugin plugin;

    public CosmeticsCommand(CosmeticsService cosmeticsService,
                            TagService tagService,
                            Messages messages,
                            EnthusiaTagsPlugin plugin) {
        this.cosmeticsMenu = new CosmeticsMenu(cosmeticsService, tagService, messages);
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
        if (!sender.hasPermission("enthusia.cosmetics.use")) {
            sender.sendMessage(message("no-permission"));
            return true;
        }
        player.openInventory(cosmeticsMenu.createMain(player));
        return true;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (args.length == 1 && sender.hasPermission("enthusia.tags.admin")) {
            return List.of("reload");
        }
        return Collections.emptyList();
    }

    private Component message(String key) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(messages.get(key));
    }
}
