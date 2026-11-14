package org.enthusia.tags.rewards;

import java.util.List;
import org.bukkit.Material;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** REQ-1154: only an explicit boolean false makes a reward toast-only. */
class AnnounceCompletionFlagTest {
    @Test
    void onlyExplicitFalseSilencesTheCelebration() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("quiet.announce-completion", false);
        yaml.set("loud.announce-completion", true);
        yaml.set("typo.announce-completion", "no");
        yaml.set("plain.name", "Plain");
        assertFalse(RewardService.announceCompletionFlag(yaml, "quiet"));
        assertTrue(RewardService.announceCompletionFlag(yaml, "loud"));
        assertTrue(RewardService.announceCompletionFlag(yaml, "typo"));
        assertTrue(RewardService.announceCompletionFlag(yaml, "plain"));
    }

    @Test
    void definitionsDefaultToAnnouncingAndKeepOtherFlags() {
        RewardDefinition base = new RewardDefinition("r", "R", List.of(), Material.PAPER, List.of(), List.of(), "misc");
        assertTrue(base.isAnnounceCompletion());
        RewardDefinition quietHidden = base.withHiddenUntilEarned(true).withAnnounceCompletion(false);
        assertFalse(quietHidden.isAnnounceCompletion());
        assertTrue(quietHidden.isHiddenUntilEarned());
        RewardDefinition modelled = quietHidden.withIconModel("enthusia:prestige_badge_1");
        assertFalse(modelled.isAnnounceCompletion());
        assertEquals("enthusia:prestige_badge_1", modelled.getIconModel());
        assertFalse(modelled.withAnnounceCompletion(true).getIconModel() == null);
        RewardDefinition stillQuiet = quietHidden.withHiddenUntilEarned(false);
        assertFalse(stillQuiet.isAnnounceCompletion());
        assertFalse(stillQuiet.isHiddenUntilEarned());
    }
}
