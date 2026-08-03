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
    private static final String ADMIN_PERMISSION = "enthusia.tags.admin";
    private static final String COMMAND_GIVE = "give";
    private static final String COMMAND_REVOKE = "revoke";
    private static final String COMMAND_SET = "set";
    private static final String COMMAND_CLEAR = "clear";
    private static final String COMMAND_LIST = "list";
    private static final String COMMAND_CREATE = "create";
    private static final String COMMAND_EDIT = "edit";
    private static final String COMMAND_OFFSET = "offset";
    private static final String COMMAND_RELOAD = "reload";
    private static final String MSG_PLAYER_NOT_FOUND = "player-not-found";
    private static final String MSG_UNKNOWN_TAG = "unknown-tag";
    private static final int PLAYER_ARGUMENTS = 2;
    private static final int TAG_ARGUMENTS = 3;
    private static final List<String> ROOT_COMPLETIONS = List.of(
        COMMAND_GIVE, COMMAND_REVOKE, COMMAND_SET, COMMAND_CLEAR, COMMAND_LIST,
        COMMAND_CREATE, COMMAND_EDIT, COMMAND_OFFSET, COMMAND_RELOAD
    );
    private static final Set<String> PLAYER_TARGET_COMMANDS = Set.of(
        COMMAND_GIVE, COMMAND_REVOKE, COMMAND_SET, COMMAND_CLEAR, COMMAND_LIST
    );
    private static final Set<String> TAG_TARGET_COMMANDS = Set.of(COMMAND_GIVE, COMMAND_REVOKE, COMMAND_SET);

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
        if (!sender.hasPermission(ADMIN_PERMISSION)) {
            sender.sendMessage(message("no-permission"));
            return true;
        }
        if (args.length == 0) {
            sendUsage(sender);
            return true;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case COMMAND_GIVE -> handleGrant(sender, args);
            case COMMAND_REVOKE -> handleRevoke(sender, args);
            case COMMAND_CLEAR -> handleClear(sender, args);
            case COMMAND_RELOAD -> handleReload(sender);
            case COMMAND_SET -> handleSet(sender, args);
            case COMMAND_LIST -> handleList(sender, args);
            case COMMAND_CREATE -> handleCreate(sender, args);
            case COMMAND_EDIT -> handleEdit(sender, args);
            case COMMAND_OFFSET -> handleOffset(sender, args);
            default -> sendUsage(sender);
        }
        return true;
    }

    private void handleGrant(CommandSender sender, String[] args) {
        OfflinePlayer target = requireTarget(sender, args, TAG_ARGUMENTS);
        if (target == null) return;
        String tagId = args[2].toLowerCase(Locale.ROOT);
        tagService.grantTagAsync(target, tagId).thenAccept(result ->
            Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage(switch (result) {
                case PLAYER_NOT_FOUND -> message(MSG_PLAYER_NOT_FOUND);
                case UNKNOWN_TAG -> formatMessage(MSG_UNKNOWN_TAG, "", tagId);
                default -> formatMessage("tag-granted", target.getName(), tagId);
            })));
    }

    private void handleRevoke(CommandSender sender, String[] args) {
        OfflinePlayer target = requireTarget(sender, args, TAG_ARGUMENTS);
        if (target == null) return;
        String tagId = args[2].toLowerCase(Locale.ROOT);
        tagService.revokeTagAsync(target, tagId).thenAccept(result ->
            Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage(switch (result) {
                case PLAYER_NOT_FOUND -> message(MSG_PLAYER_NOT_FOUND);
                case UNKNOWN_TAG -> formatMessage(MSG_UNKNOWN_TAG, "", tagId);
                default -> formatMessage("tag-revoked", target.getName(), tagId);
            })));
    }

    private void handleClear(CommandSender sender, String[] args) {
        OfflinePlayer target = requireTarget(sender, args, PLAYER_ARGUMENTS);
        if (target == null) return;
        tagService.setSelectedTagAsync(target, null).thenAccept(result ->
            Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage(
                result == TagAdminResult.PLAYER_NOT_FOUND ? message(MSG_PLAYER_NOT_FOUND)
                    : formatMessage("tag-cleared", target.getName(), ""))));
    }

    private void handleReload(CommandSender sender) {
        plugin.reloadAllFiles();
        sender.sendMessage(message("config-reloaded"));
    }

    private void handleSet(CommandSender sender, String[] args) {
        OfflinePlayer target = requireTarget(sender, args, TAG_ARGUMENTS);
        if (target == null) return;
        String tagId = args[2].toLowerCase(Locale.ROOT);
        tagService.setSelectedTagAsync(target, tagId).thenAccept(result ->
            Bukkit.getScheduler().runTask(plugin, () -> sender.sendMessage(switch (result) {
                case PLAYER_NOT_FOUND -> message(MSG_PLAYER_NOT_FOUND);
                case UNKNOWN_TAG -> formatMessage(MSG_UNKNOWN_TAG, "", tagId);
                case TAG_NOT_OWNED -> formatMessage("tag-not-owned", target.getName(), tagId);
                default -> formatMessage("tag-selected", target.getName(), tagId);
            })));
    }

    private void handleList(CommandSender sender, String[] args) {
        OfflinePlayer target = requireTarget(sender, args, PLAYER_ARGUMENTS);
        if (target == null) return;
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
    }

    private void handleCreate(CommandSender sender, String[] args) {
        if (args.length < TAG_ARGUMENTS) {
            sendUsage(sender);
            return;
        }
        String id = args[1].toLowerCase(Locale.ROOT);
        String displayName = joinArgs(args, 2);
        boolean created = tagService.createTag(id, displayName, displayName);
        sender.sendMessage(created ? Component.text("Created tag " + id + ".") : formatMessage(MSG_UNKNOWN_TAG, "", id));
    }

    private void handleEdit(CommandSender sender, String[] args) {
        if (args.length < TAG_ARGUMENTS) {
            sendUsage(sender);
            return;
        }
        String id = args[1].toLowerCase(Locale.ROOT);
        String tagText = joinArgs(args, 2);
        boolean updated = tagService.updateTagText(id, tagText);
        sender.sendMessage(updated ? Component.text("Updated tag " + id + ".") : formatMessage(MSG_UNKNOWN_TAG, "", id));
    }

    private void handleOffset(CommandSender sender, String[] args) {
        if (args.length < PLAYER_ARGUMENTS) {
            sendUsage(sender);
            return;
        }
        try {
            double offset = Double.parseDouble(args[1]);
            tagService.setDisplayOffset(offset);
            sender.sendMessage(Component.text(
                "Tag display offset is now controlled by UnlimitedNametags; no EnthusiaTags value was changed."));
        } catch (NumberFormatException ex) {
            sender.sendMessage(message("tag-offset-invalid"));
        }
    }

    private OfflinePlayer requireTarget(CommandSender sender, String[] args, int requiredArgs) {
        if (args.length < requiredArgs) {
            sendUsage(sender);
            return null;
        }
        OfflinePlayer target = playerLookup.findPlayer(args[1]);
        if (target == null) sender.sendMessage(message(MSG_PLAYER_NOT_FOUND));
        return target;
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        if (!sender.hasPermission(ADMIN_PERMISSION)) return Collections.emptyList();
        if (args.length == PLAYER_ARGUMENTS - 1) return ROOT_COMPLETIONS;
        String subcommand = args[0].toLowerCase(Locale.ROOT);
        if (args.length == PLAYER_ARGUMENTS && PLAYER_TARGET_COMMANDS.contains(subcommand)) return onlinePlayerNames();
        if (args.length == PLAYER_ARGUMENTS && COMMAND_EDIT.equals(subcommand)) return tagIds();
        if (args.length == TAG_ARGUMENTS && TAG_TARGET_COMMANDS.contains(subcommand)) return tagIds();
        return Collections.emptyList();
    }

    private List<String> onlinePlayerNames() {
        List<String> names = new ArrayList<>();
        for (var player : Bukkit.getOnlinePlayers()) names.add(player.getName());
        return names;
    }

    private List<String> tagIds() {
        Set<String> ids = new java.util.HashSet<>();
        for (TagDefinition tag : tagService.getRegistry().getAll()) ids.add(tag.getId().toLowerCase(Locale.ROOT));
        return new ArrayList<>(ids);
    }

    private void sendUsage(CommandSender sender) {
        sender.sendMessage(Component.text("Usage: /tag give <player> <tag>"));
        sender.sendMessage(Component.text("       /tag revoke <player> <tag>"));
        sender.sendMessage(Component.text("       /tag set <player> <tag>"));
        sender.sendMessage(Component.text("       /tag clear <player>"));
        sender.sendMessage(Component.text("       /tag list <player>"));
        sender.sendMessage(Component.text("       /tag create <id> <display name>"));
        sender.sendMessage(Component.text("       /tag edit <id> <tag text>"));
        sender.sendMessage(Component.text("       /tag offset <number> (deprecated)"));
        sender.sendMessage(Component.text("       /tag reload"));
    }

    private String joinArgs(String[] args, int start) {
        StringBuilder builder = new StringBuilder();
        for (int i = start; i < args.length; i++) {
            if (i > start) builder.append(' ');
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
