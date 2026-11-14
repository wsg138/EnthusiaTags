package org.enthusia.tags.advancements;

import io.github.badgersmc.advancements.pilot.ProjectionService;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.logging.Logger;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;

/**
 * REQ-1154. The toast-only overload arrived in EnthusiaAdvancements 1.0.0-pilot.7,
 * after the renderer version Tags compiles against, so it is looked up on the
 * installed renderer at runtime. Older renderers get the full celebration.
 */
final class CelebrationGateway {
    private final ProjectionService projection;
    private final Plugin plugin;
    private final Logger logger;
    private final Method toastOnly;
    private boolean warned;

    CelebrationGateway(ProjectionService projection, Plugin plugin, Logger logger) {
        this.projection = projection;
        this.plugin = plugin;
        this.logger = logger;
        this.toastOnly = find(projection);
    }

    private static Method find(ProjectionService projection) {
        try {
            Method method = projection.getClass().getMethod("celebrate",
                Plugin.class, String.class, Player.class, String.class, boolean.class);
            return method.getReturnType() == void.class ? method : null;
        } catch (NoSuchMethodException | SecurityException unsupported) {
            return null;
        }
    }

    boolean supportsToastOnly() {
        return toastOnly != null;
    }

    void celebrate(Player player, String namespace, String key, boolean announce) {
        if (announce) {
            projection.celebrate(plugin, namespace, player, key);
            return;
        }
        if (toastOnly == null) {
            if (!warned) {
                warned = true;
                logger.warning("EnthusiaAdvancements has no toast-only celebration (needs 1.0.0-pilot.7);"
                    + " quiet rewards will also be announced in chat.");
            }
            projection.celebrate(plugin, namespace, player, key);
            return;
        }
        try {
            toastOnly.invoke(projection, plugin, namespace, player, key, false);
        } catch (IllegalAccessException error) {
            projection.celebrate(plugin, namespace, player, key);
        } catch (InvocationTargetException error) {
            if (error.getCause() instanceof RuntimeException runtime) throw runtime;
            if (error.getCause() instanceof Error fatal) throw fatal;
            throw new IllegalStateException(error.getCause());
        }
    }
}
