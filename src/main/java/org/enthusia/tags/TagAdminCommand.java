package org.enthusia.tags;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class TagAdminCommand implements CommandExecutor, TabCompleter {
    private final TagService tagService;
    private final EnthusiaTagsPlugin plugin;
    private final PlayerLookup playerLookup;

    public TagAdminCommand(TagService tagService, EnthusiaTagsPlugin plugin) {
        this.tagService = tagService;
        this.plugin = plugin;
        this.playerLookup = new PlayerLookup(plugin);
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("enthusia.tags.admin")) {
            sender.sendMessage(message("no-permission"));
            return true;
        }
        if (args.length < 1) {
            sendUsage(sender);
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "give" -> {
                if (args.length < 3) {
                    sendUsage(sender);
                    return true;
                }
                OfflinePlayer target = playerLookup.findPlayer(args[1]);
                if (target == null) {
                    sender.sendMessage(message("player-not-found"));
                    return true;
                }
                String tagId = args[2].toLowerCase(Locale.ROOT);
                tagService.grantTagAsync(target, tagId).thenAccept(result ->
                    Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage(switch (result) {
                        case PLAYER_NOT_FOUND -> message("player-not-found");
                        case UNKNOWN_TAG -> formatMessage("unknown-tag", "", tagId);
                        default -> formatMessage("tag-granted", target.getName(), tagId);
                    })));
                return true;
            }
            case "revoke" -> {
                if (args.length < 3) {
                    sendUsage(sender);
                    return true;
                }
                OfflinePlayer target = playerLookup.findPlayer(args[1]);
                if (target == null) {
                    sender.sendMessage(message("player-not-found"));
                    return true;
                }
                String tagId = args[2].toLowerCase(Locale.ROOT);
                tagService.revokeTagAsync(target, tagId).thenAccept(result ->
                    Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage(switch (result) {
                        case PLAYER_NOT_FOUND -> message("player-not-found");
                        case UNKNOWN_TAG -> formatMessage("unknown-tag", "", tagId);
                        default -> formatMessage("tag-revoked", target.getName(), tagId);
                    })));
                return true;
            }
            case "clear" -> {
                if (args.length < 2) {
                    sendUsage(sender);
                    return true;
                }
                OfflinePlayer target = playerLookup.findPlayer(args[1]);
                if (target == null) {
                    sender.sendMessage(message("player-not-found"));
                    return true;
                }
                tagService.setSelectedTagAsync(target, null).thenAccept(result ->
                    Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage(
                        result == TagAdminResult.PLAYER_NOT_FOUND ? message("player-not-found")
                            : formatMessage("tag-cleared", target.getName(), ""))));
                return true;
            }
            case "reload" -> {
                plugin.reloadAllFiles();
                sender.sendMessage(message("config-reloaded"));
                return true;
            }
            case "set" -> {
                if (args.length < 3) {
                    sendUsage(sender);
                    return true;
                }
                OfflinePlayer target = playerLookup.findPlayer(args[1]);
                if (target == null) {
                    sender.sendMessage(message("player-not-found"));
                    return true;
                }
                String tagId = args[2].toLowerCase(Locale.ROOT);
                tagService.setSelectedTagAsync(target, tagId).thenAccept(result ->
                    Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage(switch (result) {
                        case PLAYER_NOT_FOUND -> message("player-not-found");
                        case UNKNOWN_TAG -> formatMessage("unknown-tag", "", tagId);
                        case TAG_NOT_OWNED -> formatMessage("tag-not-owned", target.getName(), tagId);
                        default -> formatMessage("tag-selected", target.getName(), tagId);
                    })));
                return true;
            }
            case "list" -> {
                if (args.length < 2) {
                    sendUsage(sender);
                    return true;
                }
                OfflinePlayer target = playerLookup.findPlayer(args[1]);
                if (target == null) {
                    sender.sendMessage(message("player-not-found"));
                    return true;
                }
                tagService.getPlayerDataAsync(target.getUniqueId()).thenAccept(data ->
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        String selected = data.getSelectedTag();
                        sender.sendMessage(formatMessage("tag-list-header", target.getName(), selected == null ? "none" : selected));
                        if (data.getOwnedTags().isEmpty()) {
                            sender.sendMessage(message("tag-list-empty"));
                            return;
                        }
                        sender.sendMessage(formatMessage("tag-list-items", "", String.join(", ", data.getOwnedTags())));
                    }));
                return true;
            }
            case "create" -> {
                if (args.length < 3) {
                    sendUsage(sender);
                    return true;
                }
                String id = args[1].toLowerCase(Locale.ROOT);
                String displayName = joinArgs(args, 2);
                boolean created = tagService.createTag(id, displayName, displayName);
                if (!created) {
                    sender.sendMessage(formatMessage("unknown-tag", "", id));
                } else {
                    sender.sendMessage(Component.text("Created tag " + id + "."));
                }
                return true;
            }
            case "edit" -> {
                if (args.length < 3) {
                    sendUsage(sender);
                    return true;
                }
                String id = args[1].toLowerCase(Locale.ROOT);
                String tagText = joinArgs(args, 2);
                boolean updated = tagService.updateTagText(id, tagText);
                if (!updated) {
                    sender.sendMessage(formatMessage("unknown-tag", "", id));
                } else {
                    sender.sendMessage(Component.text("Updated tag " + id + "."));
                }
                return true;
            }
            case "offset" -> {
                if (args.length < 2) {
                    sendUsage(sender);
                    return true;
                }
                try {
                    double offset = Double.parseDouble(args[1]);
                    tagService.setDisplayOffset(offset);
                    sender.sendMessage(formatMessage("tag-offset-set", "", String.valueOf(offset)));
                } catch (NumberFormatException ex) {
                    sender.sendMessage(message("tag-offset-invalid"));
                }
                return true;
            }
            default -> {
                sendUsage(sender);
                return true;
            }
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission("enthusia.tags.admin")) {
            return Collections.emptyList();
        }
        if (args.length == 1) {
            return List.of("give", "revoke", "set", "clear", "list", "create", "edit", "offset", "reload");
        }
        if (args.length == 2 && (args[0].equalsIgnoreCase("give")
            || args[0].equalsIgnoreCase("revoke")
            || args[0].equalsIgnoreCase("set")
            || args[0].equalsIgnoreCase("clear")
            || args[0].equalsIgnoreCase("list"))) {
            List<String> names = new ArrayList<>();
            for (var player : Bukkit.getOnlinePlayers()) {
                names.add(player.getName());
            }
            return names;
        }
        if (args.length == 2 && args[0].equalsIgnoreCase("edit")) {
            List<String> ids = new ArrayList<>();
            for (TagDefinition tag : tagService.getRegistry().getAll()) {
                ids.add(tag.getId().toLowerCase(Locale.ROOT));
            }
            return ids;
        }
        if (args.length == 3 && (args[0].equalsIgnoreCase("give")
            || args[0].equalsIgnoreCase("revoke")
            || args[0].equalsIgnoreCase("set"))) {
            Set<String> ids = new java.util.HashSet<>();
            for (TagDefinition tag : tagService.getRegistry().getAll()) {
                ids.add(tag.getId().toLowerCase(Locale.ROOT));
            }
            return new ArrayList<>(ids);
        }
        return Collections.emptyList();
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage(Component.text("Usage: /tag give <player> <tag>"));
        sender.sendMessage(Component.text("       /tag revoke <player> <tag>"));
        sender.sendMessage(Component.text("       /tag set <player> <tag>"));
        sender.sendMessage(Component.text("       /tag clear <player>"));
        sender.sendMessage(Component.text("       /tag list <player>"));
        sender.sendMessage(Component.text("       /tag create <id> <display name>"));
        sender.sendMessage(Component.text("       /tag edit <id> <tag text>"));
        sender.sendMessage(Component.text("       /tag offset <number>"));
        sender.sendMessage(Component.text("       /tag reload"));
    }

    private String joinArgs(String[] args, int start) {
        StringBuilder builder = new StringBuilder();
        for (int i = start; i < args.length; i++) {
            if (i > start) {
                builder.append(' ');
            }
            builder.append(args[i]);
        }
        return builder.toString();
    }

    private Component message(String key) {
        return LegacyComponentSerializer.legacyAmpersand().deserialize(tagService.getMessages().get(key));
    }

    private Component formatMessage(String key, String playerName, String tagId) {
        String raw = tagService.getMessages().get(key)
            .replace("{player}", playerName == null ? "" : playerName)
            .replace("{tag}", tagId == null ? "" : tagId);
        return LegacyComponentSerializer.legacyAmpersand().deserialize(raw);
    }
}
