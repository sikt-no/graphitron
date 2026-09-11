package no.sikt.graphitron.model.sink;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * One way to write more than one row, and this is what keeps it to one.
 *
 * <p>A multi-row insert is rendered by H2 as a chain of unioned SELECTs, one per row, and for an
 * upsert as a MERGE over that chain. Its parser clones the whole token list once per nesting level,
 * so a whole relation in one statement costs tokens quadratic in its rows: a consumer with a 27000
 * line schema met that as an {@code OutOfMemoryError} on a 16 GB heap. {@link RowChunks#execute}
 * bounds it, and every writer in this module goes through it.
 *
 * <p>The rule is checked by enclosure rather than by a name. A scan for "the argument is called
 * chunk" passes for a writer that names its whole row list {@code chunk}, which is the mistake the
 * bound exists to prevent and the one a reader would least expect a green build to permit. So each
 * {@code valuesOfRows} is required to sit lexically inside a {@code RowChunks.execute} call, which
 * is the property that actually bounds it.
 *
 * <p>No exemption roster, deliberately. Two of the writers here are small on the schemas we have,
 * one row per schema file and one per classpath entry, and both are consumer-controlled and
 * unbounded in principle; a short list through the helper is one statement, so uniformity costs
 * nothing and a roster would be the thing that rots.
 */
class MultiRowWritesAreChunkedTest {

    private static final String MAIN_SOURCES = "graphitron-model/src/main/java";

    /** The call every multi-row write has to sit inside. */
    private static final String BOUND = "RowChunks.execute";

    /** The write that has to be bounded. */
    private static final String MULTI_ROW = ".valuesOfRows(";

    /** A walk that reached nothing would otherwise pass, so the population has a floor. */
    private static final int MIN_SITES = 60;

    @Test
    @DisplayName("every multi-row write in this module goes through the bound")
    void everyMultiRowWriteIsChunked() throws IOException {
        var unbounded = new ArrayList<String>();
        int sites = 0;

        for (Path source : mainSources()) {
            String text = Files.readString(source, StandardCharsets.UTF_8);
            for (int at = text.indexOf(MULTI_ROW); at >= 0; at = text.indexOf(MULTI_ROW, at + 1)) {
                sites++;
                if (!enclosedByTheBound(text, at)) {
                    unbounded.add(source.getFileName() + ":" + lineOf(text, at));
                }
            }
        }

        assertThat(sites)
            .as("fewer multi-row writes than this module has; did the scan reach the sources?")
            .isGreaterThanOrEqualTo(MIN_SITES);
        assertThat(unbounded)
            .as("multi-row writes not inside a " + BOUND + " call. A whole relation in one "
                + "statement is quadratic in H2's parser and takes the heap with it on a large "
                + "consumer schema; wrap the statement in " + BOUND + "(rows, chunk -> ...) and "
                + "give it the chunk")
            .isEmpty();
    }

    /**
     * Whether the occurrence at {@code at} sits inside a {@link RowChunks#execute} call, decided by
     * walking back over balanced parentheses to the innermost call still open at that point. Any
     * unbalanced {@code (} is a call this text is inside; the innermost one has to be the bound.
     */
    private static boolean enclosedByTheBound(String text, int at) {
        int depth = 0;
        for (int i = at - 1; i >= 0; i--) {
            char c = text.charAt(i);
            if (c == ')') {
                depth++;
            } else if (c == '(') {
                if (depth == 0) {
                    return text.lastIndexOf(BOUND, i) == i - BOUND.length();
                }
                depth--;
            } else if (c == ';' || c == '}') {
                // A statement or block boundary at depth zero: nothing above encloses this.
                if (depth == 0) {
                    return false;
                }
            }
        }
        return false;
    }

    private static int lineOf(String text, int at) {
        return (int) text.substring(0, at).chars().filter(c -> c == '\n').count() + 1;
    }

    private static List<Path> mainSources() throws IOException {
        Path root = repoRoot().resolve(MAIN_SOURCES);
        assertThat(root).as("the module's main sources").isDirectory();
        try (Stream<Path> walk = Files.walk(root)) {
            return walk.filter(p -> p.getFileName().toString().endsWith(".java")).sorted().toList();
        }
    }

    /** Surefire runs from the module directory; a run started at the reactor root would not. */
    private static Path repoRoot() {
        Path cwd = Path.of("").toAbsolutePath();
        for (Path p = cwd; p != null; p = p.getParent()) {
            if (Files.isDirectory(p.resolve(MAIN_SOURCES))) {
                return p;
            }
        }
        throw new IllegalStateException("Could not locate " + MAIN_SOURCES + " from " + cwd);
    }
}
