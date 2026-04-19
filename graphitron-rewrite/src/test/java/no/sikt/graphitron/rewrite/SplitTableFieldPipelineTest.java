package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.generators.TypeFetcherGenerator;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_JOOQ_PACKAGE;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * SDL → classified schema → generated {@code TypeSpec} pipeline tests for
 * {@link no.sikt.graphitron.rewrite.model.ChildField.SplitTableField} emission (argres Phase 2b C1).
 *
 * <p>Verifies the structural contract: a {@code @splitQuery} child field produces a
 * DataLoader-registering fetcher (returning {@code CompletableFuture}) and a paired rows method
 * (taking {@code List<RowN>, DataFetchingEnvironment} and returning {@code List<List<Record>>}
 * for list cardinality). The shared {@code scatterByIdx} helper is emitted exactly once per
 * fetcher class containing any Split* field.
 */
class SplitTableFieldPipelineTest {

    @BeforeEach
    void setup() {
        RewriteConfig.setProperties(java.util.Set.of(), "", "fake.code.generated", DEFAULT_JOOQ_PACKAGE, java.util.Map.of());
    }

    @AfterEach
    void teardown() {
        RewriteConfig.clear();
    }

    @Test
    void splitQueryField_producesDataLoaderFetcherAndRowsMethod() {
        var schema = TestSchemaHelper.buildSchema("""
            type Actor @table(name: "actor") { name: String }
            type Film @table(name: "film") {
                actors: [Actor!]! @splitQuery
                    @reference(path: [{key: "film_actor_film_id_fkey"}, {key: "film_actor_actor_id_fkey"}])
            }
            type Query { film: Film }
            """);

        var filmFetchers = TypeFetcherGenerator.generate(schema).stream()
            .filter(t -> t.name().equals("FilmFetchers"))
            .findFirst()
            .orElseThrow();

        var methodNames = filmFetchers.methodSpecs().stream().map(m -> m.name()).toList();
        assertThat(methodNames).contains("actors", "rowsActors", "scatterByIdx");
    }

    @Test
    void splitQueryField_fetcherReturnsCompletableFutureOfListRecord() {
        var schema = TestSchemaHelper.buildSchema("""
            type Actor @table(name: "actor") { name: String }
            type Film @table(name: "film") {
                actors: [Actor!]! @splitQuery
                    @reference(path: [{key: "film_actor_film_id_fkey"}, {key: "film_actor_actor_id_fkey"}])
            }
            type Query { film: Film }
            """);

        var fetcherMethod = TypeFetcherGenerator.generate(schema).stream()
            .filter(t -> t.name().equals("FilmFetchers"))
            .findFirst().orElseThrow()
            .methodSpecs().stream()
            .filter(m -> m.name().equals("actors"))
            .findFirst().orElseThrow();

        assertThat(fetcherMethod.returnType().toString())
            .isEqualTo("java.util.concurrent.CompletableFuture<java.util.List<org.jooq.Record>>");
        assertThat(fetcherMethod.parameters()).extracting(p -> p.type().toString())
            .containsExactly("graphql.schema.DataFetchingEnvironment");
    }

    @Test
    void splitQueryField_rowsMethodHasKeysAndEnvSignature() {
        var schema = TestSchemaHelper.buildSchema("""
            type Actor @table(name: "actor") { name: String }
            type Film @table(name: "film") {
                actors: [Actor!]! @splitQuery
                    @reference(path: [{key: "film_actor_film_id_fkey"}, {key: "film_actor_actor_id_fkey"}])
            }
            type Query { film: Film }
            """);

        var rowsMethod = TypeFetcherGenerator.generate(schema).stream()
            .filter(t -> t.name().equals("FilmFetchers"))
            .findFirst().orElseThrow()
            .methodSpecs().stream()
            .filter(m -> m.name().equals("rowsActors"))
            .findFirst().orElseThrow();

        assertThat(rowsMethod.returnType().toString())
            .isEqualTo("java.util.List<java.util.List<org.jooq.Record>>");
        assertThat(rowsMethod.parameters()).extracting(p -> p.type().toString())
            .containsExactly(
                "java.util.List<org.jooq.Row1<java.lang.Integer>>",
                "graphql.schema.DataFetchingEnvironment");
    }

