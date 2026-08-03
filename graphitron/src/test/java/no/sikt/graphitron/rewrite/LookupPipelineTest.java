package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.generators.TypeFetcherGenerator;
import no.sikt.graphitron.rewrite.generators.util.TypeSpecAssertions;
import no.sikt.graphitron.rewrite.model.ChildField;
import no.sikt.graphitron.rewrite.model.GraphitronField;
import no.sikt.graphitron.rewrite.model.LookupMapping;
import org.junit.jupiter.api.Test;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_OUTPUT_PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;
import no.sikt.graphitron.rewrite.test.tier.PipelineTier;

/**
 * SDL → classified schema → generated {@code TypeSpec} pipeline tests for inline
 * Inline lookup-keyed table-child emission (argres Phase 2a).
 *
 * <p>Verifies C1's structural contract: the {@code the type's $project unit} method contains
 * a switch arm for each child-lookup field; the input-rows helper is emitted on the type class;
 * no fetcher method lands in {@code *Fetchers}; and classifier rejection for {@code @asConnection}
 * or single cardinality on an inline {@code @lookupKey} field produces {@code UnclassifiedField}.
 */
@PipelineTier
class LookupPipelineTest {

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

        var filmClass = ProjectionRenderTestSupport.renderProjections(schema, DEFAULT_OUTPUT_PACKAGE).stream()
            .filter(t -> t.name().equals("Film"))
            .findFirst()
            .orElseThrow();

        var methodNames = filmClass.methodSpecs().stream().map(m -> m.name()).toList();
        assertThat(methodNames).contains("$project", "actorsInputRows");

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

        var filmFetchers = TypeFetcherGenerator.generate(schema, DEFAULT_OUTPUT_PACKAGE).stream()
            .filter(t -> t.name().equals("FilmFetchers"))
            .findFirst()
            .orElseThrow();

        var methodNames = filmFetchers.methodSpecs().stream().map(m -> m.name()).toList();
        assertThat(methodNames)
            .as("R303: the lookup-keyed TableField projects inline via the type's $project unit; the read of "
                + "that result-key-aliased projection is reified as a named env-dependent method")
            .contains("actors");
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
        // Phase 3 — @table input type used as a @lookupKey-bearing arg (arg-level
        // @lookupKey drives the binding walk over every admissible input field). Emits inline
        // via the type's $project unit, with a composite VALUES helper on the type class.
        var schema = TestSchemaHelper.buildSchema("""
            input FilmActorKey {
                filmId: Int @field(name: "film_id")
                actorId: Int @field(name: "actor_id")
            }
            type FilmActor @table(name: "film_actor") { lastUpdate: String @field(name: "last_update") }
            type Film @table(name: "film") {
                filmActors(key: [FilmActorKey!]! @lookupKey): [FilmActor!]!
            }
            type Query { film: Film }
            """);

        var filmActors = (ChildField.TableField) schema.field("Film", "filmActors");
        var mapping = (LookupMapping.ColumnMapping)
            ((no.sikt.graphitron.rewrite.model.LookupResolution.Keyed) filmActors.lookup()).mapping();
        assertThat(mapping.args()).hasSize(1);
        var only = mapping.args().get(0);
        assertThat(only).isInstanceOfSatisfying(
            LookupMapping.ColumnMapping.LookupArg.MapInput.class,
            m -> {
                assertThat(m.list()).isTrue();
                assertThat(m.bindings()).hasSize(2);
                assertThat(m.bindings().stream().map(b -> b.fieldName()))
                    .containsExactly("filmId", "actorId");
                assertThat(m.bindings().stream().map(b -> b.targetColumn().sqlName()))
                    .containsExactly("film_id", "actor_id");
            });
        assertThat(mapping.slotColumns().stream().map(c -> c.sqlName()))
            .containsExactly("film_id", "actor_id");

        var filmClass = ProjectionRenderTestSupport.renderProjections(schema, DEFAULT_OUTPUT_PACKAGE).stream()
            .filter(t -> t.name().equals("Film"))
            .findFirst()
            .orElseThrow();

        var methodNames = filmClass.methodSpecs().stream().map(m -> m.name()).toList();
        assertThat(methodNames).contains("$project", "filmActorsInputRows");
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
