package org.enthusia.tags;
import java.nio.file.*;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class NestedDefaultsMigrationTest {
    @TempDir Path directory;
    @Test void installsNestedCategoryDefaultsOnceAndPreservesAdminRewards() throws Exception {
        var file=directory.resolve("rewards.yml").toFile();
        var existing=new YamlConfiguration();
        existing.set("config-version",7);
        existing.set("categories.playtime.name","Custom Playtime");
        existing.set("rewards.custom.name","Keep my custom reward");
        existing.save(file);
        var plugin=mock(JavaPlugin.class);
        when(plugin.getDataFolder()).thenReturn(directory.toFile());
        when(plugin.getResource("rewards.yml")).thenAnswer(call->getClass().getResourceAsStream("/rewards.yml"));
        var migrator=new ConfigMigrator(plugin);
        migrator.migrate("rewards.yml",new ConfigMigrator.MigrationReport());
        var migrated=YamlConfiguration.loadConfiguration(file);
        assertEquals("Custom Playtime",migrated.getString("categories.playtime.name"));
        assertEquals("CLOCK",migrated.getString("categories.playtime.icon"));
        assertTrue(migrated.contains("categories.advancements"));
        assertEquals("holidays",migrated.getString("categories.halloween.parent"));
        assertFalse(migrated.contains("categories.categories"));
        assertEquals(1,migrated.getConfigurationSection("rewards").getKeys(false).size());
        String once=Files.readString(file.toPath());
        migrator.migrate("rewards.yml",new ConfigMigrator.MigrationReport());
        assertEquals(once,Files.readString(file.toPath()));
    }
}
