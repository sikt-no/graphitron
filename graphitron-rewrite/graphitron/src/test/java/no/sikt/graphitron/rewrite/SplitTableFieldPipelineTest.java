package no.sikt.graphitron.rewrite;

import no.sikt.graphitron.rewrite.generators.TypeFetcherGenerator;
import org.junit.jupiter.api.Test;

import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_JOOQ_PACKAGE;
import static no.sikt.graphitron.common.configuration.TestConfiguration.DEFAULT_OUTPUT_PACKAGE;
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

        var filmFetchers = TypeFetcherGenerator.generate(schema, DEFAULT_OUTPUT_PACKAGE, DEFAULT_JOOQ_PACKAGE).stream()
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

        var fetcherMethod = TypeFetcherGenerator.generate(schema, DEFAULT_OUTPUT_PACKAGE, DEFAULT_JOOQ_PACKAGE).stream()
            .filter(t -> t.name().equals("FilmFetchers"))
            .findFirst().orElseThrow()
            .methodSpecs().stream()
            .filter(m -> m.name().equals("actors"))
            .findFirst().orElseThrow();

        assertThat(fetcherMethod.returnType().toString())
            .isEqualTo("java.util.concurrent.CompletableFuture<graphql.execution.DataFetcherResult<java.util.List<org.jooq.Record>>>");
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

        var rowsMethod = TypeFetcherGenerator.generate(schema, DEFAULT_OUTPUT_PACKAGE, DEFAULT_JOOQ_PACKAGE).stream()
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

        var filmFetchers = TypeFetcherGenerator.generate(schema, DEFAULT_OUTPUT_PACKAGE, DEFAULT_JOOQ_PACKAGE).stream()
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

        var filmFetchers = TypeFetcherGenerator.generate(schema, DEFAULT_OUTPUT_PACKAGE, DEFAULT_JOOQ_PACKAGE).stream()
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

        var plainFilmFetchers = TypeFetcherGenerator.generate(schemaPlain, DEFAULT_OUTPUT_PACKAGE, DEFAULT_JOOQ_PACKAGE).stream()
            .filter(t -> t.name().equals("FilmFetchers"))
            .findFirst().orElseThrow();

        assertThat(plainFilmFetchers.methodSpecs()).extracting(m -> m.name())
            .as("plain @splitQuery doesn't need emptyScatter — no @lookupKey short-circuit")
            .doesNotContain("emptyScatter")
            .contains("scatterByIdx");
    }

    // ===== single-cardinality @splitQuery =====

    @Test
    void splitQueryField_singleCardinality_producesFetcherAndRowsMethodReturningSingleRecord() {
        // Customer.address @splitQuery — single-cardinality parent-holds-FK (customer.address_id).
        // The fetcher returns CompletableFuture<Record>; the rows method returns List<Record>
        // (one slot per key, null where no match). scatterSingleByIdx is emitted.
        var schema = TestSchemaHelper.buildSchema("""
            type Address @table(name: "address") { district: String }
            type Customer @table(name: "customer") {
                address: Address @splitQuery @reference(path: [{key: "customer_address_id_fkey"}])
            }
            type Query { customer: Customer }
            """);

        var customerFetchers = TypeFetcherGenerator.generate(schema, DEFAULT_OUTPUT_PACKAGE, DEFAULT_JOOQ_PACKAGE).stream()
            .filter(t -> t.name().equals("CustomerFetchers"))
            .findFirst().orElseThrow();

        var methodNames = customerFetchers.methodSpecs().stream().map(m -> m.name()).toList();
        assertThat(methodNames).contains("address", "rowsAddress", "scatterSingleByIdx");
        assertThat(methodNames).as("list-shape scatterByIdx not emitted when no list-cardinality Split* field present")
            .doesNotContain("scatterByIdx");

        var fetcherMethod = customerFetchers.methodSpecs().stream()
            .filter(m -> m.name().equals("address")).findFirst().orElseThrow();
        assertThat(fetcherMethod.returnType().toString())
            .isEqualTo("java.util.concurrent.CompletableFuture<graphql.execution.DataFetcherResult<org.jooq.Record>>");

        var rowsMethod = customerFetchers.methodSpecs().stream()
            .filter(m -> m.name().equals("rowsAddress")).findFirst().orElseThrow();
        assertThat(rowsMethod.returnType().toString())
            .isEqualTo("java.util.List<org.jooq.Record>");
    }

    @Test
    void splitQueryField_singleCardinality_nullFkShortCircuitAppearsInFetcherBody() {
        var schema = TestSchemaHelper.buildSchema("""
            type Address @table(name: "address") { district: String }
            type Customer @table(name: "customer") {
                address: Address @splitQuery @reference(path: [{key: "customer_address_id_fkey"}])
            }
            type Query { customer: Customer }
            """);

        var fetcherMethod = TypeFetcherGenerator.generate(schema, DEFAULT_OUTPUT_PACKAGE, DEFAULT_JOOQ_PACKAGE).stream()
            .filter(t -> t.name().equals("CustomerFetchers"))
            .findFirst().orElseThrow()
            .methodSpecs().stream()
            .filter(m -> m.name().equals("address"))
            .findFirst().orElseThrow();

        // The null-FK short-circuit extracts the FK column into fkVal0 and returns
        // CompletableFuture.completedFuture(null) before loader.load when the parent's FK is NULL.
        String body = fetcherMethod.toString();
        assertThat(body)
            .contains("Integer fkVal0")
            .contains("if (fkVal0 == null)")
            .contains("CompletableFuture.completedFuture(null)");
    }

    @Test
    void mixedCardinality_bothScatterHelpersEmitted() {
        // A class with both list and single Split* fields emits both scatterByIdx and
        // scatterSingleByIdx — the helper gates are independent.
        var schema = TestSchemaHelper.buildSchema("""
            type Address @table(name: "address") { district: String }
            type Customer @table(name: "customer") {
                address: Address @splitQuery @reference(path: [{key: "customer_address_id_fkey"}])
                rentals: [Rental!]! @splitQuery @reference(path: [{key: "rental_customer_id_fkey"}])
            }
            type Rental @table(name: "rental") { rentalId: Int }
            type Query { customer: Customer }
            """);

        var customerFetchers = TypeFetcherGenerator.generate(schema, DEFAULT_OUTPUT_PACKAGE, DEFAULT_JOOQ_PACKAGE).stream()
            .filter(t -> t.name().equals("CustomerFetchers"))
            .findFirst().orElseThrow();

        var methodNames = customerFetchers.methodSpecs().stream().map(m -> m.name()).toList();
        assertThat(methodNames).contains("scatterByIdx", "scatterSingleByIdx");
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

        var filmFetchers = TypeFetcherGenerator.generate(schema, DEFAULT_OUTPUT_PACKAGE, DEFAULT_JOOQ_PACKAGE).stream()
            .filter(t -> t.name().equals("FilmFetchers"))
            .findFirst()
            .orElseThrow();

        assertThat(filmFetchers.methodSpecs()).extracting(m -> m.name())
            .doesNotContain("scatterByIdx");
    }

    // plan-split-query-connection.md §1 + §2: Connection-cardinality pipeline coverage.

    @Test
    void splitQueryConnection_fetcherReturnsConnectionResult_rowsMethodReturnsListOfConnectionResult() {
        var schema = TestSchemaHelper.buildSchema("""
            type Actor @table(name: "actor") { name: String }
            type ActorEdge { node: Actor! cursor: String! }
            type ActorsConnection { edges: [ActorEdge!]! nodes: [Actor!]! }
            type Film @table(name: "film") {
                actorsConnection(first: Int, last: Int, after: String, before: String): ActorsConnection! @splitQuery
                    @reference(path: [{key: "film_actor_film_id_fkey"}, {key: "film_actor_actor_id_fkey"}])
                    @defaultOrder(primaryKey: true)
            }
            type Query { film: Film }
            """);

        var filmFetchers = TypeFetcherGenerator.generate(schema, DEFAULT_OUTPUT_PACKAGE, DEFAULT_JOOQ_PACKAGE).stream()
            .filter(t -> t.name().equals("FilmFetchers"))
            .findFirst().orElseThrow();

        var fetcher = filmFetchers.methodSpecs().stream()
            .filter(m -> m.name().equals("actorsConnection"))
            .findFirst().orElseThrow();
        assertThat(fetcher.returnType().toString())
            .isEqualTo("java.util.concurrent.CompletableFuture<graphql.execution.DataFetcherResult<fake.code.generated.util.ConnectionResult>>");

        var rowsMethod = filmFetchers.methodSpecs().stream()
            .filter(m -> m.name().equals("rowsActorsConnection"))
            .findFirst().orElseThrow();
        assertThat(rowsMethod.returnType().toString())
            .isEqualTo("java.util.List<fake.code.generated.util.ConnectionResult>");
    }

    @Test
    void splitQueryConnection_emitsScatterConnectionByIdxHelperAndOmitsListScatter() {
        // Connection-only fetcher class: scatterConnectionByIdx emitted, scatterByIdx is not
        // (plain-list scatter would be dead code in a class that only has Connection-cardinality
        // Split* fields).
        var schema = TestSchemaHelper.buildSchema("""
            type Actor @table(name: "actor") { name: String }
            type ActorEdge { node: Actor! cursor: String! }
            type ActorsConnection { edges: [ActorEdge!]! nodes: [Actor!]! }
            type Film @table(name: "film") {
                actorsConnection(first: Int, last: Int, after: String, before: String): ActorsConnection! @splitQuery
                    @reference(path: [{key: "film_actor_film_id_fkey"}, {key: "film_actor_actor_id_fkey"}])
                    @defaultOrder(primaryKey: true)
            }
            type Query { film: Film }
            """);

        var filmFetchers = TypeFetcherGenerator.generate(schema, DEFAULT_OUTPUT_PACKAGE, DEFAULT_JOOQ_PACKAGE).stream()
            .filter(t -> t.name().equals("FilmFetchers"))
            .findFirst().orElseThrow();

        var methodNames = filmFetchers.methodSpecs().stream().map(m -> m.name()).toList();
        assertThat(methodNames).contains("scatterConnectionByIdx");
        assertThat(methodNames).as("plain-list scatterByIdx not emitted when no plain-list-cardinality Split* field")
            .doesNotContain("scatterByIdx");
    }

    @Test
    void splitQueryConnection_withDynamicOrderByArg_emitsOrderByHelperAcceptingAliasedTable() {
        // plan-split-query-connection.md §2: the OrderBy helper signature now takes the aliased
        // Table so Split callers can pass their FK-chain terminal alias. Pipeline-level check
        // that the helper is emitted for Argument orderBy on Split+Connection fields.
        var schema = TestSchemaHelper.buildSchema("""
            enum ActorSort { ACTOR_ID @order(primaryKey: true) }
            input ActorOrderBy { field: ActorSort! direction: SortDirection }
            type Actor @table(name: "actor") { name: String }
            type ActorEdge { node: Actor! cursor: String! }
            type ActorsConnection { edges: [ActorEdge!]! nodes: [Actor!]! }
            type Film @table(name: "film") {
                actorsConnection(
                    order: [ActorOrderBy] @orderBy, first: Int, last: Int, after: String, before: String
                ): ActorsConnection! @splitQuery
                    @reference(path: [{key: "film_actor_film_id_fkey"}, {key: "film_actor_actor_id_fkey"}])
                    @defaultOrder(primaryKey: true)
            }
            type Query { film: Film }
            """);

        var filmFetchers = TypeFetcherGenerator.generate(schema, DEFAULT_OUTPUT_PACKAGE, DEFAULT_JOOQ_PACKAGE).stream()
            .filter(t -> t.name().equals("FilmFetchers"))
            .findFirst().orElseThrow();

        var helper = filmFetchers.methodSpecs().stream()
            .filter(m -> m.name().equals("actorsConnectionOrderBy"))
            .findFirst().orElseThrow();
        assertThat(helper.parameters())
            .extracting(p -> p.type().toString())
            .containsExactly(
                "graphql.schema.DataFetchingEnvironment",
                "no.sikt.graphitron.rewrite.test.jooq.tables.Actor");
    }

    // ===== SplitTableField under NestingField =====

    @Test
    void nestingFieldWithSplitTableField_producesNestedFetchersClass() {
        var schema = TestSchemaHelper.buildSchema("""
            type Actor @table(name: "actor") { name: String }
            type FilmInfo {
                cast: [Actor!]! @splitQuery
                    @reference(path: [{key: "film_actor_film_id_fkey"}, {key: "film_actor_actor_id_fkey"}])
            }
            type Film @table(name: "film") { info: FilmInfo }
            type Query { film: Film }
            """);

        var all = TypeFetcherGenerator.generate(schema, DEFAULT_OUTPUT_PACKAGE, DEFAULT_JOOQ_PACKAGE);

        var filmInfoFetchers = all.stream()
            .filter(t -> t.name().equals("FilmInfoFetchers"))
            .findFirst()
            .orElseThrow();
        var methodNames = filmInfoFetchers.methodSpecs().stream().map(m -> m.name()).toList();
        assertThat(methodNames).contains("cast", "rowsCast", "scatterByIdx");
        assertThat(methodNames).doesNotContain("wiring");
    }

    @Test
    void nestingFieldWithSplitTableField_outerFetchersClassUnaffected() {
        var schema = TestSchemaHelper.buildSchema("""
            type Actor @table(name: "actor") { name: String }
            type FilmInfo {
                cast: [Actor!]! @splitQuery
                    @reference(path: [{key: "film_actor_film_id_fkey"}, {key: "film_actor_actor_id_fkey"}])
            }
            type Film @table(name: "film") { info: FilmInfo }
            type Query { film: Film }
            """);

        var filmFetchers = TypeFetcherGenerator.generate(schema, DEFAULT_OUTPUT_PACKAGE, DEFAULT_JOOQ_PACKAGE).stream()
            .filter(t -> t.name().equals("FilmFetchers"))
            .findFirst()
            .orElseThrow();
        var methodNames = filmFetchers.methodSpecs().stream().map(m -> m.name()).toList();
        assertThat(methodNames).doesNotContain("cast", "rowsCast");
    }
}
