package org.enthusia.tags.daily;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.time.LocalDate;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import org.bukkit.Server;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class DailyReminderTest {
    private static final LocalDate TODAY = LocalDate.of(2026, 10, 5);
    private static final String PERMISSION = "enthusia.tags.daily";
    private static final String ENABLED = "daily.discovery.enabled";

    @Test
    void disabledByDefaultAndPermissionRequired() {
        var fixture = new Fixture();
        fixture.reminder.join(fixture.player);
        verifyNoInteractions(fixture.scheduler);
        fixture.config.set(ENABLED, true);
        when(fixture.player.hasPermission(PERMISSION)).thenReturn(false);
        fixture.reminder.join(fixture.player);
        verifyNoInteractions(fixture.scheduler);
    }

    @Test
    void delayedOfferIsReadableAndOpensCommandWithoutClaiming() {
        var fixture = new Fixture();
        fixture.config.set(ENABLED, true);
        fixture.reminder.join(fixture.player);
        var delayed = ArgumentCaptor.forClass(Runnable.class);
        verify(fixture.scheduler).runTaskLater(eq(fixture.plugin), delayed.capture(), eq(2400L));
        delayed.getValue().run();
        fixture.answer.complete(Optional.of(new DailyReminder.Offer(TODAY, "5 Raw Gold")));
        var callback = ArgumentCaptor.forClass(Runnable.class);
        verify(fixture.scheduler).runTask(eq(fixture.plugin), callback.capture());
        callback.getValue().run();
        var message = ArgumentCaptor.forClass(Component.class);
        verify(fixture.player).sendMessage(message.capture());
        assertEquals(ClickEvent.runCommand("/daily"), message.getValue().clickEvent());
    }

    @Test
    void quitAndReloadSuppressPendingCallbacks() {
        var fixture = new Fixture();
        fixture.config.set(ENABLED, true);
        when(fixture.player.hasPlayedBefore()).thenReturn(true);
        fixture.reminder.join(fixture.player);
        var delayed = ArgumentCaptor.forClass(Runnable.class);
        verify(fixture.scheduler).runTaskLater(eq(fixture.plugin), delayed.capture(), eq(400L));
        delayed.getValue().run();
        fixture.reminder.clear();
        fixture.answer.complete(Optional.of(new DailyReminder.Offer(TODAY, "5 Raw Gold")));
        var callback = ArgumentCaptor.forClass(Runnable.class);
        verify(fixture.scheduler).runTask(eq(fixture.plugin), callback.capture());
        callback.getValue().run();
        verify(fixture.player, never()).sendMessage(any(Component.class));
    }

    @Test
    void unavailableOrOldDateOffersNeverSendMessages() {
        for (var offer : java.util.List.of(Optional.<DailyReminder.Offer>empty(),
            Optional.of(new DailyReminder.Offer(TODAY.minusDays(1), "5 Raw Gold")))) {
            var fixture = new Fixture();
            fixture.config.set(ENABLED, true);
            fixture.reminder.join(fixture.player);
            var delayed = ArgumentCaptor.forClass(Runnable.class);
            verify(fixture.scheduler).runTaskLater(eq(fixture.plugin), delayed.capture(), anyLong());
            delayed.getValue().run();
            fixture.answer.complete(offer);
            var callback = ArgumentCaptor.forClass(Runnable.class);
            verify(fixture.scheduler).runTask(eq(fixture.plugin), callback.capture());
            callback.getValue().run();
            verify(fixture.player, never()).sendMessage(any(Component.class));
        }
    }

    @Test
    void disconnectPermissionLossOrClaimCancellationSuppressesLoadedOffer() {
        for (int reason = 0; reason < 3; reason++) {
            var fixture = new Fixture();
            fixture.config.set(ENABLED, true);
            fixture.reminder.join(fixture.player);
            var delayed = ArgumentCaptor.forClass(Runnable.class);
            verify(fixture.scheduler).runTaskLater(eq(fixture.plugin), delayed.capture(), anyLong());
            delayed.getValue().run();
            if (reason == 0) when(fixture.player.isOnline()).thenReturn(false);
            if (reason == 1) when(fixture.player.hasPermission(PERMISSION)).thenReturn(false);
            if (reason == 2) fixture.reminder.remove(fixture.player.getUniqueId());
            fixture.answer.complete(Optional.of(new DailyReminder.Offer(TODAY, "5 Raw Gold")));
            var callback = ArgumentCaptor.forClass(Runnable.class);
            verify(fixture.scheduler).runTask(eq(fixture.plugin), callback.capture());
            callback.getValue().run();
            verify(fixture.player, never()).sendMessage(any(Component.class));
        }
    }

    private static final class Fixture {
        final JavaPlugin plugin = mock(JavaPlugin.class);
        final BukkitScheduler scheduler = mock(BukkitScheduler.class);
        final Player player = mock(Player.class);
        final YamlConfiguration config = new YamlConfiguration();
        final CompletableFuture<Optional<DailyReminder.Offer>> answer = new CompletableFuture<>();
        final DailyReminder reminder;
        Fixture() {
            var server = mock(Server.class);
            when(plugin.getServer()).thenReturn(server);
            when(server.getScheduler()).thenReturn(scheduler);
            when(plugin.getConfig()).thenReturn(config);
            when(plugin.isEnabled()).thenReturn(true);
            when(player.getUniqueId()).thenReturn(UUID.randomUUID());
            when(player.isOnline()).thenReturn(true);
            when(player.hasPermission(PERMISSION)).thenReturn(true);
            reminder = new DailyReminder(plugin, ignored -> answer, ignored -> true, () -> TODAY);
        }
    }
}
