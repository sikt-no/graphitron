package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit cases for {@link RoadmapConsumerScanner}'s rule, kept separate from the reactor walk in
 * {@link RoadmapConsumerGuardTest} so each shape the rule has to distinguish is pinned by a case
 * naming it rather than by whichever sources happen to be in the tree.
 */
@UnitTier
class RoadmapConsumerScannerTest {

    private static final Path FILE = Path.of("graphitron/src/test/java/Example.java");

    private static List<RoadmapConsumerScanner.Finding> scan(String source) {
        return RoadmapConsumerScanner.scanSource(FILE, source, Set.of());
    }

    @Test
    void namedRoadmapPathInALiteralIsAConsumer() {
        assertThat(scan("var p = dir.resolve(\"roadmap/some-slug.md\");")).hasSize(1);
    }

    @Test
    void bareRoadmapDirectoryIsAConsumer() {
        assertThat(scan("var p = dir.resolve(\"roadmap\");")).hasSize(1);
    }

    @Test
    void relativePrefixDoesNotEscapeTheRule() {
        assertThat(scan("var p = Path.of(\"../roadmap/some-slug.md\");")).hasSize(1);
        assertThat(scan("var p = Path.of(\"./roadmap\");")).hasSize(1);
    }

    @Test
    void permanentArtifactsAreAllowed() {
        assertThat(scan("var p = dir.resolve(\"roadmap/workflow.adoc\");")).isEmpty();
        assertThat(scan("var p = dir.resolve(\"roadmap/changelog.md\");")).isEmpty();
        assertThat(scan("var p = dir.resolve(\"roadmap/README.md\");")).isEmpty();
    }

    /**
     * The whole-literal rule is the point of departure from
     * {@link RoadmapReferenceScanner#SLUG_REF}, which matches anywhere in a line. A literal that
     * quotes a roadmap path as part of a larger sentence is not a consumer, and the neighbouring
     * scanner's own fixtures are exactly that shape, so reusing find semantics here would flag
     * files that read nothing.
     */
    @Test
    void aLiteralThatMerelyContainsARoadmapPathIsNotAConsumer() {
        assertThat(scan("assertThat(count(\"see roadmap/some-slug.md for context\")).isZero();")).isEmpty();
        assertThat(scan("var msg = \"recorded in roadmap/changelog.md on landing\";")).isEmpty();
    }

    @Test
    void theModuleNameIsNotAPath() {
        assertThat(scan("var s = \"roadmap-tool table check fails the build on one\";")).isEmpty();
        assertThat(scan("var s = \"roadmap-tool\";")).isEmpty();
    }

    @Test
    void aTrailingSlashWithNoSegmentIsNotAPath() {
        assertThat(scan("assertThat(r.message()).doesNotContain(\"roadmap/\");")).isEmpty();
    }

    @Test
    void aRegexCharacterClassIsNotAPath() {
        assertThat(scan("static final Pattern P = Pattern.compile(\"roadmap/[A-Za-z0-9_-]+\");")).isEmpty();
    }

    @Test
    void commentsAreNotLiterals() {
        assertThat(scan("// reads roadmap/some-slug.md at startup")).isEmpty();
        assertThat(scan("/** Probes roadmap/some-slug.md. */")).isEmpty();
    }

    /**
     * Two literals on one line project into one concatenated run under
     * {@link JavaSourceRegions#strings}, in which neither value can be recognised and a spurious
     * third one appears. The scanner reads {@link JavaSourceRegions#literalsByLine} instead, so the
     * allowed pair below stays two allowed values rather than becoming one unrecognised one.
     */
    @Test
    void adjacentLiteralsOnOneLineAreSeparateValues() {
        assertThat(scan("var s = Set.of(\"roadmap/changelog.md\", \"roadmap/workflow.adoc\");")).isEmpty();
        assertThat(scan("var s = Set.of(\"roadmap/a-slug.md\", \"roadmap/b-slug.md\");")).hasSize(2);
    }

    @Test
    void aTextBlockNamingAPathIsAConsumer() {
        assertThat(scan("""
            var s = \"""
                roadmap/some-slug.md\""";
            """)).hasSize(1);
    }

    @Test
    void anExemptedFileIsSkippedEntirely() {
        String source = "var p = dir.resolve(\"roadmap/some-slug.md\");";
        assertThat(RoadmapConsumerScanner.scanSource(FILE, source, Set.of("Example.java"))).isEmpty();
    }

    @Test
    void anExemptionIsAPathSuffixRatherThanABareName() {
        String source = "var p = dir.resolve(\"roadmap/some-slug.md\");";
        assertThat(RoadmapConsumerScanner.scanSource(FILE, source, Set.of("other/Example.java")))
            .as("a suffix that does not match this file's path must not exempt it")
            .hasSize(1);
    }

    @Test
    void findingsCarryTheLineNumberAndTheRawLine() {
        String source = "class C {\n    var p = dir.resolve(\"roadmap/some-slug.md\");\n}";
        List<RoadmapConsumerScanner.Finding> findings = scan(source);
        assertThat(findings).hasSize(1);
        assertThat(findings.getFirst().line()).isEqualTo(2);
        assertThat(findings.getFirst().literal()).isEqualTo("roadmap/some-slug.md");
        assertThat(findings.getFirst().lineText()).contains("dir.resolve");
    }

}
