package org.enthusia.tags.advancements;

import io.github.badgersmc.advancements.pilot.ProjectionService;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AdvancementNodeOrderTest {
    @Test void completeBundledGraphIsParentFirstEvenWhenInputIsReversed() throws Exception {
        var rewards = BundledRewardFixture.rewards();
        List<ProjectionService.Node> nodes = new ArrayList<>();
        for (String id : rewards.getKeys(false)) {
            var placement = AdvancementLayout.placement(id, null, 99, 99);
            String parent = placement.parentId() == null
                ? null
                : NativeAdvancementController.key(placement.parentId());
            nodes.add(new ProjectionService.Node(
                NativeAdvancementController.key(id),
                parent,
                id,
                List.of(),
                Material.STONE,
                "TASK",
                placement.x(),
                placement.y()));
        }
        nodes.addAll(WarzoneAdvancementBridge.nodes(29));
        nodes.addAll(CommendAdvancementBridge.nodes(35));
        nodes.addAll(ExpressAdvancementBridge.nodes(42));
        nodes.addAll(DiaryAdvancementBridge.nodes(49, 815002));
        Collections.reverse(nodes);

        var ordered = AdvancementNodeOrder.parentFirst(nodes);
        var positions = new HashMap<String, Integer>();
        for (int index = 0; index < ordered.size(); index++) {
            positions.put(ordered.get(index).key(), index);
        }
        for (ProjectionService.Node node : ordered) {
            if (node.parentKey() == null) continue;
            assertTrue(
                positions.get(node.parentKey()) < positions.get(node.key()),
                node.parentKey() + " must precede " + node.key());
        }
    }

    @Test void missingParentFailsBeforeProjectionRegistration() {
        var child = new ProjectionService.Node(
            "test/child",
            "test/missing",
            "Child",
            List.of(),
            Material.STONE,
            "TASK",
            1,
            1);

        var error = assertThrows(
            IllegalArgumentException.class,
            () -> AdvancementNodeOrder.parentFirst(List.of(child)));
        assertTrue(error.getMessage().contains("missing parent or cycle"));
    }
}
