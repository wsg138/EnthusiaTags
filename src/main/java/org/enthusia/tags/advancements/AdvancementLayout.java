package org.enthusia.tags.advancements;

import java.util.HashMap;
import java.util.Map;

/** Fixed visual layout for the built-in Enthusia advancement catalog. */
final class AdvancementLayout {
    record Placement(String parentId, int x, int y) {}

    private static final Map<String, Placement> FIXED = build();
    private static final int WARZONE_BASE_Y = 29;

    private AdvancementLayout() {
    }

    static Placement placement(
        String rewardId,
        String fallbackParentId,
        int fallbackX,
        int fallbackY
    ) {
        return FIXED.getOrDefault(
            rewardId,
            new Placement(fallbackParentId, fallbackX, fallbackY)
        );
    }

    static boolean hasFixed(String rewardId) {
        return FIXED.containsKey(rewardId);
    }
    static int warzoneBaseY(int categoryCount) {
        return Math.max(WARZONE_BASE_Y, categoryCount * 4 + 1);
    }

    static int reputationBaseY(int categoryCount, boolean hasWarzone) {
        return warzoneBaseY(categoryCount) + (hasWarzone ? 6 : 0);
    }

    static int expressBaseY(
        int categoryCount,
        boolean hasWarzone,
        boolean hasReputation
    ) {
        int naturalBase = Math.max(WARZONE_BASE_Y, categoryCount * 4 + 1);
        if (hasReputation) {
            return reputationBaseY(categoryCount, hasWarzone) + 7;
        }
        if (hasWarzone) {
            return warzoneBaseY(categoryCount) + 6;
        }
        return naturalBase;
    }

    static int diaryBaseY(
        int categoryCount,
        boolean hasWarzone,
        boolean hasReputation,
        boolean hasExpress
    ) {
        if (hasExpress) {
            return expressBaseY(categoryCount, hasWarzone, hasReputation) + 7;
        }
        if (hasReputation) {
            return reputationBaseY(categoryCount, hasWarzone) + 7;
        }
        if (hasWarzone) {
            return warzoneBaseY(categoryCount) + 6;
        }
        return Math.max(WARZONE_BASE_Y, categoryCount * 4 + 1);
    }

    private static Map<String, Placement> build() {
        Map<String, Placement> map = new HashMap<>();

        // Playtime: early unlocks, long-term milestones, and session/special branches.
        root(map, "first_hour", 1, 1);
        chain(map, "first_hour", 2, 1, "payday");
        put(map, "rep_unlock", "payday", 3, 0);
        put(map, "market_access", "payday", 3, 2);
        put(map, "regular_player", "payday", 4, 1);
        chain(map, "regular_player", 5, 1,
            "block_game_addict", "dedicated_player", "no_life",
            "grass_never", "touch_grass_failed", "my_life_now",
            "server_home", "thousand_hour_club", "two_thousand_hours");
        chain(map, "regular_player", 5, 0,
            "marathon_session", "sleeps_in_minecraft");
        put(map, "deep_dweller", "regular_player", 5, 2);

        // Mining: core progression with separate overworld ore and Nether branches.
        root(map, "starter_pack", 1, 5);
        chain(map, "starter_pack", 2, 5,
            "tree_puncher", "dirt_collector", "stone_age", "deepslate_dedication");
        put(map, "industrial_lumberjack", "tree_puncher", 3, 6);
        chain(map, "stone_age", 5, 4,
            "coal_miner", "deep_coal_miner", "iron_deficiency", "lapis_hoarder",
            "redstone_engineer", "deep_redstone_engineer", "gold_rush",
            "deep_gold_rush", "diamond_hands", "emerald_hunter");
        put(map, "yearn_for_mines", "stone_age", 5, 6);
        chain(map, "yearn_for_mines", 6, 6,
            "ancient_debris_hunter", "nether_enjoyer", "nether_quarry", "obsidian_breaker");

        // Combat: main PvP mastery, specialist kills, and conditional/ranged feats.
        root(map, "first_blood", 1, 9);
        chain(map, "first_blood", 2, 9,
            "km_gg_reward", "kill_sparks_reward", "no_mercy", "hunter", "blooded",
            "seasoned", "battle_hardened", "warpath", "unstoppable", "elite_hunter");
        chain(map, "hunter", 6, 8,
            "silent_killer", "already_dead", "rampage", "untouchable",
            "death_sentence", "executioner", "checkmate");
        chain(map, "hunter", 6, 10,
            "armor_breaker", "not_even_close", "clutch_master",
            "proj_spark_reward", "arrow_storm", "deadeye");

        // Deaths: general respawn milestones, PvP deaths, and environmental mishaps.
        root(map, "first_respawn", 1, 13);
        chain(map, "first_respawn", 2, 13,
            "fear_me", "fallen", "very_experienced", "respawn_enjoyer",
            "unkillable_spirit", "refuses_to_quit", "professional_respawner");
        chain(map, "first_respawn", 3, 12,
            "target_practice", "personal_problem", "rent_free", "nemesis");
        chain(map, "first_respawn", 3, 14,
            "gravity_victim", "skill_issue", "physics_hard", "terminal_velocity");
        chain(map, "terminal_velocity", 7, 14,
            "lava_learner", "lava_allergy", "i_swear_it_worked",
            "boom_magnet", "chicken_incident");

        // Economy: wealth progression with a competitive baltop offshoot.
        root(map, "first_thousand", 1, 17);
        chain(map, "first_thousand", 2, 17,
            "comfortable", "wealthy", "high_roller", "quarter_million", "millionaire");
        put(map, "baltop_top3", "wealthy", 4, 16);

        // Exploration: a clean escalating travel chain.
        root(map, "getting_around", 1, 21);
        chain(map, "getting_around", 2, 21,
            "trail_starter", "long_walk", "world_walker",
            "million_steps", "ten_million_steps");

        // Misc: short prestige line.
        root(map, "lag_was_crazy", 1, 25);
        chain(map, "lag_was_crazy", 2, 25,
            "all_rounder", "server_legend", "ultimate_survivor");

        return Map.copyOf(map);
    }
    private static void root(
        Map<String, Placement> map,
        String id,
        int x,
        int y
    ) {
        put(map, id, null, x, y);
    }

    private static void chain(
        Map<String, Placement> map,
        String parentId,
        int startX,
        int y,
        String... ids
    ) {
        String parent = parentId;
        int x = startX;
        for (String id : ids) {
            put(map, id, parent, x++, y);
            parent = id;
        }
    }

    private static void put(
        Map<String, Placement> map,
        String id,
        String parentId,
        int x,
        int y
    ) {
        map.put(id, new Placement(parentId, x, y));
    }
}
