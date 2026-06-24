package org.enthusia.tags;

import java.util.ArrayList;
import java.util.List;

public final class IntegrationStatus {
    private final List<String> warningMessages = new ArrayList<>();

    public void clear() {
        warningMessages.clear();
    }

    public void addWarning(String warning) {
        if (warning != null && !warning.isBlank()) {
            warningMessages.add(warning);
        }
    }

    public boolean hasWarnings() {
        return !warningMessages.isEmpty();
    }

    public List<String> warnings() {
        return List.copyOf(warningMessages);
    }
}
