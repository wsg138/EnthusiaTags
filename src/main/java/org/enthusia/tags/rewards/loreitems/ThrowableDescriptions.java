package org.enthusia.tags.rewards.loreitems;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.Set;

public final class ThrowableDescriptions {
    private ThrowableDescriptions() {
    }

    public static String describe(Throwable throwable) {
        if (throwable == null) {
            return "Unknown failure";
        }
        Set<Throwable> visited = Collections.newSetFromMap(new IdentityHashMap<>());
        Throwable current = throwable;
        while (visited.add(current)) {
            Throwable cause = current.getCause();
            if (cause == null || visited.contains(cause)) {
                break;
            }
            current = cause;
        }
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }
}
