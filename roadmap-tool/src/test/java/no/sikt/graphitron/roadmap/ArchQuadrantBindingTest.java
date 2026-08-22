package no.sikt.graphitron.roadmap;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Binds {@link LinkTarget#ARCH_QUADRANT} to the docs tree it describes. The map is this
 * tool's private copy of the architecture docs layout, and a copy repaired by hand on every
 * move ships a link that renders live and 404s before anyone notices. Every entry must
 * resolve to an existing {@code docs/architecture/<section>/<slug>.adoc}, so a page move
 * that forgets the map fails the build instead of the reader.
 */
class ArchQuadrantBindingTest {

    @Test
    void everyEntryResolvesToAnAuthoredArchitecturePage() {
        Path architecture = locateArchitectureDir();

        assertThat(LinkTarget.ARCH_QUADRANT)
            .as("the layout map must not be empty; an empty map is a vacuous pass")
            .isNotEmpty();
        for (Map.Entry<String, String> entry : LinkTarget.ARCH_QUADRANT.entrySet()) {
            Path page = architecture.resolve(entry.getValue()).resolve(entry.getKey() + ".adoc");
            assertThat(Files.isRegularFile(page))
                .as("ARCH_QUADRANT maps %s -> %s, but %s does not exist; the page moved"
                        + " without the map, or the entry is stale",
                    entry.getKey(), entry.getValue(), page)
                .isTrue();
        }
    }

    private static Path locateArchitectureDir() {
        Path cwd = Path.of("").toAbsolutePath();
        for (Path p = cwd; p != null; p = p.getParent()) {
            Path candidate = p.resolve("docs/architecture");
            if (Files.isDirectory(candidate)) return candidate;
        }
        throw new IllegalStateException("could not locate docs/architecture walking up from " + cwd);
    }
}