    @Test
    void scatterByIdxHelper_emittedExactlyOncePerFetchersClassWithSplitFields() {
        // Two Split* fields on the same parent type → the helper must still appear exactly once.
        var schema = TestSchemaHelper.buildSchema("""
            type Actor @table(name: "actor") { name: String }
            type Inventory @table(name: "inventory") { rentalRate: Float }
            type Film @table(name: "film") {
                actors: [Actor!]! @splitQuery
                    @reference(path: [{key: "film_actor_film_id_fkey"}, {key: "film_actor_actor_id_fkey"}])
                inventories: [Inventory!]! @splitQuery
                    @reference(path: [{key: "inventory_film_id_fkey"}])
            }
            type Query { film: Film }
            """);

        var filmFetchers = TypeFetcherGenerator.generate(schema).stream()
            .filter(t -> t.name().equals("FilmFetchers"))
            .findFirst()
            .orElseThrow();

        long scatterCount = filmFetchers.methodSpecs().stream()
            .filter(m -> m.name().equals("scatterByIdx"))
            .count();
        assertThat(scatterCount).isEqualTo(1);
    }

    @Test
    void splitLookupQueryField_producesFetcherRowsMethodAndInputRowsHelper() {
        // @splitQuery + @lookupKey: fetcher returns CompletableFuture<List<Record>>, rows method
        // emits the VALUES+JOIN flat batched SELECT, and the inputRows helper builds the
        // lookup-key RowN[] from env.getArgument.
        var schema = TestSchemaHelper.buildSchema("""
            type Actor @table(name: "actor") { name: String }
            type Film @table(name: "film") {
                actorsByKey(actor_id: [Int!]! @lookupKey): [Actor!]! @splitQuery
                    @reference(path: [{key: "film_actor_film_id_fkey"}, {key: "film_actor_actor_id_fkey"}])
            }
            type Query { film: Film }
            """);

        var filmFetchers = TypeFetcherGenerator.generate(schema).stream()
            .filter(t -> t.name().equals("FilmFetchers"))
            .findFirst()
            .orElseThrow();

        var methodNames = filmFetchers.methodSpecs().stream().map(m -> m.name()).toList();
        assertThat(methodNames)
            .contains("actorsByKey", "rowsActorsByKey", "actorsByKeyInputRows", "scatterByIdx", "emptyScatter");
    }

    @Test
    void splitLookupQueryField_emptyScatterHelperOnlyWhenSplitLookupPresent() {
        var schemaPlain = TestSchemaHelper.buildSchema("""
            type Actor @table(name: "actor") { name: String }
            type Film @table(name: "film") {
                actors: [Actor!]! @splitQuery
                    @reference(path: [{key: "film_actor_film_id_fkey"}, {key: "film_actor_actor_id_fkey"}])
            }
            type Query { film: Film }
            """);

        var plainFilmFetchers = TypeFetcherGenerator.generate(schemaPlain).stream()
            .filter(t -> t.name().equals("FilmFetchers"))
            .findFirst().orElseThrow();

        assertThat(plainFilmFetchers.methodSpecs()).extracting(m -> m.name())
            .as("plain @splitQuery doesn't need emptyScatter — no @lookupKey short-circuit")
            .doesNotContain("emptyScatter")
            .contains("scatterByIdx");
    }

    @Test
    void noSplitFields_noScatterByIdxHelper() {
        var schema = TestSchemaHelper.buildSchema("""
            type Language @table(name: "language") { name: String }
            type Film @table(name: "film") {
                language: Language @reference(path: [{key: "film_language_id_fkey"}])
            }
            type Query { film: Film }
            """);

        var filmFetchers = TypeFetcherGenerator.generate(schema).stream()
            .filter(t -> t.name().equals("FilmFetchers"))
            .findFirst()
            .orElseThrow();

        assertThat(filmFetchers.methodSpecs()).extracting(m -> m.name())
            .doesNotContain("scatterByIdx");
    }
}
