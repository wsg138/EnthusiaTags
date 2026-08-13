package org.enthusia.tags.rewards.loreitems;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import org.enthusia.tags.Messages;
import org.enthusia.tags.PlayerLookup;

import java.util.List;
import java.util.Objects;

public final class LoreItemRewardAdmin {
    public static final String PERMISSION = "enthusia.tags.rewards.loreitems.admin";

    private final JavaPlugin plugin;
    private final Messages messages;
    private final PlayerLookup playerLookup;
    private final LoreItemRewardRuntime runtime;

    public LoreItemRewardAdmin(
        JavaPlugin plugin,
        Messages messages,
        LoreItemRewardRuntime runtime) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.messages = Objects.requireNonNull(messages, "messages");
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.playerLookup = new PlayerLookup(plugin);
    }

    public boolean handles(String[] args) {
        if (args == null || args.length == 0) {
            return false;
        }
        return args[0].equalsIgnoreCase("lorestatus") || args[0].equalsIgnoreCase("loreretry");
    }

    public boolean handle(CommandSender sender, String[] args) {
        if (!sender.hasPermission(PERMISSION)) {
            send(sender, messages.get("rewards-loreitems-admin-no-permission"));
            return true;
        }
        if (args.length < 3) {
            sender.sendMessage(Component.text(
                "Usage: /enthusiatags rewards lorestatus <player|uuid> <reward> OR "
                    + "/enthusiatags rewards loreretry <player|uuid> <reward> <action-id>"));
            return true;
        }

        OfflinePlayer target = playerLookup.findPlayer(args[1]);
        if (target == null) {
            send(sender, messages.get("player-not-found"));
            return true;
        }
        String playerName = target.getName() == null ? target.getUniqueId().toString() : target.getName();
        String rewardId = args[2];

        if (args[0].equalsIgnoreCase("lorestatus")) {
            runtime.inspect(target.getUniqueId(), rewardId)
                .whenComplete((records, failure) -> plugin.getServer().getScheduler().runTask(plugin, () -> {
                    if (failure != null) {
                        sendError(sender, failure);
                        return;
                    }
                    send(sender, messages.get("rewards-loreitems-status-header")
                        .replace("{player}", playerName)
                        .replace("{reward}", rewardId));
                    if (records == null || records.isEmpty()) {
                        send(sender, messages.get("rewards-loreitems-status-empty"));
                        return;
                    }
                    for (LoreItemHandoffRecord record : records) {
                        send(sender, formatStatus(record));
                    }
                }));
            return true;
        }

        if (args.length < 4) {
            sender.sendMessage(Component.text(
                "Usage: /enthusiatags rewards loreretry <player|uuid> <reward> <action-id>"));
            return true;
        }
        String actionId = args[3];
        runtime.requestRetry(target.getUniqueId(), rewardId, actionId)
            .whenComplete((record, failure) -> plugin.getServer().getScheduler().runTask(plugin, () -> {
                if (failure != null) {
                    sendError(sender, failure);
                    return;
                }
                if (record == null) {
                    send(sender, messages.get("rewards-loreitems-retry-missing"));
                    return;
                }
                send(sender, messages.get("rewards-loreitems-retry-queued")
                    .replace("{player}", playerName)
                    .replace("{reward}", rewardId)
                    .replace("{action}", actionId)
                    .replace("{state}", record.state().name()));
            }));
        return true;
    }

    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (!sender.hasPermission(PERMISSION)) {
            return List.of();
        }
        if (args.length == 1) {
            return List.of("lorestatus", "loreretry");
        }
        return List.of();
    }

    private String formatStatus(LoreItemHandoffRecord record) {
        return messages.get("rewards-loreitems-status-line")
            .replace("{action}", record.actionId())
            .replace("{definition}", record.definitionKey())
            .replace("{operation}", record.externalOperationId())
            .replace("{state}", record.state().name())
            .replace("{outcome}", blank(record.lastOutcome()))
            .replace("{attempts}", Integer.toString(record.attempts()))
            .replace("{error}", blank(record.lastError()));
    }

    private void sendError(CommandSender sender, Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null && current != current.getCause()) {
            current = current.getCause();
        }
        String detail = current.getMessage();
        if (detail == null || detail.isBlank()) {
            detail = current.getClass().getSimpleName();
        }
        send(sender, messages.get("rewards-loreitems-admin-error").replace("{error}", detail));
    }

    private static String blank(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private static void send(CommandSender sender, String text) {
        sender.sendMessage(LegacyComponentSerializer.legacyAmpersand().deserialize(text));
    }
}
