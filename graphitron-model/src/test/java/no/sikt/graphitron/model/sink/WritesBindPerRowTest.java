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
 * <p>H2 renders a multi-row insert as a chain of unioned SELECTs, one per row, and an upsert as a
 * MERGE over that chain. Its parser clones the whole token list once per nesting level, so a
 * statement costs tokens quadratic in the rows it carries: a consumer with a 27000 line schema met
 * that as an {@code OutOfMemoryError} on a 16 GB heap, and the same shape at a tenth the size was
 * still the largest single cost in a capture, one relation's 500-row statement taking 844 ms to
 * parse. Bounding the rows per statement only moves that cost, total time coming out linear in the
 * bound. Binding per row removes it, one render and one parse however many rows a relation has.
 *
 * <p>So the rule is that the module has no multi-row write at all, rather than that its multi-row
 * writes are bounded. An absence is a cheaper thing to check than an enclosure and a stronger thing
 * to claim: there is no bound to pick, no constant to justify, and no way to spell the quadratic
 * that this scan would have to recognise.
 *
 * <p>A floor on the writers, because a scan that reached nothing would otherwise pass, and it is
 * held over {@link BindBatch#execute} rather than over the file count: what must not quietly
 * disappear is the population of relations written through the bind batch.
 */
class WritesBindPerRowTest {

    private static final String MAIN_SOURCES = "graphitron-model/src/main/java";

    /** The write that must not appear. */
    private static final String MULTI_ROW = ".valuesOfRows(";

    /** The write that must. */
    private static final String PER_ROW = "BindBatch.execute";

    /** A walk that reached nothing would otherwise pass, so the population has a floor. */
    private static final int MIN_SITES = 60;

    @Test
    @DisplayName("no write in this module carries its rows in the statement")
    void noMultiRowWrites() throws IOException {
        var multiRow = new ArrayList<String>();
        int sites = 0;

        for (Path source : mainSources()) {
            String text = Files.readString(source, StandardCharsets.UTF_8);
            for (int at = text.indexOf(MULTI_ROW); at >= 0; at = text.indexOf(MULTI_ROW, at + 1)) {
                multiRow.add(source.getFileName() + ":" + lineOf(text, at));
            }
            for (int at = text.indexOf(PER_ROW); at >= 0; at = text.indexOf(PER_ROW, at + 1)) {
                sites++;
            }
        }

        assertThat(sites)
            .as("fewer bind batches than this module has writers; did the scan reach the sources?")
            .isGreaterThanOrEqualTo(MIN_SITES);
        assertThat(multiRow)
            .as("multi-row writes. A statement carrying its rows is quadratic in H2's parser and "
                + "takes the heap with it on a large consumer schema, and bounding the rows per "
                + "statement only makes the cost linear in the bound. Build the statement against "
                + "one row of markers and give the rows to " + PER_ROW + "(dsl, rows, markers -> "
                + "...) instead")
            .isEmpty();
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
