package org.enthusia.tags.advancements;

import io.github.badgersmc.advancements.pilot.ProjectionService.Node;
import java.util.Collection;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.meta.ItemMeta;
import org.enthusia.tags.rewards.RewardDefinition;

/** Presentation only: never changes reward identity, progress or claim state. */
final class NativeTabPresentation {
    private NativeTabPresentation() { }

    static void applyLogo(ItemMeta meta, int customModelData, String itemModel) {
        meta.setCustomModelData(customModelData);
        if (itemModel == null || itemModel.isBlank()) return;
        NamespacedKey key = NamespacedKey.fromString(itemModel);
        if (key == null) throw new IllegalArgumentException("Invalid advancement logo item model");
        meta.setItemModel(key);
    }

    static List<Node> organize(List<Node> nodes, Collection<RewardDefinition> rewards) {
        Map<String, Node> source = new HashMap<>();
        nodes.forEach(node -> source.put(node.key(), node));
        Map<String, Node> result = new LinkedHashMap<>();
        Set<String> dynamic = new HashSet<>();
        rewards.stream().filter(r -> !AdvancementLayout.hasFixed(r.getId()))
            .forEach(r -> dynamic.add(NativeAdvancementController.key(r.getId())));
        reserveFixed(nodes, dynamic, result);
        placeProviders(nodes, result);
        Map<String, List<RewardDefinition>> remaining = placeRewardCards(source, result, dynamic, rewards);
        placeRemaining(source, result, remaining);
        return nodes.stream().map(node -> result.get(node.key())).toList();
    }

    private static void reserveFixed(List<Node> nodes, Set<String> dynamic, Map<String, Node> result) {
        // Reserve fixed branches. Provider cards use separate rows so native connectors
        // never imply that claiming a reward is a prerequisite for the next achievement.
        for (Node node : nodes) {
            if (!dynamic.contains(node.key()) && !provider(node.key())) result.put(node.key(), node);
        }
    }

    private static void placeProviders(List<Node> nodes, Map<String, Node> result) {
        Map<String, List<Node>> providers = new LinkedHashMap<>();
        for (Node node : nodes) {
            if (provider(node.key())) providers.computeIfAbsent(node.key().split("/", 2)[0], ignored -> new ArrayList<>()).add(node);
        }
        float providerY = Math.max(29, (float) result.values().stream().mapToDouble(Node::y).max().orElse(0) + 4);
        for (List<Node> group : providers.values()) {
            float min = (float) group.stream().mapToDouble(Node::y).min().orElseThrow();
            float max = (float) group.stream().mapToDouble(Node::y).max().orElseThrow();
            for (Node node : group) result.put(node.key(), move(node, node.parentKey(), node.x() * 2, providerY + (node.y() - min) * 2));
            providerY += (max - min) * 2 + 4;
        }
    }

    private static Map<String, List<RewardDefinition>> placeRewardCards(
        Map<String, Node> source, Map<String, Node> result, Set<String> dynamic,
        Collection<RewardDefinition> rewards
    ) {
        Set<String> occupied = new HashSet<>();
        result.values().forEach(node -> occupied.add(cell(node.x(), node.y())));
        Map<String, List<RewardDefinition>> remaining = new TreeMap<>();
        List<RewardDefinition> ordered = rewards.stream().sorted(Comparator.comparing(RewardDefinition::getId)).toList();
        for (RewardDefinition reward : ordered) {
            String key = NativeAdvancementController.key(reward.getId());
            if (!dynamic.contains(key)) continue;
            Node original = source.get(key);
            if (original == null) continue;
            String parent = providerKey(reward);
            Node anchor = parent == null ? null : result.get(parent);
            if (anchor == null) {
                remaining.computeIfAbsent(reward.getCategory(), ignored -> new ArrayList<>()).add(reward);
            } else {
                float x = anchor.x() + 1;
                float y = anchor.y() + 1;
                while (!occupied.add(cell(x, y))) y += 2;
                result.put(key, move(original, parent, x, y));
            }
        }
        return remaining;
    }

    private static void placeRemaining(
        Map<String, Node> source, Map<String, Node> result,
        Map<String, List<RewardDefinition>> remaining
    ) {
        float baseY = (float) Math.ceil(result.values().stream().mapToDouble(Node::y).max().orElse(0)) + 3;
        for (List<RewardDefinition> group : remaining.values()) {
            String root = NativeAdvancementController.key(group.get(0).getId());
            String previous = null;
            for (int i = 0; i < group.size(); i++) {
                String key = NativeAdvancementController.key(group.get(i).getId());
                String parent = fallbackParent(i, root, previous);
                result.put(key, move(source.get(key), parent, i % 4 + 1, baseY + i / 4 * 2));
                previous = key;
            }
            baseY += ((group.size() + 3) / 4) * 2 + 2;
        }
    }

    private static String fallbackParent(int index, String root, String previous) {
        if (index == 0) return null;
        return index % 4 == 0 ? root : previous;
    }

    private static String providerKey(RewardDefinition reward) {
        if (reward.getCriteria().size() != 1) return null;
        String key = reward.getCriteria().get(0).getKey();
        String prefix = "advancement_reward:";
        if (key == null || !key.startsWith(prefix)) return null;
        String target = key.substring(prefix.length());
        return provider(target) ? target : null;
    }

    private static boolean provider(String key) {
        return key.startsWith("reputation/") || key.startsWith("express/")
            || key.startsWith("diary/") || key.startsWith("warzone_duels/");
    }

    private static String cell(float x, float y) { return x + ":" + y; }

    private static Node move(Node node, String parent, float x, float y) {
        return new Node(node.key(), parent, node.title(), node.description(), node.icon(),
            node.customModelData(), node.itemModel(), node.frame(), x, y);
    }
}
