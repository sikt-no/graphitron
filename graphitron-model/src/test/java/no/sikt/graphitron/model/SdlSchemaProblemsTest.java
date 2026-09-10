package no.sikt.graphitron.model;

import no.sikt.graphitron.model.capture.document.SdlCapture;
import no.sikt.graphitron.model.run.GraphIdentity;
import no.sikt.graphitron.model.run.SubjectConfig;
import no.sikt.graphitron.model.schema.input.SchemaRecipe;
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
import java.util.List;

import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_TYPE_DECLARATION_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_SCHEMA_PROBLEM;
import static no.sikt.graphitron.model.Tables.STORE_SOURCE;
import static no.sikt.graphitron.model.test.SeededStore.seedGraph;
import static no.sikt.graphitron.model.test.SeededStore.withSeededStore;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * What went wrong when the documents were read and made into a schema, recorded rather than
 * re-derived.
 *
 * <p>Driven through {@link SdlCapture} rather than through the writer, because the claim is that a
 * reading records its own verdict. A case that ran the three stages itself would pass over a
 * capture that recorded none of them.
 *
 * <p>One relation for all three stages, the stage a column, so each case asserts which stage spoke
 * as well as what it said. Absence is success and is meant to be relied on: a graph that made a
 * schema has no rows here.
 */
class SdlSchemaProblemsTest {

    private static final String GRAPH = "problems";

