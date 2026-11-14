package org.enthusia.tags.advancements;
import org.enthusia.tags.advancements.domain.ProviderRewardEvidence;
import org.junit.jupiter.api.Test;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;
class ProviderRewardEvidenceTest {
    @Test void completionRequiresKnownProviderMilestone() {
        var progress = new LinkedHashMap<String, Integer>();
        progress.put("express/read_all_about_it", 1000);
        progress.put("diary/first_entry", 1000);
        progress.put("warzone_duels/gladiator", 999);
        progress.put("reputation/not_a_real_milestone", 1000);
        progress.put("tags/1234", 1000);
        progress.put("express/first_class", null);
        assertEquals(Set.of("express/read_all_about_it", "diary/first_entry"), ProviderRewardEvidence.completed(progress));
        assertTrue(ProviderRewardEvidence.completed(null).isEmpty());
    }
    @Test void keysMatchStableCounterNamespaceAndRejectInventedMilestones() {
        assertEquals(38, ProviderRewardEvidence.keys().size());
        assertEquals("advancement_reward:express/first_class", ProviderRewardEvidence.counterKey(" Express/First_Class "));
        assertThrows(IllegalArgumentException.class, () -> ProviderRewardEvidence.counterKey("express/fake"));
    }
}
