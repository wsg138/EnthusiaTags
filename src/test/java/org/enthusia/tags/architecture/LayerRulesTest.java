package org.enthusia.tags.architecture;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Java equivalent of the SPEAR JVM template for new advancement packages. */
class LayerRulesTest {
    @Test
    void newDomainAndApplicationDoNotDependOnInfrastructure() throws Exception {
        Path root = Path.of("src/main/java/org/enthusia/tags/advancements");
        try (var files = Files.walk(root)) {
            for (Path file : files.filter(path -> path.toString().endsWith(".java")).toList()) {
                String relative = root.relativize(file).toString().replace('\\', '/');
                boolean domain = relative.startsWith("domain/");
                boolean application = relative.startsWith("application/");
                if (!domain && !application) continue;
                for (String line : Files.readAllLines(file)) {
                    String trimmed = line.trim();
                    if (!trimmed.startsWith("import ")) continue;
                    String name = trimmed.replace("import static ", "import ").substring(7);
                    assertTrue(name.startsWith("java.")
                        || name.startsWith("org.enthusia.tags.advancements.domain.")
                        || (application && name.startsWith("org.enthusia.tags.advancements.application.")),
                        file + ": illegal dependency " + name);
                }
            }
        }
    }
}
