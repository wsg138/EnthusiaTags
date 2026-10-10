package org.enthusia.tags.rewards;

import java.util.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AdvancementCategoriesTest {
    @Test void commendRewardsAreFilteredByProviderWithoutChangingClaimIdentity() {
        var criterion = new RewardCriterion(RewardCriterionType.CUSTOM_COUNTER,1,null,"advancement_reward:reputation/a_good_word",0,"Commend");
        var reward = new RewardDefinition("adv_commend_a_good_word","A Good Word",List.of(),null,List.of(criterion),List.of(),"advancements");
        var row = new RewardMenuModel.Entry(reward,new RewardEvaluation(RewardStatus.LOCKED,Map.of(),false,false,"Requirements not reached"),List.of(),0,0);
        assertEquals(List.of(row),RewardMenuModel.select(List.of(row),RewardMenuState.category("advancements/commend")));
        assertTrue(RewardMenuModel.select(List.of(row),RewardMenuState.category("advancements/express")).isEmpty());
        assertEquals("advancements",reward.getCategory());
        assertEquals("adv_commend_a_good_word",row.id());
    }
    @Test void defaultRootCategoriesIncludeProductionCatalog() throws Exception {
        try(var reader=new java.io.InputStreamReader(getClass().getResourceAsStream("/rewards.yml"))) {
            var config=YamlConfiguration.loadConfiguration(reader);
            assertTrue(config.contains("categories.advancements"));
            assertTrue(config.contains("categories.supporter"));
            assertTrue(config.contains("categories.legacy"));
            assertTrue(config.contains("categories.events"));

        }
    }
}
