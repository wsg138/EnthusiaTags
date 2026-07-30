package org.enthusia.tags;

import java.util.List;
import org.bukkit.configuration.file.YamlConfiguration;

final class RewardConfigV5Migration {
    private static final List<MoneyMigration> MONEY_MIGRATIONS = List.of(
        new MoneyMigration("rewards.first_hour.rewards.payout", List.of(5D), 50D),
        new MoneyMigration("rewards.payday.rewards.r1", List.of(15D, 200D, 500D), 150D),
        new MoneyMigration("rewards.regular_player.rewards.payout", List.of(20D), 250D),
        new MoneyMigration("rewards.block_game_addict.rewards.r2", List.of(30D, 250D), 500D),
        new MoneyMigration("rewards.dedicated_player.rewards.payout", List.of(50D), 750D),
        new MoneyMigration("rewards.thousand_hour_club.rewards.payout", List.of(250D), 3000D),
        new MoneyMigration("rewards.two_thousand_hours.rewards.payout", List.of(500D), 5000D),
        new MoneyMigration("rewards.marathon_session.rewards.payout", List.of(20D), 250D),
        new MoneyMigration("rewards.deep_dweller.rewards.payout", List.of(75D), 500D),
        new MoneyMigration("rewards.deepslate_dedication.rewards.payout", List.of(100D), 1500D),
        new MoneyMigration("rewards.coal_miner.rewards.payout", List.of(10D), 150D),
        new MoneyMigration("rewards.deep_coal_miner.rewards.payout", List.of(15D), 250D),
        new MoneyMigration("rewards.lapis_hoarder.rewards.payout", List.of(15D), 250D),
        new MoneyMigration("rewards.redstone_engineer.rewards.payout", List.of(25D), 500D),
        new MoneyMigration("rewards.deep_redstone_engineer.rewards.payout", List.of(30D), 650D),
        new MoneyMigration("rewards.gold_rush.rewards.payout", List.of(20D), 400D),
        new MoneyMigration("rewards.deep_gold_rush.rewards.payout", List.of(25D), 500D),
        new MoneyMigration("rewards.diamond_hands.rewards.r2", List.of(25D, 200D, 500D), 750D),
        new MoneyMigration("rewards.emerald_hunter.rewards.payout", List.of(25D), 500D),
        new MoneyMigration("rewards.ancient_debris_hunter.rewards.payout", List.of(50D), 1000D),
        new MoneyMigration("rewards.nether_quarry.rewards.payout", List.of(50D), 1250D),
        new MoneyMigration("rewards.obsidian_breaker.rewards.payout", List.of(50D), 1000D),
        new MoneyMigration("rewards.first_blood.rewards.payout", List.of(5D), 50D),
        new MoneyMigration("rewards.hunter.rewards.payout", List.of(20D), 300D),
        new MoneyMigration("rewards.warpath.rewards.r2", List.of(75D, 1000D), 2000D),
        new MoneyMigration("rewards.elite_hunter.rewards.payout", List.of(250D), 4000D),
        new MoneyMigration("rewards.rampage.rewards.payout", List.of(75D), 1500D),
        new MoneyMigration("rewards.untouchable.rewards.payout", List.of(150D), 3000D),
        new MoneyMigration("rewards.executioner.rewards.payout", List.of(50D), 1000D),
        new MoneyMigration("rewards.armor_breaker.rewards.payout", List.of(30D), 750D),
        new MoneyMigration("rewards.clutch_master.rewards.payout", List.of(30D), 750D),
        new MoneyMigration("rewards.arrow_storm.rewards.payout", List.of(10D), 200D),
        new MoneyMigration("rewards.deadeye.rewards.payout", List.of(40D), 750D),
        new MoneyMigration("rewards.professional_respawner.rewards.payout", List.of(50D), 1000D),
        new MoneyMigration("rewards.terminal_velocity.rewards.payout", List.of(10D), 250D),
        new MoneyMigration("rewards.first_thousand.rewards.payout", List.of(5D), 50D),
        new MoneyMigration("rewards.comfortable.rewards.payout", List.of(10D), 100D),
        new MoneyMigration("rewards.wealthy.rewards.payout", List.of(20D), 250D),
        new MoneyMigration("rewards.high_roller.rewards.r2", List.of(40D, 2500D), 500D),
        new MoneyMigration("rewards.quarter_million.rewards.payout", List.of(75D), 750D),
        new MoneyMigration("rewards.millionaire.rewards.payout", List.of(150D), 1500D),
        new MoneyMigration("rewards.long_walk.rewards.payout", List.of(15D), 250D),
        new MoneyMigration("rewards.world_walker.rewards.payout", List.of(40D), 750D),
        new MoneyMigration("rewards.million_steps.rewards.payout", List.of(75D), 1500D),
        new MoneyMigration("rewards.ten_million_steps.rewards.payout", List.of(250D), 5000D),
        new MoneyMigration("rewards.all_rounder.rewards.payout", List.of(100D), 1250D),
        new MoneyMigration("rewards.server_legend.rewards.payout", List.of(300D), 3500D),
        new MoneyMigration("rewards.ultimate_survivor.rewards.payout", List.of(500D), 5000D)
    );
    private static final List<PhysicalGoldMigration> PHYSICAL_GOLD_MIGRATIONS = List.of(
        new PhysicalGoldMigration("rewards.gold_rush.rewards.item", "GOLD_INGOT", 16),
        new PhysicalGoldMigration("rewards.deep_gold_rush.rewards.item", "GOLD_INGOT", 16),
        new PhysicalGoldMigration("rewards.nether_quarry.rewards.item", "GOLD_INGOT", 16)
    );

