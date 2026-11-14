package org.enthusia.tags.advancements;

import io.github.badgersmc.advancements.pilot.ProjectionService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/** REQ-1154: toast-only when the renderer supports it, full celebration otherwise. */
class CelebrationGatewayTest {
    private final Plugin plugin = mock(Plugin.class);
    private final Player player = mock(Player.class);

    /** Renderer built against pilot.6: only the four-argument call. */
    public static class OldRenderer implements ProjectionService {
        final List<String> calls = new ArrayList<>();
        @Override public void registerTree(Plugin owner, String namespace, ItemStack icon, List<Node> nodes) {}
        @Override public void removeTree(Plugin owner, String namespace) {}
        @Override public boolean ready(Player player) { return true; }
        @Override public void project(Plugin owner, String namespace, Player player, Map<String, Integer> progress) {}
        @Override public void celebrate(Plugin owner, String namespace, Player player, String key) { calls.add("full:" + key); }
    }

    /** Renderer from pilot.7: also offers the toast-only overload. */
    public static class NewRenderer extends OldRenderer {
        public void celebrate(Plugin owner, String namespace, Player player, String key, boolean announce) {
            calls.add((announce ? "full5:" : "toast:") + key);
        }
    }

    @Test
    void quietRewardsUseTheToastOnlyOverload() {
        NewRenderer renderer = new NewRenderer();
        CelebrationGateway gateway = new CelebrationGateway(renderer, plugin, Logger.getAnonymousLogger());
        assertTrue(gateway.supportsToastOnly());
        gateway.celebrate(player, "enthusia", "quiet", false);
        gateway.celebrate(player, "enthusia", "loud", true);
        assertEquals(List.of("toast:quiet", "full:loud"), renderer.calls);
    }

    @Test
    void olderRenderersFallBackToTheFullCelebration() {
        OldRenderer renderer = new OldRenderer();
        CelebrationGateway gateway = new CelebrationGateway(renderer, plugin, Logger.getAnonymousLogger());
        assertFalse(gateway.supportsToastOnly());
        gateway.celebrate(player, "enthusia", "quiet", false);
        gateway.celebrate(player, "enthusia", "quiet2", false);
        assertEquals(List.of("full:quiet", "full:quiet2"), renderer.calls);
    }
}
