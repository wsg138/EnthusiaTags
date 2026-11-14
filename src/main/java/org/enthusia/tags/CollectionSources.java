package org.enthusia.tags;

import java.io.File;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.bukkit.configuration.file.YamlConfiguration;

/** Read-only menu metadata. Ownership and qualification remain with existing services. */
public final class CollectionSources {
    public record Source(String source, List<String> tags, List<String> cosmetics, List<String> activeCosmetics) {}
    private final List<Source> sources = new ArrayList<>();
    public CollectionSources(EnthusiaTagsPlugin plugin) {
        var stream = plugin.getResource("menu-sources.yml");
        YamlConfiguration config = new YamlConfiguration();
        if (stream != null) {
            try (var reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                config = YamlConfiguration.loadConfiguration(reader);
            } catch (java.io.IOException error) {
                plugin.getLogger().warning("Cannot close menu source metadata: " + error.getMessage());
            }
        }
        File folder=plugin.getDataFolder();
        if (folder != null) {
            File file=new File(folder,"entitlements.yml");
            if(file.isFile()) config=YamlConfiguration.loadConfiguration(file);
        }
        var definitions=config.getConfigurationSection("definitions");
        if(definitions!=null) for(String id:definitions.getKeys(false)) {
            var section=definitions.getConfigurationSection(id);
            if(section!=null) sources.add(new Source(section.getString("source",""),section.getStringList("tags"),section.getStringList("cosmetics"),section.getStringList("active-cosmetics")));
        }
    }
    private static boolean contains(List<String> ids,String id) { return ids.stream().anyMatch(value->value.equalsIgnoreCase(id)); }
    public List<Source> definitionsForTag(String id) {
        var result=sources.stream().filter(s->contains(s.tags(),id)).toList();
        if(result.isEmpty() && HolidayTagCatalog.tags().stream().anyMatch(t->t.id().equalsIgnoreCase(id))) return List.of(new Source("Holiday Event",List.of(id),List.of(),List.of()));
        return result;
    }
    public List<Source> definitionsForCosmetic(String id) { return sources.stream().filter(s->contains(s.cosmetics(),id)||contains(s.activeCosmetics(),id)).toList(); }
    public boolean isActiveOnlyCosmetic(String id) { return sources.stream().anyMatch(s->contains(s.activeCosmetics(),id)) && sources.stream().noneMatch(s->contains(s.cosmetics(),id)); }
}
