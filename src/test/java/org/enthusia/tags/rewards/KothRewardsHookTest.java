package org.enthusia.tags.rewards;

import org.bukkit.Server;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.bukkit.plugin.ServicesManager;
import org.bukkit.plugin.ServicePriority;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class KothRewardsHookTest {
    /** Class-loader seam models the provider-owned API without bundling it in Tags. */
    public interface Menu { boolean open(UUID player, String page); }
    @Test void enabledOwnerOpensOnlyTheCallerAndReplacementIsResolvedFresh() {
        Server server = mock(Server.class); PluginManager plugins = mock(PluginManager.class);
        ServicesManager services = mock(ServicesManager.class); Plugin owner = mock(Plugin.class);
        Player player = mock(Player.class); UUID id = UUID.randomUUID();
        when(server.getPluginManager()).thenReturn(plugins); when(server.getServicesManager()).thenReturn(services);
        when(plugins.getPlugin("EnthusiaKOTH")).thenReturn(owner); when(owner.isEnabled()).thenReturn(true);
        when(player.isOnline()).thenReturn(true); when(player.getUniqueId()).thenReturn(id);
        AtomicReference<Menu> selected = new AtomicReference<>(mock(Menu.class));
        Menu first = selected.get(); when(first.open(id, "challenges")).thenReturn(true);
        when(services.getRegistration(Menu.class)).thenAnswer(call -> new RegisteredServiceProvider<>(Menu.class, selected.get(), ServicePriority.Normal, owner));
        KothRewardsHook hook = new KothRewardsHook(server, ignored -> Menu.class);
        assertTrue(hook.open(player, "challenges")); verify(first).open(id, "challenges");
        Menu replacement = mock(Menu.class); selected.set(replacement); when(replacement.open(id, "claims")).thenReturn(true);
        assertTrue(hook.open(player, "claims")); verify(replacement).open(id, "claims");
        when(owner.isEnabled()).thenReturn(false); assertFalse(hook.open(player, "claims"));
        verifyNoMoreInteractions(replacement);
    }
    @Test void missingWrongOwnerOfflineUnknownAndLinkageFailuresCannotOpen() {
        Server server = mock(Server.class); PluginManager plugins = mock(PluginManager.class);
        ServicesManager services = mock(ServicesManager.class); Player player = mock(Player.class);
        when(server.getPluginManager()).thenReturn(plugins); when(server.getServicesManager()).thenReturn(services);
        KothRewardsHook hook = new KothRewardsHook(server, ignored -> Menu.class);
        assertFalse(hook.open(player, "home"));
        Plugin owner = mock(Plugin.class); when(plugins.getPlugin("EnthusiaKOTH")).thenReturn(owner);
        when(owner.isEnabled()).thenReturn(true); when(player.isOnline()).thenReturn(true);
        Menu provider = mock(Menu.class);
        when(services.getRegistration(Menu.class)).thenReturn(new RegisteredServiceProvider<>(Menu.class, provider, ServicePriority.Normal, mock(Plugin.class)));
        assertFalse(hook.open(player, "home")); verifyNoInteractions(provider);
        when(services.getRegistration(Menu.class)).thenReturn(new RegisteredServiceProvider<>(Menu.class, provider, ServicePriority.Normal, owner));
        assertFalse(hook.open(player, "grant")); when(player.isOnline()).thenReturn(false);
        assertFalse(hook.open(player, "home")); verifyNoInteractions(provider);
        when(player.isOnline()).thenReturn(true);
        when(provider.open(any(), eq("home"))).thenThrow(new NoClassDefFoundError("reloaded"));
        assertFalse(hook.open(player, "home"));
    }
}
