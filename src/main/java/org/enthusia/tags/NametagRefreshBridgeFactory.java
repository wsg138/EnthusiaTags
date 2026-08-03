package org.enthusia.tags;

import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.function.Consumer;
import java.util.function.Supplier;

final class NametagRefreshBridgeFactory {
    static final String UNAVAILABLE_WARNING = "UnlimitedNametags is absent or incompatible; selected tags remain stored and manageable, but will not appear above players until a compatible nametag consumer is installed.";

    private NametagRefreshBridgeFactory() {
    }

    static NametagRefreshBridge create(JavaPlugin plugin) {
        Plugin dependency = plugin.getServer().getPluginManager().getPlugin("UnlimitedNametags");
        boolean enabled = dependency != null && dependency.isEnabled();
        return create(enabled, UnlimitedNametagsBridge::new, plugin.getLogger()::warning);
    }

    static NametagRefreshBridge create(boolean dependencyEnabled,
                                        Supplier<NametagRefreshBridge> bridgeFactory,
                                        Consumer<String> warningLogger) {
        if (!dependencyEnabled) {
            warningLogger.accept(UNAVAILABLE_WARNING);
            return NoOpNametagRefreshBridge.INSTANCE;
        }
        try {
            return bridgeFactory.get();
        } catch (LinkageError | RuntimeException ex) {
            warningLogger.accept(UNAVAILABLE_WARNING + " Cause: " + ex.getClass().getSimpleName()
                + (ex.getMessage() == null ? "" : ": " + ex.getMessage()));
            return NoOpNametagRefreshBridge.INSTANCE;
        }
    }
}
