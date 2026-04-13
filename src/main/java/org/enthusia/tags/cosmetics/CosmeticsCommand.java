package org.enthusia.tags.cosmetics;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.enthusia.tags.Messages;
import org.enthusia.tags.TagService;

import java.util.Collections;
import java.util.List;

public final class CosmeticsCommand implements CommandExecutor, TabCompleter {
    private final CosmeticsService cosmeticsService;
    private final CosmeticsMenu cosmeticsMenu;
    private final Messages messages;

    public CosmeticsCommand(CosmeticsService cosmeticsService, TagService tagService, Messages messages) {
        this.cosmeticsService = cosmeticsService;
        this.cosmeticsMenu = new CosmeticsMenu(cosmeticsService, tagService, messages);
        this.messages = messages;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            if (!sender.hasPermission("enthusia.tags.admin")) {
                sender.sendMessage(message("no-permission"));
                return true;
            }
            cosmeticsService.reload();
            sender.sendMessage(message("cosmetics-reloaded"));
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
        String raw = messages.get(key);
        return LegacyComponentSerializer.legacyAmpersand().deserialize(raw);
    }
}
