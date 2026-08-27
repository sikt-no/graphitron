package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Structural guard behind the scoped verification build: no Java source outside the two modules
 * that read {@code roadmap/} names a path into it, beyond the permanent artifacts and one recorded
 * exemption. See {@code CLAUDE.md} under "Building and testing" for the rule this backs, and
 * {@link RoadmapConsumerScanner} for what the rule matches and why it is whole-literal.
 *
 * <p>What this guard pins is narrower than the claim it guards, and the gap is deliberate. It sees
 * a <em>named</em> path. A file that reaches roadmap content by walking the repository root
 * generically, or one that assembles the path by concatenation, names nothing and passes unnoticed;
 * nothing lexical catches that class. The tree has one such walker today and the scoped build
 * covers its roadmap slice by construction, which is what the recorded exemption says. The guard's
 * job is to make a new named consumer loud, not to prove the absence of readers.
 */
@UnitTier
class RoadmapConsumerGuardTest {

    /** A floor on scanned files, so a drifted repository root cannot make this pass vacuously. */
    private static final int MIN_SCANNED_FILES = 500;

    @Test
    void noRoadmapConsumersOutsideTheRoadmapReadingModules() throws IOException {
        Scan scan = scanReactor(RoadmapConsumerScanner.ALLOWED_CONSUMERS);

        assertThat(scan.scanned())
            .as("the guard reaches sibling modules by walking to the repository root; a scanned-file "
                + "count near zero means the root drifted and the guard would pass vacuously")
            .isGreaterThan(MIN_SCANNED_FILES);

        assertThat(scan.findings())
            .as("a source outside roadmap-tool and docs names a path into roadmap/, which puts it "
                + "outside what the scoped verification build covers. Either it does not need the "
                + "path, or the scoped build still covers what it reads and the reason belongs in "
                + "RoadmapConsumerScanner.ALLOWED_CONSUMERS, or the scoped-build rule in CLAUDE.md "
                + "is no longer true and has to change. Offending sites:\n"
                + scan.findings().stream().map(Object::toString).reduce((a, b) -> a + "\n" + b).orElse(""))
            .isEmpty();
    }

    /**
     * The exemption set earns its keep only while the tree still needs it. Scanning with it emptied
     * must fail, so an entry that outlives the code it was written for shows up as a failure here
     * rather than sitting in the set forever.
     */
    @Test
    void everyRecordedExemptionIsStillLoadBearing() throws IOException {
        List<RoadmapConsumerScanner.Finding> withoutExemptions = scanReactor(Set.of()).findings();

        assertThat(withoutExemptions)
            .as("with ALLOWED_CONSUMERS emptied the scan must still report something; an empty "
                + "result means every recorded exemption is obsolete and the set should be emptied")
            .isNotEmpty();

        List<String> exemptedFiles = withoutExemptions.stream()
            .map(f -> f.file().toString().replace('\\', '/'))
            .filter(p -> RoadmapConsumerScanner.ALLOWED_CONSUMERS.stream().anyMatch(p::endsWith))
            .distinct()
            .toList();

        assertThat(exemptedFiles)
            .as("every entry in ALLOWED_CONSUMERS must correspond to a file the scan actually "
                + "reports without it; an entry matching nothing is stale and should be removed")
            .hasSameSizeAs(RoadmapConsumerScanner.ALLOWED_CONSUMERS);
    }

    private record Scan(List<RoadmapConsumerScanner.Finding> findings, int scanned) {}

    private static Scan scanReactor(Set<String> allowedConsumers) throws IOException {
        Path repoRoot = GuardScope.locateRepoRoot();
        List<RoadmapConsumerScanner.Finding> findings = new ArrayList<>();
        int scanned = 0;
        for (String module : GuardScope.IN_SCOPE_MODULES) {
            for (String tree : List.of("src/main/java", "src/test/java")) {
                Path root = repoRoot.resolve(module).resolve(tree);
                if (!Files.isDirectory(root)) continue;
                findings.addAll(RoadmapConsumerScanner.scan(root, allowedConsumers));
                try (var paths = Files.walk(root)) {
                    scanned += (int) paths.filter(p -> p.toString().endsWith(".java")).count();
                }
            }
        }
        return new Scan(findings, scanned);
    }

}
