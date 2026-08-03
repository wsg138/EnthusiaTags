package org.enthusia.tags;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class RendererRetirementTest {
    @Test
    void customDisplayRendererIsNotPackagedOrReferenced() throws IOException {
        assertThrows(ClassNotFoundException.class,
            () -> Class.forName("org.enthusia.tags.TagDisplayManager"));

        try (var files = Files.walk(Path.of("src/main/java/org/enthusia/tags"))) {
            String source = files.filter(path -> path.toString().endsWith(".java"))
                .map(RendererRetirementTest::read)
                .reduce("", String::concat);
            assertFalse(source.contains("TextDisplay"));
            assertFalse(source.contains("addPassenger"));
            assertFalse(source.contains("getPassengers"));
            assertFalse(source.contains("TagDisplayManager"));
        }
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