    private RewardConfigV5Migration() {
    }

    static boolean migrateRewards(YamlConfiguration config, ConfigMigrator.MigrationReport report) {
        boolean changed = false;
        for (MoneyMigration migration : MONEY_MIGRATIONS) {
            changed |= migrateMoneyAction(config, migration, report);
        }
        for (PhysicalGoldMigration migration : PHYSICAL_GOLD_MIGRATIONS) {
            changed |= removePhysicalGoldAction(config, migration, report);
        }
        return changed;
    }

    static boolean migrateMessages(YamlConfiguration config, ConfigMigrator.MigrationReport report) {
        String path = "rewards-rewards-line-money";
        if (!"&7- &fMoney: &e{amount}".equals(config.getString(path))) {
            return false;
        }
        config.set(path, "&7- &fRaw Gold: &e{amount}");
        report.migrated("messages.yml: labeled Vault reward currency as raw gold");
        return true;
    }

    private static boolean migrateMoneyAction(YamlConfiguration config, MoneyMigration migration,
                                              ConfigMigrator.MigrationReport report) {
        String typePath = migration.path() + ".type";
        String type = config.getString(typePath, "");
        boolean knownMoney = "MONEY".equalsIgnoreCase(type)
            && migration.oldAmounts().stream()
                .anyMatch(oldAmount -> Double.compare(config.getDouble(migration.path() + ".amount"),
                    oldAmount) == 0);
        boolean physicalGold = "ITEM".equalsIgnoreCase(type)
            && isPhysicalGold(config.getString(migration.path() + ".material", ""));
        if (!knownMoney && !physicalGold) {
            return false;
        }

        config.set(typePath, "MONEY");
        config.set(migration.path() + ".amount", migration.newAmount());
        clear(config, migration.path() + ".id");
        clear(config, migration.path() + ".material");
        clear(config, migration.path() + ".label");
        clear(config, migration.path() + ".display-name");
        clear(config, migration.path() + ".lore");
        report.migrated("rewards.yml: restored Vault raw-gold payout "
            + migration.path() + " -> " + migration.newAmount());
        return true;
    }

    private static boolean removePhysicalGoldAction(YamlConfiguration config,
                                                    PhysicalGoldMigration migration,
                                                    ConfigMigrator.MigrationReport report) {
        String type = config.getString(migration.path() + ".type", "");
        String material = config.getString(migration.path() + ".material", "");
        int amount = config.getInt(migration.path() + ".amount", 0);
        if (!"ITEM".equalsIgnoreCase(type)
            || !migration.material().equalsIgnoreCase(material)
            || amount != migration.amount()) {
            return false;
        }
        clear(config, migration.path());
        report.migrated("rewards.yml: removed physical gold item payout " + migration.path());
        return true;
    }

    private static boolean isPhysicalGold(String material) {
        return "GOLD_NUGGET".equalsIgnoreCase(material)
            || "GOLD_INGOT".equalsIgnoreCase(material)
            || "GOLD_BLOCK".equalsIgnoreCase(material)
            || "RAW_GOLD".equalsIgnoreCase(material)
            || "RAW_GOLD_BLOCK".equalsIgnoreCase(material);
    }

    @SuppressWarnings("PMD.NullAssignment")
    private static void clear(YamlConfiguration config, String path) {
        config.set(path, null);
    }

    private record MoneyMigration(String path, List<Double> oldAmounts, double newAmount) {
        MoneyMigration {
            oldAmounts = List.copyOf(oldAmounts);
        }
    }

    private record PhysicalGoldMigration(String path, String material, int amount) {
    }
}
