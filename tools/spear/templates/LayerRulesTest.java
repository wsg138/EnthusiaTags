package base_package.architecture;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
/** Java/JUnit template. Substitute the base package and adapt paths to the documented layer map. */
class LayerRulesTest {
    @Test void layerImportsRespectBoundaries() throws Exception {
        String base="base_package";
        Path source=Path.of("src/main/java",base.replace('.','/'));
        List<String> errors=new ArrayList<>();
        Pattern imports=Pattern.compile("(?m)^import\\s+(?:static\\s+)?([\\w.]+)");
        try(var paths=Files.walk(source)) {
            for(Path file:paths.filter(p->p.toString().endsWith(".java")).toList()) {
                String relative=source.relativize(file).toString().replace('\\','/');
                boolean domain=relative.startsWith("domain/");
                boolean application=relative.startsWith("application/");
                if(!domain && !application) continue;
                Matcher matcher=imports.matcher(Files.readString(file));
                while(matcher.find()) {
                    String name=matcher.group(1);
                    boolean allowed=name.startsWith("java.") || name.startsWith(base+".domain.")
                        || (application && name.startsWith(base+".application."));
                    if(!allowed) errors.add(file+": "+name);
                }
            }
        }
        assertTrue(errors.isEmpty(),()->String.join("\n",errors));
    }
}
