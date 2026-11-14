package org.enthusia.tags.advancements;

import io.github.badgersmc.advancements.pilot.ProjectionService.Node;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.enthusia.tags.rewards.RewardCriterionType;
import org.enthusia.tags.rewards.RewardCriterion;
import org.enthusia.tags.rewards.RewardDefinition;
import org.enthusia.tags.rewards.RewardSourceType;

/** Infrastructure view only. RewardService remains the sole claim and payout owner. */
final class NativeMilestonePresentation {
    private final List<Node> nodes;
    private final Map<String, String> aliases;
    private final Set<String> visible;

    private NativeMilestonePresentation(List<Node> nodes, Map<String, String> aliases) {
        this.nodes = List.copyOf(nodes);
        this.aliases = Map.copyOf(aliases);
        visible = nodes.stream().map(Node::key).collect(Collectors.toUnmodifiableSet());
    }

    static NativeMilestonePresentation create(List<Node> source, Collection<RewardDefinition> rewards) {
        Set<String> available = source.stream().map(Node::key).collect(Collectors.toSet());
        Map<String, String> aliases = new HashMap<>();
        Map<String, List<String>> details = new HashMap<>();
        for (RewardDefinition reward : rewards) {
            String target = mirrorTarget(reward);
            String key = NativeAdvancementController.key(reward.getId());
            if (target == null || !available.contains(target) || !available.contains(key)) continue;
            aliases.put(key, target);
            details.computeIfAbsent(target, ignored -> new ArrayList<>())
                .addAll(NativeAdvancementController.description(reward));
        }
        List<Node> result = new ArrayList<>();
        for (Node node : source) {
            if (aliases.containsKey(node.key())) continue;
            List<String> description = new ArrayList<>(node.description());
            if (details.containsKey(node.key())) {
                description.remove("§7Rewards: None (advancement only).");
                description.addAll(details.get(node.key()));
            }
            result.add(new Node(node.key(), aliases.getOrDefault(node.parentKey(), node.parentKey()),
                node.title(), description, node.icon(), node.customModelData(), node.itemModel(),
                node.frame(), node.x(), node.y()));
        }
        return new NativeMilestonePresentation(result, aliases);
    }

    private static String mirrorTarget(RewardDefinition reward) {
        if (reward.getCriteria().size() != 1) return null;
        var criterion = reward.getCriteria().getFirst();
        String key = criterion.getKey();
        String prefix = "advancement_reward:";
        if (!providerCounter(criterion) || key == null || !key.startsWith(prefix)) return null;
        String target = key.substring(prefix.length());
        return providerTarget(target) ? target : null;
    }

    private static boolean providerTarget(String target) {
        return target.startsWith("diary/") || target.startsWith("express/")
            || target.startsWith("reputation/") || target.startsWith("warzone_duels/");
    }

    private static boolean providerCounter(RewardCriterion criterion) {
        return criterion.isValid() && criterion.getType() == RewardCriterionType.CUSTOM_COUNTER
            && (criterion.getSourceType() == RewardSourceType.LEGACY
                || criterion.getSourceType() == RewardSourceType.CUSTOM_COUNTER)
            && criterion.getAmount() == 1;
    }

    List<Node> nodes() { return nodes; }

    boolean ownsCelebration(String key) { return visible.contains(key); }

    Map<String, Integer> progress(Map<String, Integer> original) {
        Map<String, Integer> result = new LinkedHashMap<>();
        original.forEach((key, value) -> {
            String target = aliases.getOrDefault(key, key);
            if (visible.contains(target)) result.merge(target, value, Math::max);
        });
        return result;
    }
}
