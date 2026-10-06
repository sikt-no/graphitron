package no.sikt.graphitron.model;

import no.sikt.graphitron.model.capture.document.GraphQLAssemblyCapture;
import no.sikt.graphitron.model.capture.document.GraphQLAstCapture;
import no.sikt.graphitron.model.capture.document.GraphQLSourceCapture;
import no.sikt.graphitron.model.capture.document.GraphitronAstCapture;
import no.sikt.graphitron.model.run.GraphIdentity;
import no.sikt.graphitron.model.run.ModelCapture;
import no.sikt.graphitron.model.run.SubjectConfig;
import no.sikt.graphitron.model.schema.SchemaAssembly;
import no.sikt.graphitron.model.schema.SchemaLoader;
import no.sikt.graphitron.model.schema.input.SchemaRecipe;
import no.sikt.graphitron.model.schema.input.SchemaSource;
import no.sikt.graphitron.model.test.EntryFamilyFixture;
import org.jooq.DSLContext;
import org.jooq.Table;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

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
 * <p>Driven through the document gatherers rather than through the writer, because the claim is that a
 * reading records its own verdict. A case that ran the stages itself would pass over a capture that
 * recorded none of them.
 *
 * <p>One relation for every stage, the stage a column, so each case asserts which stage spoke
 * as well as what it said. Absence is success and is meant to be relied on: a graph that made a
 * schema has no rows here.
 */
class GraphQLSchemaProblemsTest {

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
            // The sibling's anchor row, which no document gatherer mints: the graph is the
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

    // The loading rewrites. The assembly judges the corpus as the generator composes it, so a
    // federation @link's imports are declarations and the configured tags are applied; a rewrite
    // that refuses is recorded at its own stage and the assembly judges the corpus as written.

    /** A schema shaped like a federated consumer's: it declares nothing the {@code @link} imports. */
    private static final String LINKED = """
        extend schema @link(url: "https://specs.apollo.dev/federation/v2.10",
                            import: ["@key", "@shareable", "@tag", "@inaccessible"])

        type Query { film: Film }

        type Film @key(fields: "id") @shareable {
          id: ID!
          title: String @tag(name: "public") @inaccessible
        }
        """;

    /** The regression: every imported directive is declared, so nothing is undeclared. */
    @Test
    @DisplayName("a federation @link's imports are declared, so a linked schema records nothing")
    void aLinkedSchemaRecordsNothing(@TempDir Path tmp) {
        write(tmp, "linked.graphqls", LINKED);

        withSeededStore(GRAPH, dsl -> {
            read(dsl, tmp);
            assertThat(dsl.select(GRAPHQL_SCHEMA_PROBLEM.STAGE, GRAPHQL_SCHEMA_PROBLEM.MESSAGE)
                    .from(GRAPHQL_SCHEMA_PROBLEM).fetch())
                .as("the @link imports @key, @shareable, @tag and @inaccessible, and @link itself")
                .isEmpty();
        });
    }

    /** A source two bindings claim has two answers for its tag, which the attribution refuses. */
    @Test
    @DisplayName("a source two bindings claim is a rewrite refusal, and the capture completes")
    void aSourceInTwoInputsIsRefused(@TempDir Path tmp) {
        Path file = write(tmp, "claimed.graphqls", "type Query { a: String }\n");

        withSeededStore(GRAPH, dsl -> {
            read(dsl, tmp, List.of(
                SchemaRecipe.Binding.pattern("*.graphqls"),
                SchemaRecipe.Binding.literal(SchemaSource.file(file))), LocalDateTime.now());

            assertRefusedOnce(dsl, "SourceInTwoInputs", file);
            assertThat(dsl.select(GRAPHQL_SCHEMA_PROBLEM.SOURCE_NAME).from(GRAPHQL_SCHEMA_PROBLEM)
                    .where(GRAPHQL_SCHEMA_PROBLEM.STAGE.eq("REWRITE"))
                    .fetchOne(GRAPHQL_SCHEMA_PROBLEM.SOURCE_NAME))
                .as("the row names the source both inputs claim")
                .isEqualTo(SchemaSource.file(file).sourceName());
        });
    }

