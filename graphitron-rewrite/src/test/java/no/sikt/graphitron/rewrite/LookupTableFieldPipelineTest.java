package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.generators.TypeClassGenerator;
import no.sikt.graphitron.rewrite.generators.TypeFetcherGenerator;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_JOOQ_PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * SDL → classified schema → generated {@code TypeSpec} pipeline tests for inline
 * {@link no.sikt.graphitron.rewrite.model.ChildField.LookupTableField} emission (argres Phase 2a).
 *
 * <p>Verifies C1's structural contract: the {@code TypeClassGenerator.$fields} method contains
 * a switch arm for each child-lookup field; the input-rows helper is emitted on the type class;
 * no fetcher method lands in {@code *Fetchers}; and classifier rejection for {@code @asConnection}
 * or single cardinality on an inline {@code @lookupKey} field produces {@code UnclassifiedField}.
 */
class LookupTableFieldPipelineTest {

    @BeforeEach
    void setup() {
        RewriteConfig.setProperties(java.util.Set.of(), "", "fake.code.generated", DEFAULT_JOOQ_PACKAGE, java.util.Map.of());
    }

    @AfterEach
    void teardown() {
        RewriteConfig.clear();
    }

    @Test
    void listLookupKey_producesSwitchArmAndInputRowsHelper() {
        var schema = TestSchemaHelper.buildSchema("""
            type Actor @table(name: "actor") { name: String }
            type Film @table(name: "film") {
                actors(actor_id: [Int!]! @lookupKey): [Actor!]!
            }
            type Query { film: Film }
            """);

        var filmClass = TypeClassGenerator.generate(schema).stream()
            .filter(t -> t.name().equals("Film"))
            .findFirst()
            .orElseThrow();

        var methodNames = filmClass.methodSpecs().stream().map(m -> m.name()).toList();
        assertThat(methodNames).contains("$fields", "actorsInputRows");

        var dollarFields = filmClass.methodSpecs().stream()
            .filter(m -> m.name().equals("$fields")).findFirst().orElseThrow();
        assertThat(dollarFields.code().toString()).contains("case \"actors\"");
    }

    @Test
    void lookupTableField_producesNoFetcherMethod() {
        var schema = TestSchemaHelper.buildSchema("""
            type Actor @table(name: "actor") { name: String }
            type Film @table(name: "film") {
                actors(actor_id: [Int!]! @lookupKey): [Actor!]!
            }
            type Query { film: Film }
            """);

        var filmFetchers = TypeFetcherGenerator.generate(schema).stream()
            .filter(t -> t.name().equals("FilmFetchers"))
            .findFirst()
            .orElseThrow();

        var methodNames = filmFetchers.methodSpecs().stream().map(m -> m.name()).toList();
        assertThat(methodNames)
            .as("LookupTableField projects inline via TypeClassGenerator.$fields — no fetcher method")
            .doesNotContain("actors");
    }

    @Test
    void asConnectionOnInlineLookupKey_classifiesAsUnclassifiedField() {
        var schema = TestSchemaHelper.buildSchema("""
            type Actor @table(name: "actor") { name: String }
            type Film @table(name: "film") {
                actors(actor_id: [Int!]! @lookupKey, first: Int, after: String): ActorConnection @asConnection
            }
            type ActorConnection { edges: [ActorEdge!]! }
            type ActorEdge { node: Actor! cursor: String! }
            type Query { film: Film }
            """);

        var field = schema.field("Film", "actors");
        assertThat(field).isInstanceOf(GraphitronField.UnclassifiedField.class);
        assertThat(((GraphitronField.UnclassifiedField) field).reason())
            .contains("@asConnection on inline (non-@splitQuery) LookupTableField");
    }

    @Test
    void singleCardinalityLookupKey_classifiesAsUnclassifiedField() {
        var schema = TestSchemaHelper.buildSchema("""
            type Actor @table(name: "actor") { name: String }
            type Film @table(name: "film") {
                actor(actor_id: ID! @lookupKey): Actor
            }
            type Query { film: Film }
            """);

        var field = schema.field("Film", "actor");
        assertThat(field).isInstanceOf(GraphitronField.UnclassifiedField.class);
        assertThat(((GraphitronField.UnclassifiedField) field).reason())
            .contains("Single-cardinality @lookupKey is not supported");
    }
}
