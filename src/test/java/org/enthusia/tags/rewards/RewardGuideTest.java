package org.enthusia.tags.rewards;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Server;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.enthusia.tags.advancements.domain.GoldEligibility;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class RewardGuideTest {
    private static final String PERMISSION = "enthusia.tags.rewards";
    private final JavaPlugin plugin = mock(JavaPlugin.class);
    private final RewardService service = mock(RewardService.class);
    private final Player player = mock(Player.class);
    private final Server server = mock(Server.class);
    private final BukkitScheduler scheduler = mock(BukkitScheduler.class);
    private final YamlConfiguration config = new YamlConfiguration();
    private final List<Component> messages = new ArrayList<>();
    private final List<Runnable> callbacks = new ArrayList<>();
    private final UUID id = UUID.randomUUID();
    private final RewardDefinition goal = new RewardDefinition("goal", "A goal", List.of(), null, List.of(),
        List.of(new RewardAction("gold", RewardActionType.MONEY, "", 100, "Gold", null, 0, null, List.of(), true),
            new RewardAction("gift", RewardActionType.TAG, "tag", 0, "A tag", null, 0, null, List.of(), true)), "mining");

    private RewardGuide ready() {
        config.set("rewards.guide.enabled", true);
        config.set("rewards.guide.paths.build", List.of("goal"));
        when(plugin.getConfig()).thenReturn(config);
        when(plugin.getServer()).thenReturn(server);
        when(server.getScheduler()).thenReturn(scheduler);
        when(server.getPlayer(id)).thenReturn(player);
        when(player.getUniqueId()).thenReturn(id);
        when(player.isOnline()).thenReturn(true);
        when(player.hasPermission(PERMISSION)).thenReturn(true);
        when(service.isAvailable()).thenReturn(true);
        when(service.getRewards()).thenReturn(Map.of("goal", goal));
        when(service.isGuideGoalReady(player, goal)).thenReturn(true);
        when(service.formatAction(goal.getActions().get(0))).thenReturn("100 Gold");
        when(service.formatAction(goal.getActions().get(1))).thenReturn("A tag");
        doAnswer(call -> { messages.add(call.getArgument(0)); return null; }).when(player).sendMessage(any(Component.class));
        doAnswer(call -> { callbacks.add(call.getArgument(1)); return null; }).when(scheduler).runTask(eq(plugin), any(Runnable.class));
        return new RewardGuide(plugin, service);
    }

    @Test void choicesNeverPreviewOrClaimAndRespectPermissionAndDisabledConfig() {
        RewardGuide guide = ready();
        guide.open(player, null);
        assertEquals(4, messages.size());
        assertEquals(ClickEvent.runCommand("/rewards guide build"), messages.get(1).clickEvent());
        verify(service, never()).previewGuideGold(any(), any());
        when(player.hasPermission(PERMISSION)).thenReturn(false);
        assertFalse(guide.allowed(player));
        when(player.hasPermission(PERMISSION)).thenReturn(true);
        config.set("rewards.guide.enabled", false);
        assertFalse(guide.allowed(player));
        verify(service, never()).claimAsync(any(Player.class), any(RewardDefinition.class));
    }

    @Test void blockedAndUnknownGoldKeepOtherComponentsAndOpenOrdinaryMenuOnly() {
        for (GoldEligibility gold : List.of(GoldEligibility.BLOCKED, GoldEligibility.UNKNOWN)) {
            RewardGuide guide = ready();
            messages.clear(); callbacks.clear();
            when(service.previewGuideGold(player, goal)).thenReturn(CompletableFuture.completedFuture(gold));
            guide.open(player, "build");
            callbacks.get(0).run();
            String text = messages.stream().map(PlainTextComponentSerializer.plainText()::serialize).reduce("", String::concat);
            assertFalse(text.contains("100 Gold"));
            assertTrue(text.contains("A tag"));
            assertEquals(ClickEvent.runCommand("/rewards open goal"), messages.getLast().clickEvent());
        }
        verify(service, never()).claimAsync(any(Player.class), any(RewardDefinition.class));
    }

    @Test void callbacksSuppressReconnectReloadAndChangedRewardState() {
        RewardGuide guide = ready();
        assertTrue(guide.canRender(player, player, 0, goal));
        assertFalse(guide.canRender(mock(Player.class), player, 0, goal));
        assertFalse(guide.canRender(null, player, 0, goal));
        when(service.getGuideGeneration()).thenReturn(1L);
        assertFalse(guide.canRender(player, player, 0, goal));
        when(service.getGuideGeneration()).thenReturn(0L);
        when(service.isGuideGoalReady(player, goal)).thenReturn(false);
        assertFalse(guide.canRender(player, player, 0, goal));
        when(service.isGuideGoalReady(player, goal)).thenReturn(true);
        when(player.isOnline()).thenReturn(false);
        assertFalse(guide.canRender(player, player, 0, goal));
    }

    @Test void singleFlightAndStaleAsyncResultNeverShowGoal() {
        RewardGuide guide = ready();
        var result = new CompletableFuture<GoldEligibility>();
        when(service.previewGuideGold(player, goal)).thenReturn(result);
        guide.open(player, "build"); guide.open(player, "build");
        verify(service, times(1)).previewGuideGold(player, goal);
        config.set("rewards.guide.enabled", false);
        result.complete(GoldEligibility.ALLOWED);
        callbacks.get(0).run();
        assertEquals(1, messages.size());
    }

    @Test void unavailableGoalDoesNotStartPreview() {
        RewardGuide guide = ready();
        when(service.isGuideGoalReady(player, goal)).thenReturn(false);
        guide.open(player, "build");
        verify(service, never()).previewGuideGold(any(), any());
        assertEquals(1, messages.size());
    }

    @Test void allowedGoldShowsConfiguredAmountWithoutPayingOrOpeningInventory() {
        RewardGuide guide = ready();
        when(service.previewGuideGold(player, goal)).thenReturn(CompletableFuture.completedFuture(GoldEligibility.ALLOWED));
        guide.open(player, "build");
        callbacks.get(0).run();
        String text = messages.stream().map(PlainTextComponentSerializer.plainText()::serialize).reduce("", String::concat);
        assertTrue(text.contains("100 Gold"));
        assertTrue(text.contains("checked again when you claim"));
        verify(service, never()).claimAsync(any(Player.class), any(RewardDefinition.class));
        verify(player, never()).openInventory(any(org.bukkit.inventory.Inventory.class));
    }
    @Test void providerGoalExplainsConfiguredActionBeforeManualNavigation() {
        RewardGuide guide = ready();
        var provider = new RewardDefinition("provider", "Read All About It",
            List.of("&7Read your first received letter."), null, List.of(), goal.getActions(), "advancements");
        config.set("rewards.guide.paths.social", List.of("provider"));
        when(service.getRewards()).thenReturn(Map.of("provider", provider));
        when(service.isGuideGoalReady(player, provider)).thenReturn(true);
        when(service.previewGuideGold(player, provider)).thenReturn(CompletableFuture.completedFuture(GoldEligibility.UNKNOWN));
        guide.open(player, "social");
        callbacks.getFirst().run();
        String text = messages.stream().map(PlainTextComponentSerializer.plainText()::serialize).reduce("", String::concat);
        assertTrue(text.contains("Read your first received letter."));
        assertFalse(text.contains("100 Gold"));
        assertEquals(ClickEvent.runCommand("/rewards open provider"), messages.getLast().clickEvent());
        verify(service, never()).claimAsync(any(Player.class), any(RewardDefinition.class));
    }
}
