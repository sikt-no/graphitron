package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.generators.TypeClassGenerator;
import no.sikt.graphitron.rewrite.generators.TypeFetcherGenerator;
import no.sikt.graphitron.rewrite.generators.util.TypeSpecAssertions;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import org.junit.jupiter.api.Test;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_JOOQ_PACKAGE;
import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_OUTPUT_PACKAGE;
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

    @Test
    void listLookupKey_producesSwitchArmAndInputRowsHelper() {
        var schema = TestSchemaHelper.buildSchema("""
            type Actor @table(name: "actor") { name: String }
            type Film @table(name: "film") {
                actors(actor_id: [Int!]! @lookupKey): [Actor!]!
                    @reference(path: [{key: "film_actor_film_id_fkey"}, {key: "film_actor_actor_id_fkey"}])
            }
            type Query { film: Film }
            """);

        var filmClass = TypeClassGenerator.generate(schema, DEFAULT_OUTPUT_PACKAGE, DEFAULT_JOOQ_PACKAGE).stream()
            .filter(t -> t.name().equals("Film"))
            .findFirst()
            .orElseThrow();

        var methodNames = filmClass.methodSpecs().stream().map(m -> m.name()).toList();
        assertThat(methodNames).contains("$fields", "actorsInputRows");

        assertThat(TypeSpecAssertions.hasFieldsArm(filmClass, "actors")).isTrue();
    }

    @Test
    void lookupTableField_producesNoFetcherMethod() {
        var schema = TestSchemaHelper.buildSchema("""
            type Actor @table(name: "actor") { name: String }
            type Film @table(name: "film") {
                actors(actor_id: [Int!]! @lookupKey): [Actor!]!
                    @reference(path: [{key: "film_actor_film_id_fkey"}, {key: "film_actor_actor_id_fkey"}])
            }
            type Query { film: Film }
            """);

        var filmFetchers = TypeFetcherGenerator.generate(schema, DEFAULT_OUTPUT_PACKAGE, DEFAULT_JOOQ_PACKAGE).stream()
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
                    @reference(path: [{key: "film_actor_film_id_fkey"}, {key: "film_actor_actor_id_fkey"}])
            }
            type ActorConnection { edges: [ActorEdge!]! }
            type ActorEdge { node: Actor! cursor: String! }
            type Query { film: Film }
            """);

        var field = schema.field("Film", "actors");
        assertThat(field).isInstanceOf(GraphitronField.UnclassifiedField.class);
        assertThat(((GraphitronField.UnclassifiedField) field).reason())
            .contains("@asConnection on @lookupKey fields is invalid")
            .contains("positional correspondence");
    }

    @Test
    void compositeKeyInputType_producesSwitchArmAndInputRowsHelper() {
        // Phase 3 — @table input type with @lookupKey on two scalar fields emits inline via
        // TypeClassGenerator.$fields, with a composite VALUES helper on the type class.
        var schema = TestSchemaHelper.buildSchema("""
            input FilmActorKey @table(name: "film_actor") {
                filmId: Int @field(name: "film_id") @lookupKey
                actorId: Int @field(name: "actor_id") @lookupKey
            }
            type FilmActor @table(name: "film_actor") { lastUpdate: String @field(name: "last_update") }
            type Film @table(name: "film") {
                filmActors(key: [FilmActorKey!]! @lookupKey): [FilmActor!]!
            }
            type Query { film: Film }
            """);

        var filmClass = TypeClassGenerator.generate(schema, DEFAULT_OUTPUT_PACKAGE, DEFAULT_JOOQ_PACKAGE).stream()
            .filter(t -> t.name().equals("Film"))
            .findFirst()
            .orElseThrow();

        var methodNames = filmClass.methodSpecs().stream().map(m -> m.name()).toList();
        assertThat(methodNames).contains("$fields", "filmActorsInputRows");
        assertThat(TypeSpecAssertions.hasFieldsArm(filmClass, "filmActors")).isTrue();
    }

    @Test
    void singleCardinalityLookupKey_classifiesAsUnclassifiedField() {
        var schema = TestSchemaHelper.buildSchema("""
            type Actor @table(name: "actor") { name: String }
            type Film @table(name: "film") {
                actor(actor_id: ID! @lookupKey): Actor
                    @reference(path: [{key: "film_actor_film_id_fkey"}, {key: "film_actor_actor_id_fkey"}])
            }
            type Query { film: Film }
            """);

        var field = schema.field("Film", "actor");
        assertThat(field).isInstanceOf(GraphitronField.UnclassifiedField.class);
        assertThat(((GraphitronField.UnclassifiedField) field).reason())
            .contains("Single-cardinality @lookupKey is not supported");
    }
}
