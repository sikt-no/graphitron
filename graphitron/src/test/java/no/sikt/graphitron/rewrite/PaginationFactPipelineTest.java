package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.model.FieldWrapper;
import no.sikt.graphitron.rewrite.model.QueryField;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The pagination fact's population and view pins, asserted through the readers so an empty or
 * drifted relation fails here rather than surfacing as a silently absent component: the
 * authored population fills the spec's role slots, the inferred population synthesizes the
 * forward defaults, both default-page-size materialisations (the wrapper the emitters read and
 * the rebuilt schema's injected {@code first} argument) agree because they read one view, and a
 * coordinate outside both populations carries nothing. The directive-argument read itself has
 * one lexical home, pinned last.
 */
@PipelineTier
class PaginationFactPipelineTest {

    @Test
    void authoredArgs_fillTheirRoleSlots() {
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { title: String }
            type Query { films(first: Int, after: String): [Film!]! @defaultOrder(primaryKey: true) }
            """);
        var f = (QueryField.QueryTableField) schema.field("Query", "films");
        assertThat(f.pagination()).isNotNull();
        assertThat(f.pagination().first()).isNotNull();
        assertThat(f.pagination().after()).isNotNull();
        assertThat(f.pagination().last()).isNull();
        assertThat(f.pagination().before()).isNull();
    }

    @Test
    void authoredBackwardArgs_fillTheBackwardSlots() {
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { title: String }
            type Query { films(last: Int, before: String): [Film!]! @defaultOrder(primaryKey: true) }
            """);
        var f = (QueryField.QueryTableField) schema.field("Query", "films");
        assertThat(f.pagination()).isNotNull();
        assertThat(f.pagination().last()).isNotNull();
        assertThat(f.pagination().before()).isNotNull();
        assertThat(f.pagination().first()).isNull();
    }

    @Test
    void connectionDirective_withNoAuthoredSlot_synthesizesForwardDefaults() {
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { title: String }
            type Query { films: [Film!]! @asConnection @defaultOrder(primaryKey: true) }
            """);
        var f = (QueryField.QueryTableField) schema.field("Query", "films");
        assertThat(f.pagination()).isNotNull();
        assertThat(f.pagination().first()).isNotNull();
        assertThat(f.pagination().after()).isNotNull();
        assertThat(f.returnType().wrapper()).isInstanceOf(FieldWrapper.Connection.class);
        assertThat(((FieldWrapper.Connection) f.returnType().wrapper()).defaultPageSize())
            .isEqualTo(FieldWrapper.DEFAULT_PAGE_SIZE);
    }

    @Test
    void authoredDefaultFirstValue_reachesBothMaterialisationsThroughOneView() {
        // The wrapper the emitters read and the rebuilt assembled schema's injected `first`
        // argument each materialise the default page size; both read the pagination fact's one
        // resolved view, so they agree by construction. This is the agreement the retired
        // second directive-read copy could silently break.
        var bundle = TestSchemaHelper.buildBundle("""
            type Film @table(name: "film") { title: String }
            type Query { films: [Film!]! @asConnection(defaultFirstValue: 25) @defaultOrder(primaryKey: true) }
            """);
        var f = (QueryField.QueryTableField) bundle.model().field("Query", "films");
        assertThat(((FieldWrapper.Connection) f.returnType().wrapper()).defaultPageSize()).isEqualTo(25);

        var firstArg = bundle.assembled().getObjectType("Query")
            .getFieldDefinition("films").getArgument("first");
        Object injectedDefault = graphql.schema.GraphQLArgument.getArgumentDefaultValue(firstArg);
        assertThat(injectedDefault).isEqualTo(25);
    }

    @Test
    void coordinateOutsideBothPopulations_carriesNoSpec() {
        var schema = TestSchemaHelper.buildSchema("""
            type Film @table(name: "film") { title: String }
            type Query { films: [Film!]! @defaultOrder(primaryKey: true) }
            """);
        var f = (QueryField.QueryTableField) schema.field("Query", "films");
        assertThat(f.pagination()).isNull();
    }

    @Test
    void directiveArgumentRead_hasOneLexicalHome() throws IOException {
        // The defaultFirstValue coercion lives once, in the pagination fact's gather; a second
        // literal read site is the drift this fact was migrated to remove. The capture package is
        // out of scope on purpose: it is the other model, and its job is precisely to read the
        // directive. It records the authored literal and applies no default, so it is not a
        // second coercion site and cannot drift from the gathered row.
        var mainRoot = GuardScope.locateRepoRoot().resolve("graphitron/src/main/java/no/sikt/graphitron");
        try (Stream<java.nio.file.Path> files = Files.walk(mainRoot)) {
            var offenders = files
                .filter(p -> p.toString().endsWith(".java"))
                .filter(p -> !p.toString().contains("/facts/"))
                .filter(p -> !p.toString().contains("/capture/"))
                .filter(p -> {
                    try {
                        return Files.readString(p).contains("\"defaultFirstValue\"");
                    } catch (IOException e) {
                        throw new java.io.UncheckedIOException(e);
                    }
                })
                .toList();
            assertThat(offenders)
                .as("the defaultFirstValue directive argument is coerced only by the pagination"
                    + " fact's gather (capture excepted); read the gathered row instead of the"
                    + " directive")
                .isEmpty();
        }
    }
}
