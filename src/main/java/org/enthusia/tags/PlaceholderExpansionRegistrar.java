package org.enthusia.tags;

import org.bukkit.plugin.Plugin;

final class PlaceholderExpansionRegistrar {
    private PlaceholderExpansionRegistrar() {
    }

    static AutoCloseable register(EnthusiaTagsPlugin plugin, TagService tagService) {
        Plugin placeholderApi = plugin.getServer().getPluginManager().getPlugin("PlaceholderAPI");
        if (placeholderApi == null || !placeholderApi.isEnabled()) {
            plugin.getLogger().warning("PlaceholderAPI is not installed; EnthusiaTags placeholders are unavailable.");
            return () -> { };
        }
        try {
            EnthusiaTagsPlaceholderExpansion expansion = new EnthusiaTagsPlaceholderExpansion(plugin, tagService);
            if (!expansion.register()) {
                plugin.getLogger().warning("Failed to register the persistent enthusiatags PlaceholderAPI expansion.");
                return () -> { };
            }
            return expansion;
        } catch (LinkageError | RuntimeException ex) {
            plugin.getLogger().warning("PlaceholderAPI is incompatible; EnthusiaTags placeholders are unavailable: "
                + ex.getMessage());
            return () -> { };
        }
    }
}
