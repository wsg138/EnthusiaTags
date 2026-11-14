package org.enthusia.tags.rewards;

import static org.mockito.Mockito.*;
import java.util.List;
import java.util.Map;
import org.bukkit.Material;
import org.bukkit.command.Command;
import org.bukkit.entity.Player;
import org.enthusia.tags.EnthusiaTagsPlugin;
import org.enthusia.tags.Messages;
import org.enthusia.tags.TagService;
import org.junit.jupiter.api.Test;

class PrivateRewardCommandTest {
    @Test void directOpenCannotRevealUnearnedReward() {
        var plugin = mock(EnthusiaTagsPlugin.class);
        when(plugin.getName()).thenReturn("EnthusiaTags"); when(plugin.namespace()).thenReturn("enthusiatags");
        var tags = mock(TagService.class); when(tags.getPlugin()).thenReturn(plugin);
        var service = mock(RewardService.class); when(service.isAvailable()).thenReturn(true);
        var secret = new RewardDefinition("private", "Private", List.of(), Material.PAPER, List.of(), List.of(), "misc", RewardCompletionMode.LATCHED).withHiddenUntilEarned(true);
        when(service.getRewards()).thenReturn(Map.of("private", secret));
        var player = mock(Player.class); when(service.canReveal(player, secret)).thenReturn(false);
        var command = new RewardsCommand(service, tags, mock(Messages.class), plugin);
        command.onCommand(player, mock(Command.class), "rewards", new String[]{"open", "private"});
        verify(player, never()).openInventory(any(org.bukkit.inventory.Inventory.class));
        verify(player).sendMessage(net.kyori.adventure.text.Component.text("Unknown reward."));
    }
}
