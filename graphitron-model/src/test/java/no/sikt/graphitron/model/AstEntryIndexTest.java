package no.sikt.graphitron.model;

import no.sikt.graphitron.model.capture.document.GraphQLAstCapture;
import no.sikt.graphitron.model.capture.document.GraphQLSourceCapture;
import no.sikt.graphitron.model.capture.document.GraphitronAstCapture;
import no.sikt.graphitron.model.capture.sdl.SdlFactCapture;
import no.sikt.graphitron.model.run.GraphIdentity;
import no.sikt.graphitron.model.vocabulary.EntryKind;
import no.sikt.graphitron.model.run.SubjectConfig;
import no.sikt.graphitron.model.schema.SchemaLoader;
import no.sikt.graphitron.model.schema.input.SchemaInput;
import no.sikt.graphitron.model.schema.input.SchemaRecipe;
import no.sikt.graphitron.model.sink.FactSink;
import no.sikt.graphitron.model.sources.ClasspathSources;
import org.jooq.DSLContext;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_VALUE_ENTRY;
import static no.sikt.graphitron.model.test.SeededStore.seedSource;
import static no.sikt.graphitron.model.test.SeededStore.withSeededStore;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The entry index: one row per written position the entry stratum holds, each naming the schema
 * element it sits in.
 *
 * <p>The population claim is asserted against the entry relations themselves rather than against a
 * count this file carries, so an arm added to the stratum and forgotten in the union fails here
 * instead of leaving a position nothing can reference. The resolution claim is asserted at the
 * depth that makes it worth having: a string written three levels inside a directive's argument
 * names the field the directive was applied to, because that is the element an author reading the
 * line is looking at.
 */
class AstEntryIndexTest {

    private static final String GRAPH = "ast-entry-index";

    private static final String SDL = """
        type Widget @key(fields: "id") {
          id: ID!
          name(locale: String = "nb"): String @field(name: "title")
        }

        interface Named { name: String }

        enum WidgetKind { SMALL @deprecated(reason: "unused") LARGE }

        input WidgetFilterInput { namePrefix: String }

        type Query {
          widgets(filter: WidgetFilterInput): [Widget!]!
        }
        """;

    @Test
    @DisplayName("every entry position is indexed exactly once")
    void thePopulationIsTheEntryStratum() {
        withCapture(dsl -> {
            for (var arm : ENTRY_RELATIONS) {
                var missing = dsl.fetchCount(dsl.selectFrom(arm)
                    .where(arm.field(GRAPHQL_AST_ENTRY.GRAPH_NAME).eq(GRAPH))
                    .andNotExists(dsl.selectOne().from(GRAPHQL_AST_ENTRY)
                        .where(GRAPHQL_AST_ENTRY.GRAPH_NAME.eq(GRAPH))
                        .and(GRAPHQL_AST_ENTRY.SOURCE_NAME
                            .eq(arm.field(GRAPHQL_AST_ENTRY.SOURCE_NAME)))
                        .and(GRAPHQL_AST_ENTRY.SOURCE_LINE
                            .eq(arm.field(GRAPHQL_AST_ENTRY.SOURCE_LINE)))
                        .and(GRAPHQL_AST_ENTRY.SOURCE_COLUMN
                            .eq(arm.field(GRAPHQL_AST_ENTRY.SOURCE_COLUMN)))));
                assertThat(missing)
                    .as("%s positions absent from the index", arm.getName())
                    .isZero();
            }
        });
    }

    @Test
    @DisplayName("a declaration names its own element and an application names the one it sits on")
    void resolutionFollowsTheEnclosingElement() {
        withCapture(dsl -> {
            assertThat(kindsAndCoordinates(dsl, EntryKind.FIELD_DEFINITION))
                .as("a field declares its own coordinate")
                .contains("Widget.name");
            assertThat(kindsAndCoordinates(dsl, EntryKind.FIELD_DIRECTIVE))
                .as("a directive on a field names the field, not itself")
                .contains("Widget.name");
            assertThat(kindsAndCoordinates(dsl, EntryKind.FIELD_ARGUMENT))
                .as("an argument declares its own coordinate")
                .contains("Widget.name(locale:)");
            assertThat(kindsAndCoordinates(dsl, EntryKind.TYPE_DIRECTIVE))
                .as("a directive on a type names the type")
                .contains("Widget");
            assertThat(kindsAndCoordinates(dsl, EntryKind.ENUM_VALUE_DIRECTIVE))
                .as("a directive on an enum value names the value")
                .contains("WidgetKind.SMALL");
        });
    }

