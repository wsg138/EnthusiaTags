package org.enthusia.tags.advancements;

import io.github.badgersmc.advancements.pilot.ProjectionService;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Orders projected advancement definitions so every parent is registered first. */
final class AdvancementNodeOrder {
    private AdvancementNodeOrder() {
    }

    static List<ProjectionService.Node> parentFirst(List<ProjectionService.Node> nodes) {
        Map<String, ProjectionService.Node> remaining = new LinkedHashMap<>();
        for (ProjectionService.Node node : nodes) {
            if (remaining.putIfAbsent(node.key(), node) != null) {
                throw new IllegalArgumentException("Duplicate advancement key: " + node.key());
            }
        }

        List<ProjectionService.Node> ordered = new ArrayList<>(nodes.size());
        Set<String> emitted = new HashSet<>();
        while (!remaining.isEmpty()) {
            boolean progressed = false;
            var iterator = remaining.entrySet().iterator();
            while (iterator.hasNext()) {
                var entry = iterator.next();
                ProjectionService.Node node = entry.getValue();
                String parent = node.parentKey();
                if (parent == null || emitted.contains(parent)) {
                    ordered.add(node);
                    emitted.add(node.key());
                    iterator.remove();
                    progressed = true;
                }
            }

            if (!progressed) {
                String blocked = remaining.values().stream()
                    .map(node -> node.key() + " -> " + node.parentKey())
                    .findFirst()
                    .orElse("unknown");
                throw new IllegalArgumentException(
                    "Advancement graph has a missing parent or cycle: " + blocked);
            }
        }

        return List.copyOf(ordered);
    }
}
