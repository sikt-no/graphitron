package no.sikt.graphitron.model;

import no.sikt.graphitron.model.capture.document.SdlCapture;
import no.sikt.graphitron.model.run.GraphIdentity;
import no.sikt.graphitron.model.vocabulary.EntryKind;
import no.sikt.graphitron.model.run.SubjectConfig;
import no.sikt.graphitron.model.schema.input.SchemaRecipe;
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

import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_ENTRY;
import static no.sikt.graphitron.model.Tables.GRAPHQL_AST_VALUE_ENTRY;
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
        SdlCapture.captureFacts(dsl, new GraphIdentity(GRAPH, baseDir),
            SubjectConfig.of(new SchemaRecipe(baseDir.resolve("pom.xml"),
                List.of(SchemaRecipe.Binding.pattern("*.graphqls")), List.of("graphqls"))),
            LocalDateTime.now());
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
