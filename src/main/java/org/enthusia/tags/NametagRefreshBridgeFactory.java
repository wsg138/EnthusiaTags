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
        Consumer<String> warningLogger = plugin.getLogger()::warning;
        Plugin dependency = plugin.getServer().getPluginManager().getPlugin("UnlimitedNametags");
        if (dependency == null || !dependency.isEnabled()) {
            return unavailable(warningLogger, null);
        }
        try {
            return new UnlimitedNametagsBridge();
        } catch (LinkageError | RuntimeException ex) {
            return unavailable(warningLogger, ex);
        }
    }

    static NametagRefreshBridge create(boolean dependencyEnabled,
                                        Supplier<NametagRefreshBridge> bridgeFactory,
                                        Consumer<String> warningLogger) {
        if (!dependencyEnabled) {
            return unavailable(warningLogger, null);
        }
        try {
            return bridgeFactory.get();
        } catch (LinkageError | RuntimeException ex) {
            return unavailable(warningLogger, ex);
        }
    }

    private static NametagRefreshBridge unavailable(Consumer<String> warningLogger, Throwable cause) {
        String detail = cause == null ? "" : " Cause: " + cause.getClass().getSimpleName()
            + (cause.getMessage() == null ? "" : ": " + cause.getMessage());
        warningLogger.accept(UNAVAILABLE_WARNING + detail);
        return NoOpNametagRefreshBridge.INSTANCE;
    }
}
