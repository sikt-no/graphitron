package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Structural guard for the nameability rule's site coverage: every {@code Class.forName} call in
 * {@code graphitron} main sources carries an explicit {@code nameability:} marker comment, either
 * {@code nameability: checked} at a site gated through {@link ClasspathNameability} or
 * {@code nameability: exempt (<reason>)} at one that resolves a name no author wrote (a jOOQ
 * catalog class, a reflected signature's type, a revalidation of a name an earlier gate already
 * judged). Enumerating checked sites is how enforcement goes stale: the next
 * {@code Class.forName(..., codegenLoader)} someone adds would be silently unchecked and no test
 * would fail. This guard turns "did we cover every site" from a review question into a build
 * gate, which is the only form in which the answer stays true.
 *
 * <p>The detector is total over {@code Class.forName} rather than trying to trace which loader a
 * call receives: dataflow is beyond a lexical scan, and a site that resolves against any loader
 * still owes one line saying why the nameability rule does or does not apply to it. Comment and
 * string regions are excluded via {@link JavaSourceRegions#code}, so javadoc that merely mentions
 * {@code Class.forName} does not count as a site.
 *
 * <p>When this guard fires, decide the new site against the criterion in
 * {@link ClasspathNameability}'s javadoc: an author-written class name routes through the check
 * and gets a {@code nameability: checked} marker; anything else gets
 * {@code nameability: exempt (<reason>)} with the reason spelled out. Do not suppress the guard.
 */
@UnitTier
class CodegenClassForNameGuardTest {

    /** The marker token; both the checked and the exempt form carry it. */
    private static final String MARKER = "nameability:";

    /** How many lines above a site the marker may sit, to allow a short multi-line comment. */
    private static final int MARKER_WINDOW = 3;

    /** Floor on scanned files, catching a drifted walk root that would pass vacuously. */
    private static final int MIN_SCANNED_FILES = 200;

    /** Floor on detected sites, catching a lexer change that would stop seeing any site. */
    private static final int MIN_SITES = 30;

    record Finding(Path file, int line, String lineText) {
        @Override public String toString() {
            return file + ":" + line + "  " + lineText.strip();
        }
    }

    /** Sites and marker state for one source, unit-testable with an in-memory string. */
    static List<Finding> scanSource(Path file, String source) {
        String[] code = JavaSourceRegions.code(source);
        String[] comments = JavaSourceRegions.comments(source);
        String[] raw = source.split("\n", -1);
        var findings = new ArrayList<Finding>();
        for (int i = 0; i < code.length; i++) {
            if (!code[i].contains("Class.forName(")) continue;
            boolean marked = false;
            for (int j = Math.max(0, i - MARKER_WINDOW); j <= i && !marked; j++) {
                marked = j < comments.length && comments[j].contains(MARKER);
            }
            if (!marked) {
                findings.add(new Finding(file, i + 1, raw[i]));
            }
        }
        return findings;
    }

    /** Count of detected sites, marked or not, for the detection floor. */
    static int siteCount(String source) {
        String[] code = JavaSourceRegions.code(source);
        int count = 0;
        for (String line : code) {
            if (line.contains("Class.forName(")) count++;
        }
        return count;
    }

    @Test
    void everyForNameSiteCarriesANameabilityMarker() throws IOException {
        Path root = GuardScope.locateRepoRoot().resolve("graphitron/src/main/java");
        var findings = new ArrayList<Finding>();
        int[] scanned = {0};
        int[] sites = {0};
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes a) {
                if (file.getFileName().toString().endsWith(".java")) {
                    scanned[0]++;
                    try {
                        String source = Files.readString(file);
                        sites[0] += siteCount(source);
                        findings.addAll(scanSource(file, source));
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                }
                return FileVisitResult.CONTINUE;
            }
        });

        assertThat(scanned[0])
            .as("a scanned-file count near zero means the walk root drifted and the guard passes vacuously")
            .isGreaterThan(MIN_SCANNED_FILES);
        assertThat(sites[0])
            .as("a site count near zero means the detector stopped seeing Class.forName at all")
            .isGreaterThanOrEqualTo(MIN_SITES);
        assertThat(findings)
            .as("every Class.forName in graphitron main sources must carry a 'nameability: checked' "
                + "or 'nameability: exempt (<reason>)' marker within " + MARKER_WINDOW
                + " lines above it; decide the site against ClasspathNameability's criterion. "
                + "Unmarked sites:\n"
                + findings.stream().map(Object::toString).reduce((a, b) -> a + "\n" + b).orElse(""))
            .isEmpty();
    }

    @Test
    void unmarkedSiteIsAFinding() {
        String fixture = """
            class Fixture {
                Class<?> load(String name, ClassLoader codegenLoader) throws Exception {
                    return Class.forName(name, false, codegenLoader);
                }
            }
            """;
        assertThat(scanSource(Path.of("Fixture.java"), fixture)).hasSize(1);
    }

    @Test
    void markedSiteIsClean() {
        String fixture = """
            class Fixture {
                Class<?> load(String name, ClassLoader codegenLoader) throws Exception {
                    // nameability: exempt (fixture reason)
                    return Class.forName(name, false, codegenLoader);
                }
            }
            """;
        assertThat(scanSource(Path.of("Fixture.java"), fixture)).isEmpty();
    }

    @Test
    void javadocMentionIsNotASite() {
        String fixture = """
            class Fixture {
                /** Resolves via {@code Class.forName(name)} on retry. */
                int notASite;
            }
            """;
        assertThat(siteCount(fixture)).isZero();
        assertThat(scanSource(Path.of("Fixture.java"), fixture)).isEmpty();
    }

    @Test
    void markerOutsideTheWindowDoesNotCount() {
        String fixture = """
            class Fixture {
                // nameability: exempt (too far away)
                int a;
                int b;
                int c;
                Class<?> load(String name, ClassLoader codegenLoader) throws Exception {
                    return Class.forName(name, false, codegenLoader);
                }
            }
            """;
        assertThat(scanSource(Path.of("Fixture.java"), fixture)).hasSize(1);
    }
}
