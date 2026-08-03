package org.enthusia.tags;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

final class EnthusiaTagsPlaceholderExpansion extends PlaceholderExpansion implements AutoCloseable {
    private static final List<String> PLACEHOLDERS = List.of(
        "%enthusiatags_selected_mm%",
        "%enthusiatags_selected_plain%",
        "%enthusiatags_selected_id%",
        "%enthusiatags_selected_legacy%"
    );

    private final EnthusiaTagsPlugin plugin;
    private final TagService tagService;
    private final ThreadLocal<Boolean> evaluating = ThreadLocal.withInitial(() -> false);

    EnthusiaTagsPlaceholderExpansion(EnthusiaTagsPlugin plugin, TagService tagService) {
        this.plugin = plugin;
        this.tagService = tagService;
    }

    @Override
    public @NotNull String getIdentifier() {
        return "enthusiatags";
    }

    @Override
    public @NotNull String getAuthor() {
        return "Enthusia";
    }

    @Override
    public @NotNull String getVersion() {
        return plugin.getDescription().getVersion();
    }

    @Override
    public @NotNull List<String> getPlaceholders() {
        return PLACEHOLDERS;
    }

    @Override
    public boolean persist() {
        return true;
    }

    @Override
    public @Nullable String onRequest(OfflinePlayer player, @NotNull String params) {
        if (player == null) {
            return "";
        }
        if (evaluating.get()) {
            return "";
        }
        evaluating.set(true);
        try {
            return tagService.getPlaceholderOutput(
                player.getUniqueId(), player.getPlayer(), player.getName()).value(params);
        } finally {
            evaluating.remove();
        }
    }

    @Override
    public void close() {
        if (isRegistered()) {
            unregister();
        }
        evaluating.remove();
    }
}