    @Test
    @DisplayName("two federation @links are a rewrite refusal, and the assembly judges the merged corpus")
    void twoFederationLinksAreRefused(@TempDir Path tmp) {
        Path file = write(tmp, "links.graphqls", TWO_LINKS);

        withSeededStore(GRAPH, dsl -> {
            read(dsl, tmp);
            assertRefusedOnce(dsl, "MultipleFederationLinks", file);
        });
    }

    @Test
    @DisplayName("a federation version the library does not know is a rewrite refusal")
    void anUnsupportedFederationVersionIsRefused(@TempDir Path tmp) {
        Path file = write(tmp, "future.graphqls",
            LINKED.replace("federation/v2.10", "federation/v9.0"));

        withSeededStore(GRAPH, dsl -> {
            read(dsl, tmp);
            assertRefusedOnce(dsl, "UnsupportedFederationVersion", file);
        });
    }

    /** The author declaring what the {@code @link} imports was the workaround; it is now refused. */
    @Test
    @DisplayName("an author's own declaration of an imported directive is a rewrite refusal")
    void anAuthorsDeclarationCollides(@TempDir Path tmp) {
        Path file = write(tmp, "declared.graphqls", """
            directive @key(fields: String!, resolvable: Boolean) repeatable on OBJECT

            extend schema @link(url: "https://specs.apollo.dev/federation/v2.10", import: ["@key"])

            type Query { film: Film }
            type Film @key(fields: "id") { id: ID! }
            """);

        withSeededStore(GRAPH, dsl -> {
            read(dsl, tmp);
            assertRefusedOnce(dsl, "DeclarationCollision", file);
        });
    }

    @Test
    @DisplayName("a configured tag the author's @link does not import is a rewrite refusal")
    void aTagTheLinkDoesNotImportIsRefused(@TempDir Path tmp) {
        Path file = write(tmp, "untagged.graphqls", """
            extend schema @link(url: "https://specs.apollo.dev/federation/v2.10", import: ["@key"])

            type Query { film: Film }
            type Film @key(fields: "id") { id: ID! }
            """);

        withSeededStore(GRAPH, dsl -> {
            read(dsl, tmp, List.of(tagged("*.graphqls")), LocalDateTime.now());
            assertRefusedOnce(dsl, "TagNotImported", file);
        });
    }

    /**
     * The sweep names the rewrite stage, or a refusal the author fixed would stand forever and the
     * store would read as stale over a corpus that is sound.
     */
    @Test
    @DisplayName("a fixed rewrite refusal is swept on the next reading")
    void aFixedRefusalClears(@TempDir Path tmp) {
        write(tmp, "links.graphqls", TWO_LINKS);
        var first = LocalDateTime.of(2026, 1, 1, 12, 0);
        var second = first.plusMinutes(1);

        withSeededStore(GRAPH, dsl -> {
            read(dsl, tmp, List.of(SchemaRecipe.Binding.pattern("*.graphqls")), first);
            assertThat(dsl.fetchCount(GRAPHQL_SCHEMA_PROBLEM, GRAPHQL_SCHEMA_PROBLEM.STAGE.eq("REWRITE")))
                .as("the control: two @links are refused").isEqualTo(1);

            write(tmp, "links.graphqls", """
                extend schema @link(url: "https://specs.apollo.dev/federation/v2.10",
                                    import: ["@key", "@shareable"])

                type Query { film: Film }
                type Film @key(fields: "id") @shareable { id: ID! }
                """);
            read(dsl, tmp, List.of(SchemaRecipe.Binding.pattern("*.graphqls")), second);

            assertThat(dsl.select(GRAPHQL_SCHEMA_PROBLEM.STAGE, GRAPHQL_SCHEMA_PROBLEM.MESSAGE)
                    .from(GRAPHQL_SCHEMA_PROBLEM).fetch())
                .as("one @link now, so neither the refusal nor what the assembly said after it stands")
                .isEmpty();
        });
    }