    @Test
    @DisplayName("a value names the element the expression it sits in was written on")
    void valuesResolveThroughTheirHolder() {
        withCapture(dsl -> {
            assertThat(kindsAndCoordinates(dsl, EntryKind.APPLIED_ARGUMENT))
                .as("an argument passed to a directive names the element the directive is on")
                .contains("Widget", "Widget.name", "WidgetKind.SMALL");
            assertThat(kindsAndCoordinates(dsl, EntryKind.VALUE))
                .as("and so does every value written inside it")
                .contains("Widget", "Widget.name", "WidgetKind.SMALL");
        });
    }

    @Test
    @DisplayName("every value entry is indexed, however deeply it nests")
    void theValueFixpointReachesEveryDepth() {
        withCapture(dsl -> {
            var values = dsl.fetchCount(GRAPHQL_AST_VALUE_ENTRY,
                GRAPHQL_AST_VALUE_ENTRY.GRAPH_NAME.eq(GRAPH));
            var indexed = dsl.fetchCount(GRAPHQL_AST_ENTRY,
                GRAPHQL_AST_ENTRY.GRAPH_NAME.eq(GRAPH)
                    .and(GRAPHQL_AST_ENTRY.ENTRY_KIND.eq(EntryKind.VALUE)));
            assertThat(indexed).as("indexed values against written ones").isEqualTo(values);
            assertThat(values).as("the fixture writes values at all").isPositive();
        });
    }

    /**
     * The index is swept with the anchors beside it, so a position the corpus stopped holding
     * leaves rather than lingering with a stale instant.
     */
    @Test
    @DisplayName("a position the corpus no longer holds is swept")
    void theSweepRemovesRetiredPositions() {
        withSeededStore(GRAPH, dsl -> {
            Path directory = temporaryDirectory();
            write(directory, "schema.graphqls", SDL);
            read(dsl, directory);
            var before = dsl.fetchCount(GRAPHQL_AST_ENTRY, GRAPHQL_AST_ENTRY.GRAPH_NAME.eq(GRAPH));

            write(directory, "schema.graphqls", "type Query { widgets: String }\n");
            read(dsl, directory);
            var after = dsl.fetchCount(GRAPHQL_AST_ENTRY, GRAPHQL_AST_ENTRY.GRAPH_NAME.eq(GRAPH));

            assertThat(after).as("the smaller corpus indexes fewer positions").isLessThan(before);
            assertThat(dsl.fetchCount(GRAPHQL_AST_ENTRY,
                GRAPHQL_AST_ENTRY.GRAPH_NAME.eq(GRAPH)
                    .and(GRAPHQL_AST_ENTRY.ELEMENT_COORDINATE.like("Widget%"))))
                .as("nothing survives for the type the author deleted")
                .isZero();
        });
    }

    /**
     * The other reading of the same documents, and the reason the index is written by a step of its
     * own rather than by the anchor writer. A pass that walks the registry writes the element
     * anchors itself and therefore skips that writer whole, so an index written inside it was
     * written by one reading of the two; the reader that surfaced this was a graphitron anchor
     * referencing a written position, which found no index row to reference in the only pass its
     * own stages run in.
     */
    @Test
    @DisplayName("the walk's pass indexes the stratum too, not only the derivation's")
    void theWalksPassWritesTheIndex() {
        withSeededStore(GRAPH, dsl -> {
            Path directory = temporaryDirectory();
            write(directory, "schema.graphqls", SDL);
            var graph = new GraphIdentity(GRAPH, directory);
            LocalDateTime readAt = LocalDateTime.now().withNano(0);
            // The walk's pass parses for itself and owns its own source rows, so the documents are
            // assembled here rather than by the corpus reader, whose membership rows the walk's own
            // sink writes below and would meet on their key.
            var parse = SchemaLoader.parsePerSource(corpus(directory).schemaFiles(directory));
            seedSource(dsl, SchemaLoader.DIRECTIVES_SOURCE_NAME, "SCHEMA_FILE");
            seedSource(dsl, directory.resolve("schema.graphqls").toString(), "SCHEMA_FILE");
            var documents = parse.perSource().stream()
                .map(source -> new GraphQLSourceCapture.SourceDocument(
                    source.sourceName(), source.registry(), true))
                .toList();
            // The transcription alone, which is the half the walk's pass runs. It skips the anchor
            // step that follows it there, the walk being that pass's producer of the anchors.
            GraphQLAstCapture.captureEntries(dsl, graph, documents, readAt);
            // The walk, which is this pass's producer of the element anchors the index keys into.
            var sink = new FactSink(dsl, GRAPH, readAt);
            SdlFactCapture.capture(sink, parse.registry(), new ClasspathSources(),
                Map.of(directory.resolve("schema.graphqls").toString(),
                    SchemaInput.file(directory.resolve("schema.graphqls"))),
                Set.of());
            sink.flush();
            GraphQLAstCapture.captureAstIndex(dsl, graph, readAt);

            assertThat(dsl.fetchCount(GRAPHQL_AST_ENTRY, GRAPHQL_AST_ENTRY.GRAPH_NAME.eq(GRAPH)))
                .as("the pass that writes the anchors reading this index also fills it")
                .isPositive();
            assertThat(kindsAndCoordinates(dsl, EntryKind.FIELD_DIRECTIVE))
                .as("and resolves each position the same way, a directive naming its field")
                .contains("Widget.name");
        });
    }