    @Test
    @DisplayName("a corpus that makes a schema records no problem")
    void aCorpusThatBuildsRecordsNothing(@TempDir Path tmp) {
        write(tmp, "ok.graphqls", "type Query { a: String }\n");

        withSeededStore(GRAPH, dsl -> {
            read(dsl, tmp);
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
        write(tmp, "first.graphqls", "type Query { a: String }\ntype X { a: String }\n");
        write(tmp, "second.graphqls", "type X { b: Int }\n");

        withSeededStore(GRAPH, dsl -> {
            read(dsl, tmp);

            assertThat(dsl.select(GRAPHQL_SCHEMA_PROBLEM.STAGE, GRAPHQL_SCHEMA_PROBLEM.ERROR_CLASS,
                    GRAPHQL_SCHEMA_PROBLEM.MESSAGE).from(GRAPHQL_SCHEMA_PROBLEM).fetch())
                .as("graphql-java's own class and sentence, kept verbatim, and the stage that spoke")
                .anySatisfy(row -> {
                    assertThat(row.value1()).isEqualTo("REGISTRY");
                    assertThat(row.value2()).isEqualTo("TypeRedefinitionError");
                    assertThat(row.value3()).contains("tried to redefine existing");
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
        write(tmp, "dangling.graphqls", "type Query { a: Missing }\n");

        withSeededStore(GRAPH, dsl -> {
            read(dsl, tmp);
            assertThat(dsl.select(GRAPHQL_SCHEMA_PROBLEM.STAGE, GRAPHQL_SCHEMA_PROBLEM.ERROR_CLASS)
                    .from(GRAPHQL_SCHEMA_PROBLEM).fetch())
                .as("nothing was declared twice, so only the build could have caught this")
                .anySatisfy(row -> {
                    assertThat(row.value1()).isEqualTo("ASSEMBLY");
                    assertThat(row.value2()).isEqualTo("MissingTypeError");
                });
        });
    }

    /**
     * A file the parser rejected. It contributes no entries at all, so a reading that did not record
     * the refusal would leave the file missing from the store rather than reported, and missing is
     * what a file nobody configured looks like.
     */
    @Test
    @DisplayName("a file that will not parse is a problem naming it, and the file is still registered")
    void aFileThatWillNotParseIsRecorded(@TempDir Path tmp) {
        write(tmp, "ok.graphqls", "type Query { a: String }\n");
        Path broken = write(tmp, "broken.graphqls", "type Film { title: \n");

        withSeededStore(GRAPH, dsl -> {
            read(dsl, tmp);

            assertThat(dsl.select(GRAPHQL_SCHEMA_PROBLEM.STAGE, GRAPHQL_SCHEMA_PROBLEM.SOURCE_NAME,
                    GRAPHQL_SCHEMA_PROBLEM.ERROR_CLASS).from(GRAPHQL_SCHEMA_PROBLEM).fetch())
                .as("the parser's refusal, attributed to the file it refused, under graphql-java's "
                    + "own class for it. An unterminated declaration is the base class itself; a "
                    + "document that parses and then does not stop is a subclass, which is why the "
                    + "diagnostic view reads this column rather than spelling one name as a literal")
                .anySatisfy(row -> {
                    assertThat(row.value1()).isEqualTo("PARSE");
                    assertThat(row.value2()).endsWith("broken.graphqls");
                    assertThat(row.value3()).isEqualTo("InvalidSyntaxException");
                });

            assertThat(dsl.fetchCount(STORE_SOURCE,
                    STORE_SOURCE.SOURCE_NAME.eq(broken.toString())))
                .as("and the file is in the registry: read and unreadable, rather than absent")
                .isEqualTo(1);

            assertThat(dsl.fetchCount(GRAPHQL_AST_TYPE_DECLARATION_ENTRY,
                    GRAPHQL_AST_TYPE_DECLARATION_ENTRY.NAME.eq("Query")))
                .as("its sibling still landed, a refusal costing only the file that earned it")
                .isEqualTo(1);
        });
    }

    @Test
    @DisplayName("a problem the corpus no longer has is swept")
    void aProblemTheCorpusNoLongerHasIsSwept(@TempDir Path tmp) {
        write(tmp, "fixme.graphqls", "type Query { a: Missing }\n");

        withSeededStore(GRAPH, dsl -> {
            read(dsl, tmp);
            assertThat(dsl.fetchCount(GRAPHQL_SCHEMA_PROBLEM)).as("before the fix").isNotZero();

            write(tmp, "fixme.graphqls", "type Query { a: String }\n");
            read(dsl, tmp);

            assertThat(dsl.fetchCount(GRAPHQL_SCHEMA_PROBLEM))
                .as("the author fixed it, so the store stops claiming a problem that is over")
                .isZero();
        });
    }

    /**
     * The sweep is per graph, so one graph being read says nothing about another's problems. Worth
     * a case of its own because the sweep deletes by instant rather than by key: a delete that
     * forgot its graph predicate would empty every sibling partition and no per-graph assertion
     * above would see it.
     */
    @Test
    @DisplayName("reading one graph leaves another graph's problems alone")
    void theSweepDoesNotReachAnotherGraph(@TempDir Path tmp) throws IOException {
        Path broken = Files.createDirectories(tmp.resolve("broken"));
        Path clean = Files.createDirectories(tmp.resolve("clean"));
        write(broken, "dangling.graphqls", "type Query { a: Missing }\n");
        write(clean, "ok.graphqls", "type Query { a: String }\n");

        withSeededStore(GRAPH, dsl -> {
            // The sibling's anchor row, which SdlCapture no longer mints: the graph is the
            // registry row every relation it writes hangs a key on, so ModelCapture writes it and
            // a case reading a second graph states it the way withSeededStore states the first.
            seedGraph(dsl, "sibling");
            read(dsl, GRAPH, broken);
            assertThat(dsl.fetchCount(GRAPHQL_SCHEMA_PROBLEM,
                    GRAPHQL_SCHEMA_PROBLEM.GRAPH_NAME.eq(GRAPH)))
                .as("the graph with the dangling reference").isNotZero();

            read(dsl, "sibling", clean);

            assertThat(dsl.fetchCount(GRAPHQL_SCHEMA_PROBLEM,
                    GRAPHQL_SCHEMA_PROBLEM.GRAPH_NAME.eq(GRAPH)))
                .as("a clean reading of another graph is not a fix for this one")
                .isNotZero();
            assertThat(dsl.fetchCount(GRAPHQL_SCHEMA_PROBLEM,
                    GRAPHQL_SCHEMA_PROBLEM.GRAPH_NAME.eq("sibling")))
                .as("and the graph that read clean has none of its own").isZero();
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
        write(tmp, "many.graphqls",
            "type Query { one: Gone, two: Gone, three: [Gone!]!, four: Gone, five: Gone }\n");

        withSeededStore(GRAPH, dsl -> {
            read(dsl, tmp);

            assertThat(dsl.select(GRAPHQL_SCHEMA_PROBLEM.ERROR_CLASS, GRAPHQL_SCHEMA_PROBLEM.MESSAGE)
                    .from(GRAPHQL_SCHEMA_PROBLEM).fetch())
                .as("one absent type is one problem, however many fields reached for it")
                .hasSize(1)
                .allSatisfy(row -> assertThat(row.value1()).isEqualTo("MissingTypeError"));
        });
    }

    /** One reading of everything the directory holds, which is what a run does. */
    private static void read(DSLContext dsl, Path baseDir) {
        read(dsl, GRAPH, baseDir);
    }

    /** {@link #read(DSLContext, Path)} under a graph the case names, for the two-graph case. */
    private static void read(DSLContext dsl, String graph, Path baseDir) {
        SdlCapture.capture(dsl, new GraphIdentity(graph, baseDir),
            SubjectConfig.of(new SchemaRecipe(baseDir.resolve("pom.xml"),
                List.of(SchemaRecipe.Binding.pattern("*.graphqls")), List.of("graphqls"))),
            LocalDateTime.now());
    }

    private static Path write(Path directory, String name, String sdl) {
        try {
            return Files.writeString(directory.resolve(name), sdl, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