    /** The synthesis path: with a tag configured and no {@code @link}, the rewrites write one. */
    @Test
    @DisplayName("a configured tag with no @link synthesises one, and records nothing")
    void aTagWithNoLinkRecordsNothing(@TempDir Path tmp) {
        write(tmp, "plain.graphqls", "type Query { films: [Film!] }\ntype Film { title: String }\n");

        withSeededStore(GRAPH, dsl -> {
            read(dsl, tmp, List.of(tagged("*.graphqls")), LocalDateTime.now());
            assertThat(dsl.select(GRAPHQL_SCHEMA_PROBLEM.STAGE, GRAPHQL_SCHEMA_PROBLEM.MESSAGE)
                    .from(GRAPHQL_SCHEMA_PROBLEM).fetch())
                .as("the synthesised @link imports @tag, so the tag applications are declared")
                .isEmpty();
        });
    }

    /**
     * The decode walks the corpus as written, so a tag configuration that changes what the
     * assembly judges changes no transcription and no decode. Driven through the whole pass, which
     * is the only place the decode runs.
     */
    @Test
    @DisplayName("the transcription and the decode do not vary with the tag configuration")
    void theDecodeDoesNotVaryWithConfiguration(@TempDir Path tmp) {
        write(tmp, "linked.graphqls", LINKED);
        var readAt = LocalDateTime.of(2026, 1, 1, 12, 0);

        var untagged = new LinkedHashMap<String, List<String>>();
        withSeededStore(GRAPH, dsl -> {
            capture(dsl, tmp, SchemaRecipe.Binding.pattern("*.graphqls"), readAt);
            untagged.putAll(transcribed(dsl));
        });
        var tagged = new LinkedHashMap<String, List<String>>();
        withSeededStore(GRAPH, dsl -> {
            capture(dsl, tmp, tagged("*.graphqls"), readAt);
            assertThat(dsl.fetchCount(GRAPHQL_SCHEMA_PROBLEM))
                .as("the control: the tagged arm composed and assembled cleanly").isZero();
            tagged.putAll(transcribed(dsl));
        });

        assertThat(untagged.values().stream().filter(rows -> !rows.isEmpty()).count())
            .as("a comparison over relations nobody wrote to agrees by being empty")
            .isGreaterThan(5);
        var differing = new ArrayList<String>();
        untagged.forEach((relation, rows) -> {
            if (!rows.equals(tagged.get(relation))) {
                differing.add(relation);
            }
        });
        assertThat(differing).as("relations whose rows varied with the tag configuration").isEmpty();
    }

    /**
     * The attribution reaches the assembly through the whole pass, not only when a case drives the
     * gatherers one at a time. A pass that dropped the reading's inputs between the source gatherer
     * and the assembly would compose this corpus untagged, which refuses nothing.
     */
    @Test
    @DisplayName("the pass hands the recipe's tags to the assembly")
    void thePassCarriesTheAttribution(@TempDir Path tmp) {
        write(tmp, "untagged.graphqls", """
            extend schema @link(url: "https://specs.apollo.dev/federation/v2.10", import: ["@key"])

            type Query { film: Film }
            type Film @key(fields: "id") { id: ID! }
            """);

        withSeededStore(GRAPH, dsl -> {
            capture(dsl, tmp, tagged("*.graphqls"), LocalDateTime.now());
            assertThat(dsl.select(GRAPHQL_SCHEMA_PROBLEM.ERROR_CLASS).from(GRAPHQL_SCHEMA_PROBLEM)
                    .where(GRAPHQL_SCHEMA_PROBLEM.STAGE.eq("REWRITE"))
                    .fetch(GRAPHQL_SCHEMA_PROBLEM.ERROR_CLASS))
                .as("the tag is configured and the @link does not import @tag")
                .containsExactly("TagNotImported");
        });
    }

    private static final String TWO_LINKS = """
        extend schema @link(url: "https://specs.apollo.dev/federation/v2.10", import: ["@key"])
        extend schema @link(url: "https://specs.apollo.dev/federation/v2.9", import: ["@shareable"])

        type Query { film: Film }
        type Film @key(fields: "id") @shareable { id: ID! }
        """;

    private static SchemaRecipe.Binding tagged(String glob) {
        return new SchemaRecipe.Binding(new SchemaRecipe.Entry.Pattern(glob),
            Optional.of("public"), Optional.empty());
    }

