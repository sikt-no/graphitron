package no.sikt.graphitron.model;

import no.sikt.graphitron.model.capture.sdl.SdlEntries;
import no.sikt.graphitron.model.capture.sdl.SdlSchemaProblems;
import no.sikt.graphitron.model.schema.SchemaAssembly;
import no.sikt.graphitron.model.schema.SchemaError;
import no.sikt.graphitron.model.schema.SchemaLoader;
import no.sikt.graphitron.model.schema.input.SchemaSource;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_TYPE_DECLARATION_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_SCHEMA_PROBLEM;
import static no.sikt.graphitron.model.test.SeededStore.seedSource;
import static no.sikt.graphitron.model.test.SeededStore.withSeededStore;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * What went wrong when the documents were made into a schema, recorded rather than re-derived.
 *
 * <p>One relation for the whole attempt. Merging the documents and building a schema from them are
 * not two questions: the merge only ever says whether a name was declared twice, three error classes
 * in all, while the build runs around thirty checks over references, interface contracts, input
 * against output position, directive locations and the schema's own shape. A reader asking why their
 * schema did not build does not care which of the two spoke.
 *
 * <p>Absence is success, and is meant to be relied on: a graph that made a schema has no rows here.
 */
class SdlSchemaProblemsTest {

    private static final String GRAPH = "problems";

    @Test
    @DisplayName("a corpus that makes a schema records no problem")
    void aCorpusThatBuildsRecordsNothing(@TempDir Path tmp) {
        Path file = write(tmp, "ok.graphqls", "type Query { a: String }\n");

        withSeededStore(GRAPH, dsl -> {
            read(dsl, file);
            assertThat(dsl.fetchCount(GRAPHQL_SCHEMA_PROBLEM))
                .as("it built, so there is nothing to say").isZero();
        });
    }

    /**
     * A name declared twice is the merge's own complaint, and it arrives in the same relation as
     * everything else.
     */
    @Test
    @DisplayName("a redefinition is a problem, and the entries still hold both declarations")
    void aRedefinitionIsRecorded(@TempDir Path tmp) {
        Path first = write(tmp, "first.graphqls", "type Query { a: String }\ntype X { a: String }\n");
        Path second = write(tmp, "second.graphqls", "type X { b: Int }\n");

        withSeededStore(GRAPH, dsl -> {
            read(dsl, first, second);

            assertThat(dsl.select(GRAPHQL_SCHEMA_PROBLEM.ERROR_CLASS, GRAPHQL_SCHEMA_PROBLEM.MESSAGE)
                    .from(GRAPHQL_SCHEMA_PROBLEM).fetch())
                .as("graphql-java's own class and sentence, kept verbatim")
                .anySatisfy(row -> {
                    assertThat(row.value1()).isEqualTo("TypeRedefinitionError");
                    assertThat(row.value2()).contains("tried to redefine existing");
                });

            assertThat(dsl.select(GRAPHQL_AST_TYPE_DECLARATION_ENTRY.SOURCE_NAME)
                    .from(GRAPHQL_AST_TYPE_DECLARATION_ENTRY)
                    .where(GRAPHQL_AST_TYPE_DECLARATION_ENTRY.NAME.eq("X"))
                    .fetch(GRAPHQL_AST_TYPE_DECLARATION_ENTRY.SOURCE_NAME))
                .as("the corpus did not build and both declarations are still captured, which is "
                    + "what a report about the collision reads")
                .hasSize(2);
        });
    }

    /**
     * The build's own checks land here too, which is the half worth having: a reference that does not
     * resolve is one of about thirty things the merge never looks at.
     */
    @Test
    @DisplayName("a build check is a problem in the same relation as a merge complaint")
    void aBuildCheckIsRecordedTheSameWay(@TempDir Path tmp) {
        Path file = write(tmp, "dangling.graphqls", "type Query { a: Missing }\n");

        withSeededStore(GRAPH, dsl -> {
            read(dsl, file);
            assertThat(dsl.select(GRAPHQL_SCHEMA_PROBLEM.ERROR_CLASS)
                    .from(GRAPHQL_SCHEMA_PROBLEM).fetch(GRAPHQL_SCHEMA_PROBLEM.ERROR_CLASS))
                .as("nothing was declared twice, so only the build could have caught this")
                .contains("MissingTypeError");
        });
    }

    @Test
    @DisplayName("a problem the corpus no longer has is swept")
    void aProblemTheCorpusNoLongerHasIsSwept(@TempDir Path tmp) {
        Path file = write(tmp, "fixme.graphqls", "type Query { a: Missing }\n");

        withSeededStore(GRAPH, dsl -> {
            read(dsl, file);
            assertThat(dsl.fetchCount(GRAPHQL_SCHEMA_PROBLEM)).as("before the fix").isNotZero();

            write(tmp, "fixme.graphqls", "type Query { a: String }\n");
            read(dsl, file);

            assertThat(dsl.fetchCount(GRAPHQL_SCHEMA_PROBLEM))
                .as("the author fixed it, so the store stops claiming a problem that is over")
                .isZero();
        });
    }

    /**
     * The same problem said twice is one problem. One missing type reached for from five fields
     * raises five errors whose sentences are byte-identical, the message naming the absent type and
     * the type that reached for it but never the field, so four of the five are a reader's tax and
     * nothing else. How many fields reached for it is a count over the entries.
     */
    @Test
    @DisplayName("a problem raised five times is recorded once")
    void duplicateProblemsAreRecordedOnce(@TempDir Path tmp) {
        Path file = write(tmp, "many.graphqls",
            "type Query { one: Gone, two: Gone, three: [Gone!]!, four: Gone, five: Gone }\n");

        withSeededStore(GRAPH, dsl -> {
            read(dsl, file);

            assertThat(dsl.select(GRAPHQL_SCHEMA_PROBLEM.ERROR_CLASS, GRAPHQL_SCHEMA_PROBLEM.MESSAGE)
                    .from(GRAPHQL_SCHEMA_PROBLEM).fetch())
                .as("one absent type is one problem, however many fields reached for it")
                .hasSize(1)
                .allSatisfy(row -> assertThat(row.value1()).isEqualTo("MissingTypeError"));
        });
    }

    /** Ingests each document, then records what making a schema out of them produced. */
    private static void read(DSLContext dsl, Path... files) {
        LocalDateTime touchedAt = LocalDateTime.now();
        seedSource(dsl, SchemaLoader.DIRECTIVES_SOURCE_NAME, "SCHEMA_FILE");
        Arrays.stream(files).forEach(file -> seedSource(dsl, file.toString(), "SCHEMA_FILE"));

        var parse = SchemaLoader.parsePerSource(
            Arrays.stream(files).map(SchemaSource::file).toList());
        parse.perSource().forEach(document ->
            SdlEntries.write(dsl, GRAPH, document.sourceName(), document.registry(), touchedAt));

        // One attempt, one list. What the merge refused and what the build refused are the same
        // question asked of the same corpus.
        var problems = new ArrayList<SchemaError>(parse.registryErrors());
        problems.addAll(SchemaAssembly.of(parse.registry()).errors());
        SdlSchemaProblems.write(dsl, GRAPH, List.copyOf(problems), touchedAt);
    }

    private static Path write(Path directory, String name, String sdl) {
        try {
            return Files.writeString(directory.resolve(name), sdl, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
