package org.enthusia.tags;

import java.util.Collection;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class TagRegistry {
    private final Map<String, TagDefinition> tags = new ConcurrentHashMap<>();

    public void register(TagDefinition tag) {
        tags.put(tag.getId().toLowerCase(), tag);
    }

    public void unregister(String id) {
        tags.remove(id.toLowerCase());
    }

    public TagDefinition get(String id) {
        return tags.get(id.toLowerCase());
    }

    public Collection<TagDefinition> getAll() {
        return Collections.unmodifiableCollection(tags.values());
    }

    public void clear() {
        tags.clear();
    }
}