    /**
     * Exactly one rewrite row, naming {@code variant}, and the assembly rows after it the merged
     * corpus's own: what graphql-java says of the documents as written, computed here from the
     * files rather than read back from anything the capture holds.
     */
    private static void assertRefusedOnce(DSLContext dsl, String variant, Path file) {
        var p = GRAPHQL_SCHEMA_PROBLEM;
        assertThat(dsl.select(p.ERROR_CLASS).from(p).where(p.STAGE.eq("REWRITE")).fetch(p.ERROR_CLASS))
            .as("one refusal, the composition stopping at the first")
            .containsExactly(variant);

        var asWritten = SchemaAssembly.of(
            SchemaLoader.parsePerSource(List.of(SchemaSource.file(file))).registry());
        var expected = asWritten.errors().stream()
            .map(error -> error.errorClass() + ": " + error.message())
            .distinct().toList();
        assertThat(dsl.select(p.ERROR_CLASS, p.MESSAGE).from(p).where(p.STAGE.eq("ASSEMBLY"))
                .fetch(row -> row.value1() + ": " + row.value2()))
            .as("the assembly judged the merged corpus rather than a composition stopped partway")
            .containsExactlyInAnyOrderElementsOf(expected);
    }

    /** One whole pass, decode and derivations included, over the directory under one binding. */
    private static void capture(DSLContext dsl, Path baseDir, SchemaRecipe.Binding binding,
                                LocalDateTime readAt) {
        ModelCapture.capture(dsl, new GraphIdentity(GRAPH, baseDir),
            SubjectConfig.of(new SchemaRecipe(baseDir.resolve("pom.xml"), List.of(binding),
                List.of("graphqls"))),
            List.of(), null, readAt);
    }

    /**
     * Every {@code graphql_} relation but the two the composition writes, and the
     * {@code graphitron_} entry relations,
     * as sorted rendered rows: the transcription and the decode, which are functions of the
     * documents alone.
     */
    private static Map<String, List<String>> transcribed(DSLContext dsl) {
        var rows = new LinkedHashMap<String, List<String>>();
        for (Table<?> relation : Public.PUBLIC.getTables()) {
            String name = relation.getName().toLowerCase(Locale.ROOT);
            boolean inScope = (name.startsWith("graphql_") && !name.equals("graphql_schema_problem")
                    && !name.equals("graphql_assembly_synthesised_link"))
                || EntryFamilyFixture.entryRelations().contains(name);
            if (inScope) {
                rows.put(name, dsl.selectFrom(relation).fetch().stream()
                    .map(record -> record.intoList().toString()).sorted().toList());
            }
        }
        return rows;
    }

    /** One reading of everything the directory holds, which is what a run does. */
    private static void read(DSLContext dsl, Path baseDir) {
        read(dsl, GRAPH, baseDir);
    }

    /** {@link #read(DSLContext, Path)} under a graph the case names, for the two-graph case. */
    private static void read(DSLContext dsl, String graphName, Path baseDir) {
        read(dsl, graphName, baseDir, List.of(SchemaRecipe.Binding.pattern("*.graphqls")),
            LocalDateTime.now());
    }

    /** {@link #read(DSLContext, Path)} under bindings the case states, at an instant it states. */
    private static void read(DSLContext dsl, Path baseDir, List<SchemaRecipe.Binding> bindings,
                             LocalDateTime readAt) {
        read(dsl, GRAPH, baseDir, bindings, readAt);
    }

    private static void read(DSLContext dsl, String graphName, Path baseDir,
                             List<SchemaRecipe.Binding> bindings, LocalDateTime readAt) {
        var graph = new GraphIdentity(graphName, baseDir);
        var config = SubjectConfig.of(new SchemaRecipe(baseDir.resolve("pom.xml"), bindings,
            List.of("graphqls")));
        var reading = GraphQLSourceCapture.capture(dsl, graph, config, readAt);
        var documents = reading.documents();
        GraphQLAstCapture.capture(dsl, graph, documents, readAt);
        GraphitronAstCapture.capture(dsl, graph, documents, readAt);
        GraphQLAssemblyCapture.capture(dsl, graph, reading, readAt);
    }

    private static Path write(Path directory, String name, String sdl) {
        try {
            return Files.writeString(directory.resolve(name), sdl, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
