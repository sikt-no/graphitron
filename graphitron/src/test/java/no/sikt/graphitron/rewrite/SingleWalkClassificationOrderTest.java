package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R317 slice 4 — the falsifiable acceptance test for the single classify-and-emit walk: <b>no type is
 * registered before the field that discovers it is visited</b>. This is the structural assertion the
 * collapse is built to satisfy and that the retired two-pass model could not: {@code buildTypes} eagerly
 * classified every reachable composite <em>before</em> any field was classified, so a deep target's type
 * record always preceded its discovering field's record. Under the single walk, a composite is classified
 * only when the walk's enter reaches it, which happens after the parent field that points at it has been
 * classified.
 *
 * <p>The probe reads the {@link ClassificationTrace} JSONL stream (which records both type-axis and
 * field-axis classifications into one ordered log) for a chain {@code Query.a -> TypeA.b -> TypeB} where
 * {@code TypeB} is reachable only through {@code TypeA.b}. The assertion: {@code TypeB}'s type-classify
 * record appears <em>after</em> {@code TypeA.b}'s field-classify record. Run against an eager type pass
 * this inverts and the test fails, which is the point: if it would pass unchanged against the old model,
 * the collapse did nothing.
 *
 * <p>Unique type names ({@code TypeA} / {@code TypeB}) isolate this build's records from any other
 * classification co-resident in the same JVM fork; the {@code @table} names are real catalog tables with a
 * single FK between them so the nested field classifies cleanly.
 */
@PipelineTier
class SingleWalkClassificationOrderTest {

    @TempDir
    Path tempDir;

    private Path tracePath;

    @BeforeEach
    void enableTracing() {
        tracePath = tempDir.resolve("r317-walk-order.jsonl");
        ClassificationTrace.resetForTesting(tracePath);
    }

    @AfterEach
    void disableTracing() {
        ClassificationTrace.resetForTesting(null);
        ClassificationTrace.clearContext();
    }

    @Test
    void noTypeIsRegisteredBeforeItsDiscoveringFieldIsVisited() throws IOException {
        TestSchemaHelper.buildSchema("""
            type Query { a: TypeA }
            type TypeA @table(name: "film") {
              b: TypeB @reference(path: [{key: "film_language_id_fkey"}])
            }
            type TypeB @table(name: "language") { name: String }
            """);

        var lines = Files.readAllLines(tracePath);
        int discoveringFieldRecord = firstRecord(lines, "\"parent\":\"TypeA\"", "\"name\":\"b\"");
        int targetTypeRecord = firstRecord(lines, "\"op\":\"classify\"", "\"parent\":\"\"", "\"name\":\"TypeB\"");

        assertThat(discoveringFieldRecord)
            .as("TypeA.b field-classify record must be present in the trace")
            .isGreaterThanOrEqualTo(0);
        assertThat(targetTypeRecord)
            .as("TypeB type-classify record must be present in the trace")
            .isGreaterThanOrEqualTo(0);
        assertThat(targetTypeRecord)
            .as("the single walk classifies TypeB only when the walk reaches it, which is after its "
                + "discovering field TypeA.b is classified; an eager type pass would register TypeB first")
            .isGreaterThan(discoveringFieldRecord);
    }

    /** First line index containing every given substring, or -1 if none. */
    private static int firstRecord(List<String> lines, String... substrings) {
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            boolean all = true;
            for (String s : substrings) {
                if (!line.contains(s)) {
                    all = false;
                    break;
                }
            }
            if (all) return i;
        }
        return -1;
    }
}
