package org.enthusia.tags;

import java.util.ArrayList;
import java.util.List;

public final class IntegrationStatus {
    private final List<String> warnings = new ArrayList<>();

    public void clear() {
        warnings.clear();
    }

    public void addWarning(String warning) {
        if (warning != null && !warning.isBlank()) {
            warnings.add(warning);
        }
    }

    public boolean hasWarnings() {
        return !warnings.isEmpty();
    }

    public List<String> warnings() {
        return List.copyOf(warnings);
    }
}