    /** The coordinates the index resolved for one entry kind. */
    private static List<String> kindsAndCoordinates(DSLContext dsl, EntryKind entryKind) {
        var e = GRAPHQL_AST_ENTRY;
        return dsl.select(e.ELEMENT_COORDINATE).from(e)
            .where(e.GRAPH_NAME.eq(GRAPH), e.ENTRY_KIND.eq(entryKind))
            .fetch(e.ELEMENT_COORDINATE);
    }

    private static final List<org.jooq.Table<?>> ENTRY_RELATIONS = List.of(
        Tables.GRAPHQL_AST_TYPE_DECLARATION_ENTRY, Tables.GRAPHQL_AST_DIRECTIVE_DEFINITION_ENTRY,
        Tables.GRAPHQL_AST_SCHEMA_DEFINITION_ENTRY, Tables.GRAPHQL_AST_FIELD_DEFINITION_ENTRY,
        Tables.GRAPHQL_AST_ENUM_VALUE_DEFINITION_ENTRY, Tables.GRAPHQL_AST_IMPLEMENTS_ENTRY,
        Tables.GRAPHQL_AST_UNION_MEMBER_ENTRY, Tables.GRAPHQL_AST_DIRECTIVE_LOCATION_ENTRY,
        Tables.GRAPHQL_AST_OPERATION_TYPE_DEFINITION_ENTRY, Tables.GRAPHQL_AST_FIELD_ARGUMENT_ENTRY,
        Tables.GRAPHQL_AST_INPUT_FIELD_ENTRY, Tables.GRAPHQL_AST_DIRECTIVE_ARGUMENT_ENTRY,
        Tables.GRAPHQL_AST_TYPE_DIRECTIVE_ENTRY, Tables.GRAPHQL_AST_FIELD_DIRECTIVE_ENTRY,
        Tables.GRAPHQL_AST_INPUT_VALUE_DIRECTIVE_ENTRY,
        Tables.GRAPHQL_AST_ENUM_VALUE_DIRECTIVE_ENTRY, Tables.GRAPHQL_AST_SCHEMA_DIRECTIVE_ENTRY,
        Tables.GRAPHQL_AST_APPLIED_ARGUMENT_ENTRY, Tables.GRAPHQL_AST_VALUE_ENTRY);

    private static void withCapture(java.util.function.Consumer<DSLContext> body) {
        withSeededStore(GRAPH, dsl -> {
            Path directory = temporaryDirectory();
            write(directory, "schema.graphqls", SDL);
            read(dsl, directory);
            body.accept(dsl);
        });
    }

    /** One reading of everything the directory holds, which is what a run does. */
    private static void read(DSLContext dsl, Path baseDir) {
        var graph = new GraphIdentity(GRAPH, baseDir);
        var readAt = LocalDateTime.now();
        var documents = GraphQLSourceCapture.capture(dsl, graph, corpus(baseDir), readAt);
        GraphQLAstCapture.capture(dsl, graph, documents, readAt);
        GraphitronAstCapture.capture(dsl, graph, documents, readAt);
    }

    /** The corpus a reading is of, stated as the configuration a run would have had. */
    private static SubjectConfig corpus(Path baseDir) {
        return SubjectConfig.of(new SchemaRecipe(baseDir.resolve("pom.xml"),
            List.of(SchemaRecipe.Binding.pattern("*.graphqls")), List.of("graphqls")));
    }

    private static void write(Path directory, String name, String sdl) {
        try {
            Files.writeString(directory.resolve(name), sdl, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static Path temporaryDirectory() {
        try {
            return Files.createTempDirectory("ast-entry-index");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
