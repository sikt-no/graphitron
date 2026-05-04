package no.sikt.graphitron.rewrite.test.querydb;

import graphql.ExecutionInput;
import graphql.GraphQL;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.GraphQLSchema;
import no.sikt.graphitron.generated.Graphitron;
import no.sikt.graphitron.generated.schema.GraphitronContext;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.INTEGER;
import static org.assertj.core.api.InstanceOfAssertFactories.LIST;
import static org.assertj.core.api.InstanceOfAssertFactories.MAP;
import static org.assertj.core.api.InstanceOfAssertFactories.STRING;
import static org.assertj.core.api.InstanceOfAssertFactories.list;
import static org.assertj.core.api.InstanceOfAssertFactories.map;
import no.sikt.graphitron.rewrite.test.tier.ExecutionTier;

/**
 * End-to-end tests that execute GraphQL queries against a real PostgreSQL database
 * using the generated wiring, field resolvers, and table methods.
 *
 * <p>This verifies that the generated code actually works — not just that it compiles.
 */
@ExecutionTier
class GraphQLQueryTest {

    static PostgreSQLContainer<?> postgres;
    static DSLContext dsl;
    static GraphQL graphql;
    static final AtomicInteger QUERY_COUNT = new AtomicInteger();
    /**
     * Lower-cased rendered SQL for every statement issued through the test {@link DSLContext}.
     * Tests that need to assert on which SQL ran (e.g. that {@code totalCount} was lazy on
     * selection) call {@link #SQL_LOG}{@code .clear()} before executing the GraphQL query.
     */
    static final List<String> SQL_LOG = new java.util.concurrent.CopyOnWriteArrayList<>();

    @BeforeAll
    static void startDatabase() throws Exception {
        var localUrl = System.getProperty("test.db.url");
        if (localUrl != null) {
            var user = System.getProperty("test.db.username", "postgres");
            var pass = System.getProperty("test.db.password", "postgres");
            dsl = DSL.using(localUrl, user, pass);
        } else {
            postgres = new PostgreSQLContainer<>("postgres:18-alpine")
                .withInitScript("init.sql");
            postgres.start();
            dsl = DSL.using(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        }

        // Count JDBC round-trips via an ExecuteListener. Tests that care (DataLoader batching)
        // call QUERY_COUNT.set(0) before executing and assert on the count afterward. The same
        // listener captures the rendered SQL of every statement into SQL_LOG; the totalCount
        // lazy-on-selection test asserts that no `select count` ran when the field wasn't picked.
        dsl.configuration().set(new org.jooq.impl.DefaultExecuteListenerProvider(
            new org.jooq.impl.DefaultExecuteListener() {
                @Override
                public void executeStart(org.jooq.ExecuteContext ctx) {
                    QUERY_COUNT.incrementAndGet();
                    var sql = ctx.sql();
                    if (sql != null) SQL_LOG.add(sql.toLowerCase(java.util.Locale.ROOT));
                }
            }));

        GraphQLSchema schema = Graphitron.buildSchema(b -> {});
        graphql = GraphQL.newGraphQL(schema).build();
    }

    @AfterAll
    static void stopDatabase() {
        if (postgres != null) postgres.stop();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> execute(String query) {
        var context = new GraphitronContext() {
            @Override
            public DSLContext getDslContext(DataFetchingEnvironment env) {
                return dsl;
            }
            @Override
            public <T> T getContextArgument(DataFetchingEnvironment env, String name) {
                return null;
            }
        };

        // DataLoader registry is per-request; Split* fetchers call computeIfAbsent on it.
        // graphql-java requires one explicitly even for non-DataLoader queries.
        var input = ExecutionInput.newExecutionInput()
            .query(query)
            .graphQLContext(builder -> builder.put(GraphitronContext.class, context))
            .dataLoaderRegistry(new org.dataloader.DataLoaderRegistry())
            .build();

        var result = graphql.execute(input);
        assertThat(result.getErrors()).isEmpty();
        return result.getData();
    }

    /**
     * Executes a query and returns the {@link graphql.ExecutionResult} without asserting
     * on errors — for tests that expect a failure path (e.g. Relay first+last validation).
     */
    private graphql.ExecutionResult executeRaw(String query) {
        var context = new GraphitronContext() {
            @Override
            public DSLContext getDslContext(DataFetchingEnvironment env) {
                return dsl;
            }
            @Override
            public <T> T getContextArgument(DataFetchingEnvironment env, String name) {
                return null;
            }
        };

        var input = ExecutionInput.newExecutionInput()
            .query(query)
            .graphQLContext(builder -> builder.put(GraphitronContext.class, context))
            .build();

        return graphql.execute(input);
    }

    /**
     * Executes a query with a caller-supplied {@link GraphitronContext} (e.g. one whose
     * {@code getTenantId} partitions per id). Asserts no errors. Used by the
     * {@code Query.nodes} per-tenant fan-out test.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> executeWithContext(String query, GraphitronContext context) {
        var input = ExecutionInput.newExecutionInput()
            .query(query)
            .graphQLContext(builder -> builder.put(GraphitronContext.class, context))
            .dataLoaderRegistry(new org.dataloader.DataLoaderRegistry())
            .build();
        var result = graphql.execute(input);
        assertThat(result.getErrors()).isEmpty();
        return result.getData();
    }

    // ===== Multi-field root query =====

    @Test
    void multipleRootFields_eachGetsCorrectSelectionSet() {
        Map<String, Object> data = execute("""
            {
                customers { firstName }
                films { title }
            }
            """);

        assertThat(data).extractingByKey("customers", as(LIST))
            .hasSize(5)
            .first(as(MAP)).containsKey("firstName");
        assertThat(data).extractingByKey("films", as(LIST))
            .hasSize(5)
            .first(as(MAP)).containsKey("title");
    }

    @Test
    void multipleRootFields_filmsColumnsNotLeakedIntoCustomers() {
        // If selection set scoping is wrong, customers might try to SELECT film columns
        Map<String, Object> data = execute("""
            {
                customers { firstName lastName }
                films { title rating }
            }
            """);
        assertThat(data).extractingByKey("customers", as(LIST))
            .hasSize(5)
            .first(as(MAP)).containsOnlyKeys("firstName", "lastName");
    }

    // ===== customers query =====

    @Test
    void customers_returnsAllCustomers() {
        Map<String, Object> data = execute("{ customers { customerId firstName lastName } }");
        assertThat(data).extractingByKey("customers", as(LIST)).hasSize(5);
    }

    @Test
    void customers_filteredByActive() {
        Map<String, Object> data = execute("{ customers(active: true) { customerId firstName } }");
        assertThat(data).extractingByKey("customers", as(list(Map.class)))
            .hasSize(3)
            .extracting(c -> c.get("firstName"))
            .containsExactlyInAnyOrder("Mary", "Patricia", "Linda");
    }

    // ===== films query =====

    @Test
    void films_returnsAllFilms() {
        Map<String, Object> data = execute("{ films { filmId title } }");
        assertThat(data).extractingByKey("films", as(LIST)).hasSize(5);
    }

    @Test
    void films_titleUppercase_resolvesViaServiceRecordFieldDataLoader() {
        // R49 Phase B (R32): @service child field with a scalar return. The generator-emitted
        // rows-method body calls FilmService.titleUppercase via the parameterised
        // ArgCallEmitter (Sources -> keys, DslContext -> dsl local). Each key is one parent
        // FILM_ID; the developer's method returns Map<Row1<Integer>, String> with uppercased
        // titles. End-to-end verification that the Phase A plumbing (BatchKey, IMPLEMENTED_LEAVES,
        // shared emitters) works with Phase B's body emission against PostgreSQL.
        Map<String, Object> data = execute("{ films { title titleUppercase } }");
        assertThat(data).extractingByKey("films", as(list(Map.class)))
            .hasSize(5)
            .allSatisfy(f -> {
                var title = (String) f.get("title");
                var titleUppercase = (String) f.get("titleUppercase");
                assertThat(titleUppercase)
                    .as("titleUppercase must equal title.toUpperCase() for film '%s'", title)
                    .isEqualTo(title.toUpperCase());
            });
    }

    @Test
    void films_isEnglish_resolvesViaExternalFieldExpression() {
        // R48 ComputedField execution-tier fixture: @externalField(reference: ...) inlines
        // FilmExtensions.isEnglish(table) (Field<Boolean>(LANGUAGE_ID = 1)) into Film.$fields().
        // ColumnFetcher reads the projected alias from the result Record at request time.
        // All seeded films have language_id=1, so the expression resolves to true for each.
        Map<String, Object> data = execute("{ films { title isEnglish } }");
        var films = assertThat(data).extractingByKey("films", as(list(Map.class))).hasSize(5);
        films.extracting(f -> f.get("isEnglish")).containsOnly(Boolean.TRUE);
        films.extracting(f -> f.get("title")).doesNotContainNull();
    }

    @Test
    void inventoryById_filmRef_resolvesViaExternalFieldReturningFieldOfTableRecord() {
        // R61 execution-tier fixture: @externalField returning Field<TableRecord<?>>.
        // InventoryExtensions.filmRef(table) projects inventory.film_id via DSL.row(...).
        // convertFrom(...), constructing a FilmRecord with only the PK populated. The
        // GraphQL FilmCard type is @record-backed by FilmRecord; FilmCard.filmId is read
        // from the lifted FilmRecord at request time. Confirms a Field<X> where X is a
        // jOOQ TableRecord round-trips end-to-end through the existing @externalField
        // resolver (no production change in R61) and the convertFrom trick lifts a typed
        // value at SQL time.
        Map<String, Object> data = execute(
            "{ inventoryById(inventory_id: [1, 2, 3]) { inventoryId filmRef { filmId } } }");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) data.get("inventoryById");
        assertThat(rows).hasSize(3);
        // Seed: inventory_id N -> film_id N for N in {1, 2, 3}.
        for (var row : rows) {
            int inventoryId = (Integer) row.get("inventoryId");
            @SuppressWarnings("unchecked")
            Map<String, Object> filmRef = (Map<String, Object>) row.get("filmRef");
            assertThat(filmRef).extractingByKey("filmId").isEqualTo(inventoryId);
        }
    }

    @Test
    void inventoryById_filmCardData_firesAccessorKeyedSingleLiftThroughCustomJavaRecord() {
        // R61 execution-tier fixture: @externalField returning Field<CustomJavaRecord>
        // where the custom record (FilmCardData) carries a typed FilmRecord accessor.
        // The classifier picks the canonical film() accessor on FilmCardData and produces
        // an AccessorKeyedSingle BatchKey for the GraphQL child field `film: Film`. The
        // framework batches dispatch via loader.load(key, env) keyed on the element table's
        // PK (Record1<Integer>) and returns one Film row per key — full columns this time,
        // so Film.title resolves from the framework-fetched record (the lifted FilmRecord
        // only carried the PK).
        //
        // R61 lifted Invariant #10 (the single-cardinality RecordTableField rejection at
        // GraphitronSchemaValidator.validateRecordParentSingleCardinalityRejected) by
        // extending RecordTableField.emitsSingleRecordPerKey() to also be true for
        // single-cardinality fields, so this test exercises the AccessorKeyedSingle path
        // end-to-end against PostgreSQL.
        Map<String, Object> data = execute(
            "{ inventoryById(inventory_id: [1, 2, 3]) { inventoryId filmCardData { film { filmId title } } } }");
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) data.get("inventoryById");
        assertThat(rows).hasSize(3);
        // Seeded film titles by film_id 1..3.
        Map<Integer, String> expectedTitleByFilmId = Map.of(
            1, "ACADEMY DINOSAUR",
            2, "ACE GOLDFINGER",
            3, "ADAPTATION HOLES");
        for (var row : rows) {
            int inventoryId = (Integer) row.get("inventoryId");
            @SuppressWarnings("unchecked")
            Map<String, Object> filmCardData = (Map<String, Object>) row.get("filmCardData");
            @SuppressWarnings("unchecked")
            Map<String, Object> film = (Map<String, Object>) filmCardData.get("film");
            assertThat(film).extractingByKey("filmId").isEqualTo(inventoryId);
            assertThat(film).extractingByKey("title").isEqualTo(expectedTitleByFilmId.get(inventoryId));
        }
    }

    @Test
    void films_filteredBySameTableNodeId_returnsRowsMatchingDecodedIds() {
        // self-table-nodeid-filter.md: [ID!] @nodeId(typeName: "Film") on a film-bound input
        // → primary-key IN predicate. Encode 2 of the 5 PKs, expect exactly those 2 rows.
        String id1 = no.sikt.graphitron.generated.util.NodeIdEncoder.encode("Film", 2);
        String id3 = no.sikt.graphitron.generated.util.NodeIdEncoder.encode("Film", 4);
        Map<String, Object> data = execute(
            "{ filmsBySameTableNodeId(filter: {filmIds: [\"" + id1 + "\", \"" + id3 + "\"]}) "
            + "{ filmId title } }");
        assertThat(data).extractingByKey("filmsBySameTableNodeId", as(list(Map.class)))
            .extracting(f -> f.get("filmId")).containsExactlyInAnyOrder(2, 4);
    }

    @Test
    void films_filteredByArgNodeId_returnsRowsMatchingDecodedIds() {
        // R40: argument-level same-table @nodeId — `filmsByNodeIdArg(ids: [ID!]! @nodeId(typeName: "Film"))`
        // routes through the @lookupKey dispatch path (same-table @nodeId implies isLookupKey
        // at classify time; the field promotes to a QueryLookupTableField). Each opaque ID
        // decodes once at the per-row decode loop in addRowBuildingCore and feeds the VALUES+
        // JOIN against film.film_id, so the result rows correspond exactly to the supplied ids
        // (ordering is restored by ConnectionHelper-style index column).
        String id2 = no.sikt.graphitron.generated.util.NodeIdEncoder.encode("Film", 2);
        String id4 = no.sikt.graphitron.generated.util.NodeIdEncoder.encode("Film", 4);
        Map<String, Object> data = execute(
            "{ filmsByNodeIdArg(ids: [\"" + id2 + "\", \"" + id4 + "\"]) { filmId title } }");
        List<Map<String, Object>> films = (List<Map<String, Object>>) data.get("filmsByNodeIdArg");
        assertThat(films).extracting(f -> f.get("filmId")).containsExactlyInAnyOrder(2, 4);
    }

    @Test
    void filmsByNodeIdArg_malformedIdMixedWithWellFormed_returnsWellFormedSubset() {
        // R40 phase 2: same-table @nodeId arg now uses SkipMismatchedElement — a malformed id
        // drops silently from the VALUES set rather than throwing GraphqlErrorException. The
        // emitter tracks an effective row count and trims rows[] when shorter than n, so the
        // VALUES+JOIN runs against only the well-formed decoded ids and the result is the
        // single matching row. Restores the originally-specified Skip semantics over the first
        // pass's expedient Throw.
        String id2 = no.sikt.graphitron.generated.util.NodeIdEncoder.encode("Film", 2);
        Map<String, Object> data = execute(
            "{ filmsByNodeIdArg(ids: [\"" + id2 + "\", \"not-a-valid-node-id\"]) { filmId title } }");
        List<Map<String, Object>> films = (List<Map<String, Object>>) data.get("filmsByNodeIdArg");
        assertThat(films).extracting(f -> f.get("filmId")).containsExactly(2);
    }

    @Test
    void filmsByNodeIdArg_allMalformedIds_returnsNoRows() {
        // R40 phase 2: when every id is malformed, the per-row Skip drops every entry; the
        // effective row count reaches 0 and the call site's `if (rows.length == 0) return
        // dsl.newResult();` short-circuit handles the all-skipped case without further
        // bookkeeping.
        Map<String, Object> data = execute(
            "{ filmsByNodeIdArg(ids: [\"garbage-1\", \"garbage-2\"]) { filmId title } }");
        List<Map<String, Object>> films = (List<Map<String, Object>>) data.get("filmsByNodeIdArg");
        assertThat(films).isEmpty();
    }

    @Test
    void filmsByNodeIdArg_emptyList_returnsNoRows() {
        // R40 phase 2: empty input list — the input-rows helper's row-count computation
        // (`int n = ids == null ? 0 : ids.size()`) yields 0, the rows array is length 0, and
        // the call site short-circuits to `dsl.newResult()`. No SQL round-trip.
        Map<String, Object> data = execute("{ filmsByNodeIdArg(ids: []) { filmId title } }");
        List<Map<String, Object>> films = (List<Map<String, Object>>) data.get("filmsByNodeIdArg");
        assertThat(films).isEmpty();
    }

    @Test
    void films_filteredBySameTableNodeId_emptyListReturnsNoRows() {
        // R50 phase (e4b): the post-collapse successor of NodeIdInFilterField is a column-shaped
        // ColumnField with NodeIdDecodeKeys.SkipMismatchedElement, which lands on the same
        // column-equality body as a regular [String!] filter -- empty list emits an
        // unsatisfiable IN predicate (jOOQ renders IN () as false). This aligns the empty-list
        // behaviour with every other column-equality filter; the legacy hasIds short-circuit
        // (empty → noCondition) is gone.
        Map<String, Object> data = execute(
            "{ filmsBySameTableNodeId(filter: {filmIds: []}) { filmId } }");
        assertThat(data).extractingByKey("filmsBySameTableNodeId", as(LIST)).isEmpty();
    }

    @Test
    void films_filteredByRating() {
        // Test data: ACADEMY DINOSAUR=PG, ACE GOLDFINGER=G, ADAPTATION HOLES=NC_17,
        //            AFFAIR PREJUDICE=G, AGENT TRUMAN=PG
        Map<String, Object> data = execute("{ films(rating: G) { title } }");
        assertThat(data).extractingByKey("films", as(list(Map.class)))
            .extracting(f -> f.get("title"))
            .containsExactlyInAnyOrder("ACE GOLDFINGER", "AFFAIR PREJUDICE");
    }

    @Test
    void films_filteredByTextRating() {
        // TextRating enum maps to varchar column via @field(name:) — NC_17 → "NC-17"
        Map<String, Object> data = execute("{ films(textRating: NC_17) { title } }");
        assertThat(data).extractingByKey("films", as(list(Map.class)))
            .extracting(f -> f.get("title"))
            .containsExactly("ADAPTATION HOLES");
    }

    @Test
    void films_filteredByTextRating_simpleValue() {
        // G maps to "G" (no @field mapping needed)
        Map<String, Object> data = execute("{ films(textRating: G) { title } }");
        assertThat(data).extractingByKey("films", as(list(Map.class)))
            .extracting(f -> f.get("title"))
            .containsExactlyInAnyOrder("ACE GOLDFINGER", "AFFAIR PREJUDICE");
    }

    @Test
    void films_orderedByFilmId() {
        Map<String, Object> data = execute("{ films { title } }");
        assertThat(data).extractingByKey("films", as(list(Map.class)))
            .extracting(f -> f.get("title"))
            .containsExactly("ACADEMY DINOSAUR", "ACE GOLDFINGER", "ADAPTATION HOLES",
                "AFFAIR PREJUDICE", "AGENT TRUMAN");
    }

    @Test
    void films_selectsOnlyRequestedFields() {
        // Only request 'title' — should still work even though filmId etc. are not selected
        Map<String, Object> data = execute("{ films { title } }");
        assertThat(data).extractingByKey("films", as(LIST))
            .isNotEmpty()
            .first(as(MAP)).containsKey("title");
    }

    // ===== filmById lookup query =====

    @Test
    void filmById_returnsRequestedFilms() {
        Map<String, Object> data = execute("{ filmById(film_id: [\"1\", \"3\"]) { filmId title } }");
        // containsExactly (not InAnyOrder) — VALUES+JOIN preserves input order by joining on the
        // derived table's idx column. See docs/argument-resolution.md Phase 1.
        assertThat(data).extractingByKey("filmById", as(list(Map.class)))
            .hasSize(2)
            .extracting(f -> f.get("title"))
            .containsExactly("ACADEMY DINOSAUR", "ADAPTATION HOLES");
    }

    @Test
    void filmById_preservesInputOrder() {
        // VALUES+JOIN ordering evidence: request IDs in a non-sorted order and assert output order
        // matches input order. This is the one thing IN/EQ could not do, so it's the
        // behaviour-level proof that the emitter uses ordered VALUES+JOIN.
        Map<String, Object> data = execute("{ filmById(film_id: [\"3\", \"1\", \"2\"]) { filmId title } }");
        assertThat(data).extractingByKey("filmById", as(list(Map.class)))
            .extracting(f -> f.get("filmId"))
            .containsExactly(3, 1, 2);
    }

    @Test
    void filmById_singleId_returnsOneFilm() {
        Map<String, Object> data = execute("{ filmById(film_id: [\"2\"]) { title } }");
        assertThat(data).extractingByKey("filmById", as(LIST))
            .hasSize(1)
            .first(as(MAP)).containsEntry("title", "ACE GOLDFINGER");
    }

    // ===== languageByKey lookup query =====

    @Test
    void languageByKey_returnsRequestedLanguages() {
        Map<String, Object> data = execute("{ languageByKey(language_id: [1, 2]) { languageId } }");
        assertThat(data).extractingByKey("languageByKey", as(list(Map.class)))
            .hasSize(2)
            .extracting(l -> l.get("languageId"))
            .containsExactlyInAnyOrder(1, 2);
    }

    @Test
    void languageByKey_singleId_returnsOneLanguage() {
        Map<String, Object> data = execute("{ languageByKey(language_id: [3]) { languageId } }");
        assertThat(data).extractingByKey("languageByKey", as(LIST))
            .hasSize(1)
            .first(as(MAP)).containsEntry("languageId", 3);
    }

    // ===== customerById lookup query =====

    @Test
    void customerById_listKeyAndScalarKey_filtersCorrectly() {
        // Customers 1,2,4 are in store 1; 3,5 are in store 2
        Map<String, Object> data = execute(
            "{ customerById(customer_id: [\"1\", \"2\", \"4\", \"3\"], store_id: \"1\") { customerId } }");
        // Only IDs 1, 2, 4 are in store 1
        assertThat(data).extractingByKey("customerById", as(list(Map.class)))
            .hasSize(3)
            .extracting(c -> c.get("customerId"))
            .containsExactlyInAnyOrder(1, 2, 4);
    }

    @Test
    void customerById_noMatchForStore_returnsEmpty() {
        // Customer 3 is in store 2, requesting store 1 → no match
        Map<String, Object> data = execute(
            "{ customerById(customer_id: [\"3\"], store_id: \"1\") { customerId } }");
        assertThat(data).extractingByKey("customerById", as(LIST)).isEmpty();
    }

    // ===== filmsConnection — forward pagination =====

    @Test
    void filmsConnection_firstPage_returnsFirstNFilms() {
        Map<String, Object> data = execute(
            "{ filmsConnection(first: 2) { nodes { title } pageInfo { hasNextPage hasPreviousPage } } }");
        var conn = assertThat(data).extractingByKey("filmsConnection", as(MAP));
        conn.extractingByKey("nodes", as(list(Map.class)))
            .hasSize(2)
            .extracting(n -> n.get("title"))
            .containsExactly("ACADEMY DINOSAUR", "ACE GOLDFINGER");
        conn.extractingByKey("pageInfo", as(MAP))
            .containsEntry("hasNextPage", true)
            .containsEntry("hasPreviousPage", false);
    }

    @Test
    void filmsConnection_defaultPageSize_returnsUpToDefault() {
        // Default page size is 100; test DB has 5 films, so all 5 are returned
        Map<String, Object> data = execute(
            "{ filmsConnection { nodes { filmId } } }");
        assertThat(data).extractingByKey("filmsConnection", as(MAP))
            .extractingByKey("nodes", as(LIST)).hasSize(5);
    }

    @Test
    void filmsConnection_withAfterCursor_returnsNextPage() {
        // Get page 1 cursor, then use it to get page 2
        Map<String, Object> page1Data = execute(
            "{ filmsConnection(first: 2) { edges { cursor node { title } } pageInfo { endCursor } } }");
        String endCursor = assertThat(page1Data).extractingByKey("filmsConnection", as(MAP))
            .extractingByKey("pageInfo", as(MAP))
            .extractingByKey("endCursor", as(STRING))
            .isNotNull()
            .actual();

        Map<String, Object> page2Data = execute(
            "{ filmsConnection(first: 2, after: \"" + endCursor + "\") { nodes { title } pageInfo { hasNextPage } } }");
        var conn2 = assertThat(page2Data).extractingByKey("filmsConnection", as(MAP));
        conn2.extractingByKey("nodes", as(list(Map.class)))
            .extracting(n -> n.get("title"))
            .containsExactly("ADAPTATION HOLES", "AFFAIR PREJUDICE");
        conn2.extractingByKey("pageInfo", as(MAP))
            .containsEntry("hasNextPage", true);
    }

    @Test
    void filmsConnection_lastPage_hasNextPageFalse() {
        Map<String, Object> data = execute(
            "{ filmsConnection(first: 5) { nodes { title } pageInfo { hasNextPage } } }");
        var conn = assertThat(data).extractingByKey("filmsConnection", as(MAP));
        conn.extractingByKey("nodes", as(LIST)).hasSize(5);
        conn.extractingByKey("pageInfo", as(MAP)).containsEntry("hasNextPage", false);
    }

    // ===== filmsConnection — backward pagination =====

    @Test
    void filmsConnection_rejectsFirstAndLastTogether() {
        // Relay spec: must reject when both first and last are supplied. The connection helper
        // throws IllegalArgumentException with both arg names in the message; under R12 §3 the
        // fetcher's redacting catch arm replaces the raw message with a UUID-keyed redaction
        // (the privacy contract) so the client-visible payload no longer carries "first"/"last".
        // Schemas that want the raw IAE message back must declare {handler: GENERIC, className:
        // "java.lang.IllegalArgumentException"} on the payload's @error type.
        var result = executeRaw(
            "{ filmsConnection(first: 2, last: 2) { nodes { title } } }");
        assertThat(result.getErrors()).isNotEmpty();
        assertThat(result.getErrors().get(0).getMessage())
            .startsWith("An error occurred. Reference: ")
            .endsWith(".");
    }

    @Test
    void filmsConnection_backward_returnsLastNFilms() {
        Map<String, Object> data = execute(
            "{ filmsConnection(last: 2) { nodes { title } pageInfo { hasNextPage hasPreviousPage } } }");
        var conn = assertThat(data).extractingByKey("filmsConnection", as(MAP));
        // last 2 in ascending film_id order: AFFAIR PREJUDICE, AGENT TRUMAN
        conn.extractingByKey("nodes", as(list(Map.class)))
            .hasSize(2)
            .extracting(n -> n.get("title"))
            .containsExactly("AFFAIR PREJUDICE", "AGENT TRUMAN");
        conn.extractingByKey("pageInfo", as(MAP))
            .containsEntry("hasPreviousPage", true)
            .containsEntry("hasNextPage", false);
    }

    @Test
    void filmsConnection_backward_withBeforeCursor_returnsPrevPage() {
        // First get the last page to obtain a before cursor (startCursor of last page)
        Map<String, Object> lastPageData = execute(
            "{ filmsConnection(last: 2) { nodes { title } pageInfo { startCursor } } }");
        String startCursor = assertThat(lastPageData).extractingByKey("filmsConnection", as(MAP))
            .extractingByKey("pageInfo", as(MAP))
            .extractingByKey("startCursor", as(STRING))
            .isNotNull()
            .actual();

        // Paginate backwards before that cursor
        Map<String, Object> prevPageData = execute(
            "{ filmsConnection(last: 2, before: \"" + startCursor + "\") { nodes { title } } }");
        // last: 2 returns [AFFAIR PREJUDICE (4), AGENT TRUMAN (5)]; startCursor = cursor(4).
        // "2 items before cursor(4)" in ascending order = items 2, 3: ACE GOLDFINGER, ADAPTATION HOLES.
        assertThat(prevPageData).extractingByKey("filmsConnection", as(MAP))
            .extractingByKey("nodes", as(list(Map.class)))
            .extracting(n -> n.get("title"))
            .containsExactly("ACE GOLDFINGER", "ADAPTATION HOLES");
    }

    // ===== filmsConnection / stores — totalCount =====

    @Test
    void filmsConnection_totalCount_returnsTotalRowCount() {
        // Structural FilmsConnection declares totalCount: Int; root fetcher binds (table, condition)
        // onto ConnectionResult so ConnectionHelper.totalCount issues SELECT count(*) on demand.
        Map<String, Object> data = execute(
            "{ filmsConnection { totalCount } }");
        assertThat(data).extractingByKey("filmsConnection", as(MAP))
            .containsEntry("totalCount", 5);
    }

    @Test
    void filmsOrderedConnection_totalCount_underFilter_appliesSamePredicate() {
        // SELECT count(*) is run with the same Condition the page query used; rating=G filter
        // restricts the count to the 2 G-rated films in the seed data.
        Map<String, Object> data = execute(
            "{ filmsOrderedConnection(rating: G, first: 1) { totalCount nodes { title } } }");
        var conn = assertThat(data).extractingByKey("filmsOrderedConnection", as(MAP));
        conn.containsEntry("totalCount", 2);
        conn.extractingByKey("nodes", as(LIST)).hasSize(1); // page-trimmed by first:1
    }

    @Test
    void synthesisedConnection_totalCount_returnsRowCount() {
        // `stores: [Store!]! @asConnection` synthesises QueryStoresConnection which always carries
        // totalCount: Int. Two stores are seeded.
        Map<String, Object> data = execute(
            "{ stores { totalCount } }");
        assertThat(data).extractingByKey("stores", as(MAP))
            .containsEntry("totalCount", 2);
    }

    @Test
    void filmsConnection_totalCount_isLazyOnSelection_noCountSqlWhenUnselected() {
        SQL_LOG.clear();
        execute("{ filmsConnection(first: 2) { nodes { title } pageInfo { hasNextPage } } }");
        // graphql-java only invokes the totalCount resolver when the client picks the field.
        // No `select count` should appear among the rendered statements for this query.
        assertThat(SQL_LOG)
            .as("no SELECT count statement should be issued when totalCount is not selected")
            .noneMatch(s -> s.contains("select count"));
    }

    @Test
    void filmsConnection_totalCount_selected_doesIssueCountSql() {
        // Companion ratchet: when totalCount IS selected, exactly one `select count` runs.
        SQL_LOG.clear();
        execute("{ filmsConnection { totalCount } }");
        assertThat(SQL_LOG)
            .filteredOn(s -> s.contains("select count"))
            .as("selecting totalCount should issue exactly one SELECT count statement")
            .hasSize(1);
    }

    // ===== filmsOrderedConnection — dynamic ordering pagination =====

    @Test
    void filmsOrderedConnection_defaultOrder_paginatesById() {
        Map<String, Object> data = execute(
            "{ filmsOrderedConnection(first: 2) { nodes { filmId title } } }");
        assertThat(data).extractingByKey("filmsOrderedConnection", as(MAP))
            .extractingByKey("nodes", as(list(Map.class)))
            .hasSize(2)
            .extracting(n -> n.get("title"))
            .containsExactly("ACADEMY DINOSAUR", "ACE GOLDFINGER");
    }

    @Test
    void filmsOrderedConnection_orderByTitle_paginatesAlphabetically() {
        Map<String, Object> data = execute(
            "{ filmsOrderedConnection(order: [{field: TITLE, direction: ASC}], first: 3) { nodes { title } } }");
        // ACADEMY DINOSAUR < ACE GOLDFINGER < ADAPTATION HOLES alphabetically
        assertThat(data).extractingByKey("filmsOrderedConnection", as(MAP))
            .extractingByKey("nodes", as(list(Map.class)))
            .hasSize(3)
            .extracting(n -> n.get("title"))
            .containsExactly("ACADEMY DINOSAUR", "ACE GOLDFINGER", "ADAPTATION HOLES");
    }

    @Test
    void filmsOrderedConnection_filterPlusOrderPlusPagination_combinesAllThree() {
        // Exercises buildFilters + buildOrderBySpec + buildPaginationSpec on one field.
        // Seed data: two G-rated films — ACE GOLDFINGER, AFFAIR PREJUDICE.
        Map<String, Object> data = execute(
            "{ filmsOrderedConnection(rating: G, order: [{field: TITLE, direction: ASC}], first: 1) { " +
            "nodes { title } pageInfo { hasNextPage } } }");
        var conn = assertThat(data).extractingByKey("filmsOrderedConnection", as(MAP));
        conn.extractingByKey("nodes", as(list(Map.class)))
            .extracting(n -> n.get("title")).containsExactly("ACE GOLDFINGER");
        conn.extractingByKey("pageInfo", as(MAP))
            .containsEntry("hasNextPage", true);
    }

    @Test
    void filmsOrderedConnection_orderByTitle_cursorNavigation() {
        // Get page 1 ordered by title, then follow cursor
        Map<String, Object> page1Data = execute(
            "{ filmsOrderedConnection(order: [{field: TITLE, direction: ASC}], first: 2) { " +
            "nodes { title } pageInfo { endCursor hasNextPage } } }");
        var pageInfo1 = assertThat(page1Data).extractingByKey("filmsOrderedConnection", as(MAP))
            .extractingByKey("pageInfo", as(MAP));
        String endCursor = pageInfo1.extractingByKey("endCursor", as(STRING)).isNotNull().actual();
        pageInfo1.containsEntry("hasNextPage", true);

        Map<String, Object> page2Data = execute(
            "{ filmsOrderedConnection(order: [{field: TITLE, direction: ASC}], first: 2, after: \"" +
            endCursor + "\") { nodes { title } } }");
        assertThat(page2Data).extractingByKey("filmsOrderedConnection", as(MAP))
            .extractingByKey("nodes", as(list(Map.class)))
            .extracting(n -> n.get("title"))
            .containsExactly("ADAPTATION HOLES", "AFFAIR PREJUDICE");
    }

    // ===== G5 inline TableField — single-hop FK =====

    @Test
    void inlineTableField_singleHopFk_returnsNestedRecord() {
        // Customer 1 is in store 1, with address_id=1 → '47 MySakila Drive'
        Map<String, Object> data = execute(
            "{ customerById(customer_id: [\"1\"], store_id: \"1\") { customerId address { addressId address } } }");
        assertThat(data).extractingByKey("customerById", as(LIST))
            .hasSize(1)
            .first(as(MAP)).extractingByKey("address", as(MAP))
            .isNotNull()
            .containsEntry("addressId", 1)
            .containsEntry("address", "47 MySakila Drive");
    }

    // ===== G5 inline TableField — multi-hop FK =====

    @Test
    void inlineTableField_multiHopFk_walksTwoFkHops() {
        // customer 1, store 1, address 1 '47 MySakila Drive'; customer 2, store 1, address 2.
        Map<String, Object> data = execute(
            "{ customerById(customer_id: [\"1\", \"2\"], store_id: \"1\") { customerId storeAddress { address } } }");
        assertThat(data).extractingByKey("customerById", as(list(Map.class)))
            .hasSize(2)
            .filteredOn(c -> Integer.valueOf(1).equals(c.get("customerId")))
            .singleElement(as(MAP))
            .extractingByKey("storeAddress", as(MAP))
            .containsEntry("address", "47 MySakila Drive");
    }

    // ===== G5 inline TableField — single-hop FK, list cardinality =====

    @Test
    void inlineTableField_listCardinality_returnsAllChildren() {
        // Store 1 holds customers 1, 2, 4. Store 2 holds 3, 5. Order by customer_id (PK).
        Map<String, Object> data = execute(
            "{ storeById(store_id: [1]) { storeId customers { customerId firstName } } }");
        assertThat(data).extractingByKey("storeById", as(LIST))
            .hasSize(1)
            .first(as(MAP)).extractingByKey("customers", as(list(Map.class)))
            .extracting(c -> c.get("customerId"))
            .containsExactly(1, 2, 4);
    }

    // ===== G5 inline TableField — self-referential recursion =====

    @Test
    void inlineTableField_selfRef_depth2_recursionTerminatesOnSelectionSet() {
        // Category tree:
        //   Genre (id=1)
        //   └── Action (id=2)
        //       └── Thriller (id=5)
        // Depth-2 query: start at Thriller, walk parent → parent. Verifies Plan Decision 5's
        // "recursion terminates on client selection depth" invariant end-to-end.
        Map<String, Object> data = execute(
            "{ categoryById(category_id: [5]) { name parent { name parent { name } } } }");
        var thriller = assertThat(data).extractingByKey("categoryById", as(LIST))
            .hasSize(1)
            .first(as(MAP));
        thriller.containsEntry("name", "Thriller");
        thriller.extractingByKey("parent", as(MAP))
            .containsEntry("name", "Action")
            .extractingByKey("parent", as(MAP))
            .containsEntry("name", "Genre");
    }

    @Test
    void inlineTableField_selfRef_listCardinality_returnsChildren() {
        // Genre (id=1) has children: Action, Animation, Comedy (ids 2, 3, 4).
        Map<String, Object> data = execute(
            "{ categoryById(category_id: [1]) { name children { name } } }");
        var genre = assertThat(data).extractingByKey("categoryById", as(LIST))
            .hasSize(1)
            .first(as(MAP));
        genre.containsEntry("name", "Genre");
        genre.extractingByKey("children", as(list(Map.class)))
            .extracting(c -> c.get("name"))
            .containsExactly("Action", "Animation", "Comedy");
    }

    @Test
    void inlineTableField_selfRef_nonRootCategory_hasNoChildren() {
        // Thriller (id=5) is a leaf — children list is empty.
        Map<String, Object> data = execute(
            "{ categoryById(category_id: [5]) { name children { name } } }");
        assertThat(data).extractingByKey("categoryById", as(LIST))
            .first(as(MAP))
            .extractingByKey("children", as(LIST)).isEmpty();
    }

    @Test
    void inlineTableField_selfRef_optionalParent_nullable() {
        // Genre (id=1) has no parent — parent is null.
        Map<String, Object> data = execute(
            "{ categoryById(category_id: [1]) { name parent { name } } }");
        assertThat(data).extractingByKey("categoryById", as(LIST))
            .first(as(MAP))
            .extractingByKey("parent").isNull();
    }

    // ===== argres Phase 2a — inline LookupTableField (Film.actors via film_actor junction) =====

    @Test
    void inlineLookupTableField_returnsMatchingActors() {
        // Film 1 (ACADEMY DINOSAUR) cast: PENELOPE (id=1), NICK (id=2).
        Map<String, Object> data = execute(
            "{ filmById(film_id: [\"1\"]) { actors(actor_id: [1, 2]) { actorId firstName } } }");
        assertThat(data).extractingByKey("filmById", as(LIST))
            .first(as(MAP)).extractingByKey("actors", as(list(Map.class)))
            .extracting(a -> a.get("firstName"))
            .containsExactlyInAnyOrder("PENELOPE", "NICK");
    }

    @Test
    void inlineLookupTableField_preservesInputOrder() {
        // Input [2, 1] should return NICK before PENELOPE.
        Map<String, Object> data = execute(
            "{ filmById(film_id: [\"1\"]) { actors(actor_id: [2, 1]) { firstName } } }");
        assertThat(data).extractingByKey("filmById", as(LIST))
            .first(as(MAP)).extractingByKey("actors", as(list(Map.class)))
            .extracting(a -> a.get("firstName"))
            .containsExactly("NICK", "PENELOPE");
    }

    @Test
    void inlineLookupTableField_fkFilter_excludesActorsNotInFilm() {
        // Film 1 cast: actors 1, 2. Actor 3 (ED) is not in film 1 — the FK chain drops him.
        Map<String, Object> data = execute(
            "{ filmById(film_id: [\"1\"]) { actors(actor_id: [1, 3]) { firstName } } }");
        assertThat(data).extractingByKey("filmById", as(LIST))
            .first(as(MAP)).extractingByKey("actors", as(list(Map.class)))
            .extracting(a -> a.get("firstName")).containsExactly("PENELOPE");
    }

    @Test
    void inlineLookupTableField_emptyInput_returnsEmpty() {
        Map<String, Object> data = execute(
            "{ filmById(film_id: [\"1\"]) { actors(actor_id: []) { firstName } } }");
        assertThat(data).extractingByKey("filmById", as(LIST))
            .first(as(MAP)).extractingByKey("actors", as(LIST)).isEmpty();
    }

    @Test
    void inlineLookupTableField_nullInput_returnsEmpty() {
        // actor_id is optional; omitting it should short-circuit to an empty list (n=0 path).
        Map<String, Object> data = execute(
            "{ filmById(film_id: [\"1\"]) { actors { firstName } } }");
        assertThat(data).extractingByKey("filmById", as(LIST))
            .first(as(MAP)).extractingByKey("actors", as(LIST)).isEmpty();
    }

    @Test
    void inlineLookupTableField_acrossMultipleParents_perFilmFiltering() {
        // Film 2 (ACE GOLDFINGER) cast: PENELOPE (1), ED (3). Film 3 cast: PENELOPE only.
        // Same input [1, 3] on both → film 2 has both; film 3 has only PENELOPE.
        Map<String, Object> data = execute(
            "{ filmById(film_id: [\"2\", \"3\"]) { filmId actors(actor_id: [1, 3]) { firstName } } }");
        var films = assertThat(data).extractingByKey("filmById", as(list(Map.class))).hasSize(2);

        var film2 = films.element(0, as(MAP)).containsEntry("filmId", 2);
        film2.extractingByKey("actors", as(list(Map.class)))
            .extracting(a -> a.get("firstName"))
            .containsExactlyInAnyOrder("PENELOPE", "ED");

        var film3 = films.element(1, as(MAP)).containsEntry("filmId", 3);
        film3.extractingByKey("actors", as(list(Map.class)))
            .extracting(a -> a.get("firstName")).containsExactly("PENELOPE");
    }

    // ===== argres Phase 2b: Split(Lookup)TableField DataLoader fan-out =====

    @Test
    void splitTableField_singleParent_returnsItsChildren() {
        // Language.films (SplitTableField) — language 1 has films 1-5 seeded.
        Map<String, Object> data = execute(
            "{ languageByKey(language_id: [1]) { languageId films { filmId } } }");
        assertThat(data).extractingByKey("languageByKey", as(LIST))
            .hasSize(1)
            .first(as(MAP)).extractingByKey("films", as(list(Map.class)))
            .extracting(f -> f.get("filmId"))
            .containsExactlyInAnyOrder(1, 2, 3, 4, 5);
    }

    @Test
    void splitTableField_multipleParents_scatterPerParent() {
        // languages 1, 2, 3 — only language 1 has films in the seed. DataLoader batches the
        // three parent lookups into one SQL round-trip; the scatter correctly assigns all
        // films to language 1 and empty lists to languages 2 and 3 (no cross-contamination).
        QUERY_COUNT.set(0);
        Map<String, Object> data = execute(
            "{ languageByKey(language_id: [1, 2, 3]) { languageId films { filmId } } }");
        // Expect 2 JDBC round-trips: 1 for languageByKey root + 1 batched for films. An
        // unbatched scatter would fire 1 + N=3 = 4. This is the primary proof that the
        // DataLoader fan-in works — the value assertions below only prove scatter correctness.
        assertThat(QUERY_COUNT.get()).isEqualTo(2);

        var langs = assertThat(data).extractingByKey("languageByKey", as(list(Map.class))).hasSize(3);
        langs.filteredOn(l -> Integer.valueOf(1).equals(l.get("languageId")))
            .singleElement(as(MAP))
            .extractingByKey("films", as(LIST)).hasSize(5);
        langs.filteredOn(l -> Integer.valueOf(2).equals(l.get("languageId")))
            .singleElement(as(MAP))
            .extractingByKey("films", as(LIST)).isEmpty();
        langs.filteredOn(l -> Integer.valueOf(3).equals(l.get("languageId")))
            .singleElement(as(MAP))
            .extractingByKey("films", as(LIST)).isEmpty();
    }

    @Test
    void splitTableField_preservesParentInputOrder_scatterAlignsByIdx() {
        // Non-identity parent order: [3, 1, 2] — only language 1 has films. If the scatter
        // keyed children by parent-PK instead of __idx__, the films array would land on a
        // different slot than the language-1 slot. Asserts both (a) parent order preservation
        // from VALUES+JOIN on the root lookup, and (b) __idx__ scatter alignment on the child
        // DataLoader.
        Map<String, Object> data = execute(
            "{ languageByKey(language_id: [3, 1, 2]) { languageId films { filmId } } }");
        var langs = assertThat(data).extractingByKey("languageByKey", as(list(Map.class)));
        langs.extracting(l -> l.get("languageId")).containsExactly(3, 1, 2);
        langs.element(0, as(MAP)).extractingByKey("films", as(LIST)).isEmpty();
        langs.element(1, as(MAP)).extractingByKey("films", as(LIST)).hasSize(5);
        langs.element(2, as(MAP)).extractingByKey("films", as(LIST)).isEmpty();
    }

    @SuppressWarnings("unchecked")
    @Test
    void splitLookupTableField_filtersActorsPerFilm() {
        // Film 1 cast: PENELOPE (1), NICK (2). Film 2 cast: PENELOPE (1), ED (3).
        // Film 3 cast: PENELOPE (1). actor_id: [1, 2] → film 1 gets {1,2}; film 2 gets {1};
        // film 3 gets {1}.
        QUERY_COUNT.set(0);
        Map<String, Object> data = execute(
            "{ films { filmId actorsBySplitLookup(actor_id: [1, 2]) { actorId } } }");
        // 5 parent films + 1 batched SplitLookup child = 2 round-trips. Unbatched: 1 + 5 = 6.
        assertThat(QUERY_COUNT.get()).isEqualTo(2);
        List<Map<String, Object>> films = (List<Map<String, Object>>) data.get("films");

        var byId = films.stream().collect(java.util.stream.Collectors.toMap(
            f -> (Integer) f.get("filmId"), f -> (List<Map<String, Object>>) f.get("actorsBySplitLookup")));

        assertThat(byId.get(1)).extracting(a -> a.get("actorId")).containsExactlyInAnyOrder(1, 2);
        assertThat(byId.get(2)).extracting(a -> a.get("actorId")).containsExactly(1);
        assertThat(byId.get(3)).extracting(a -> a.get("actorId")).containsExactly(1);
    }

    @SuppressWarnings("unchecked")
    @Test
    void splitLookupTableField_filterExcludesActorsNotInFilm() {
        // actor_id: [3] → only films 2 and 5 have actor 3. Films 1, 3, 4 return empty lists;
        // scatter correctly places empty sublists in their slots.
        Map<String, Object> data = execute(
            "{ films { filmId actorsBySplitLookup(actor_id: [3]) { actorId } } }");
        List<Map<String, Object>> films = (List<Map<String, Object>>) data.get("films");

        var byId = films.stream().collect(java.util.stream.Collectors.toMap(
            f -> (Integer) f.get("filmId"), f -> (List<Map<String, Object>>) f.get("actorsBySplitLookup")));

        assertThat(byId.get(1)).isEmpty();
        assertThat(byId.get(2)).extracting(a -> a.get("actorId")).containsExactly(3);
        assertThat(byId.get(3)).isEmpty();
        assertThat(byId.get(4)).isEmpty();
        assertThat(byId.get(5)).extracting(a -> a.get("actorId")).containsExactly(3);
    }

    @Test
    void splitLookupTableField_emptyLookupInput_returnsEmptyPerFilm() {
        // Empty @lookupKey list → emptyScatter short-circuit. No DB round-trip for the
        // lookup join; every parent gets an empty sublist.
        QUERY_COUNT.set(0);
        Map<String, Object> data = execute(
            "{ films { filmId actorsBySplitLookup(actor_id: []) { actorId } } }");
        // Parent query only — the empty-input short-circuit returns emptyScatter without
        // touching DSL, so no child round-trip fires.
        assertThat(QUERY_COUNT.get()).isEqualTo(1);
        assertThat(data).extractingByKey("films", as(list(Map.class)))
            .allSatisfy(f -> assertThat(f.get("actorsBySplitLookup")).asInstanceOf(LIST).isEmpty());
    }

    @Test
    void splitLookupTableField_nullLookupInput_returnsEmptyPerFilm() {
        // Omitting the @lookupKey arg → null → env.getArgument returns null → rowCount=0 →
        // inputRows helper returns new Row[0] → emptyScatter short-circuit.
        QUERY_COUNT.set(0);
        Map<String, Object> data = execute(
            "{ films { filmId actorsBySplitLookup { actorId } } }");
        // Same short-circuit as the empty-list case — parent query only.
        assertThat(QUERY_COUNT.get()).isEqualTo(1);
        assertThat(data).extractingByKey("films", as(list(Map.class)))
            .allSatisfy(f -> assertThat(f.get("actorsBySplitLookup")).asInstanceOf(LIST).isEmpty());
    }

    // ===== single-cardinality @splitQuery =====

    @SuppressWarnings("unchecked")
    @Test
    void splitTableField_singleCardinality_returnsAddressPerCustomer() {
        // Customer.addressSplit (SplitTableField, single cardinality, parent-holds-FK):
        // each customer resolves to its own address. Seeded customer→address mapping:
        //   c1→a1  c2→a2  c3→a3  c4→a1 (shared)  c5→a2 (shared)
        Map<String, Object> data = execute(
            "{ customers { customerId addressSplit { addressId } } }");
        List<Map<String, Object>> customers = (List<Map<String, Object>>) data.get("customers");
        var byId = customers.stream().collect(java.util.stream.Collectors.toMap(
            c -> (Integer) c.get("customerId"),
            c -> (Map<String, Object>) c.get("addressSplit")));
        assertThat(byId.get(1).get("addressId")).isEqualTo(1);
        assertThat(byId.get(2).get("addressId")).isEqualTo(2);
        assertThat(byId.get(3).get("addressId")).isEqualTo(3);
        assertThat(byId.get(4).get("addressId")).isEqualTo(1);  // shared with c1
        assertThat(byId.get(5).get("addressId")).isEqualTo(2);  // shared with c2
    }

    @SuppressWarnings("unchecked")
    @Test
    void splitTableField_singleCardinality_dedupesSharedFk_oneBatchRoundTrip() {
        // Five customers hit the addressSplit DataLoader; caching-enabled dedup collapses the
        // 5 loads to 3 distinct keys (addresses 1, 2, 3). Total round-trips: 1 (customers root)
        // + 1 (batched addressSplit rows method) = 2. An un-batched scatter would fire 1 + 5 = 6.
        QUERY_COUNT.set(0);
        Map<String, Object> data = execute(
            "{ customers { customerId addressSplit { addressId district } } }");
        assertThat(QUERY_COUNT.get()).isEqualTo(2);
        List<Map<String, Object>> customers = (List<Map<String, Object>>) data.get("customers");
        assertThat(customers).hasSize(5);
        // Customers 1 and 4 share address 1 → the same district value resolves for both.
        var byId = customers.stream().collect(java.util.stream.Collectors.toMap(
            c -> (Integer) c.get("customerId"),
            c -> (Map<String, Object>) c.get("addressSplit")));
        assertThat(byId.get(1).get("district")).isEqualTo(byId.get(4).get("district"));
        assertThat(byId.get(2).get("district")).isEqualTo(byId.get(5).get("district"));
    }

    @Test
    void splitTableField_singleCardinality_nullFk_shortCircuitsWithoutLoaderDispatch() {
        // Store 2 has manager_staff_id = NULL in init.sql. The §4 null-FK short-circuit in
        // StoreFetchers.manager returns CompletableFuture.completedFuture(null) before
        // dispatching to the DataLoader, so no rows-method round-trip fires for that store.
        QUERY_COUNT.set(0);
        Map<String, Object> data = execute(
            "{ storeById(store_id: [2]) { storeId manager { staffId } } }");
        // Parent query only — null-FK short-circuit eliminates the child round-trip entirely.
        assertThat(QUERY_COUNT.get()).isEqualTo(1);
        assertThat(data).extractingByKey("storeById", as(LIST))
            .hasSize(1)
            .first(as(MAP))
            .containsEntry("storeId", 2)
            .containsEntry("manager", null);
    }

    @Test
    void splitTableField_singleCardinality_nonNullFk_resolvesManager() {
        // Store 1 has manager_staff_id = 1 → Mike Hillyer. Covers the happy path for
        // Store.manager alongside the null-FK test above.
        Map<String, Object> data = execute(
            "{ storeById(store_id: [1]) { storeId manager { staffId firstName } } }");
        assertThat(data).extractingByKey("storeById", as(LIST))
            .hasSize(1)
            .first(as(MAP))
            .extractingByKey("manager", as(MAP))
            .isNotNull()
            .containsEntry("staffId", 1)
            .containsEntry("firstName", "Mike");
    }

    @SuppressWarnings("unchecked")
    @Test
    void splitTableField_singleCardinality_mixedNullAndNonNullFk_scatterAlignsByIdx() {
        // Both stores queried in one batch: store 1 (manager_staff_id=1), store 2 (NULL).
        // Store 2's null short-circuit fires before loader.load, so only store 1 reaches the
        // DataLoader — 1 parent + 1 batched child (for keys=[Row(1)]) = 2 round-trips.
        QUERY_COUNT.set(0);
        Map<String, Object> data = execute(
            "{ storeById(store_id: [1, 2]) { storeId manager { staffId } } }");
        assertThat(QUERY_COUNT.get()).isEqualTo(2);
        List<Map<String, Object>> stores = (List<Map<String, Object>>) data.get("storeById");
        var byId = stores.stream().collect(java.util.stream.Collectors.toMap(
            s -> (Integer) s.get("storeId"), s -> s));
        assertThat(((Map<String, Object>) byId.get(1).get("manager")).get("staffId")).isEqualTo(1);
        assertThat(byId.get(2).get("manager")).isNull();
    }

    // ===== argres Phase 3 — composite-key @lookupKey via @table input type =====

    @Test
    void compositeKeyLookup_returnsMatchingPairs() {
        // film_actor seed: (film 1, actor 1), (film 1, actor 2), (film 2, actor 1), (film 2, actor 3),
        // (film 3, actor 1), (film 4, actor 2), (film 5, actor 3). Composite-key join on both
        // film_id AND actor_id — query two existing pairs.
        Map<String, Object> data = execute(
            "{ filmActorsByKey(key: [{filmId: 1, actorId: 2}, {filmId: 2, actorId: 3}]) { filmId actorId } }");
        assertThat(data).extractingByKey("filmActorsByKey", as(list(Map.class)))
            .hasSize(2)
            .extracting(r -> r.get("filmId") + ":" + r.get("actorId"))
            .containsExactly("1:2", "2:3");
    }

    @Test
    void compositeKeyLookup_preservesInputOrder() {
        // VALUES+JOIN preserves input order via the derived table's idx column, even for composite
        // keys. Reverse the input to prove ordering is not coincidentally PK-sorted.
        Map<String, Object> data = execute(
            "{ filmActorsByKey(key: [{filmId: 3, actorId: 1}, {filmId: 1, actorId: 2}, {filmId: 2, actorId: 1}]) { filmId actorId } }");
        assertThat(data).extractingByKey("filmActorsByKey", as(list(Map.class)))
            .extracting(r -> r.get("filmId") + ":" + r.get("actorId"))
            .containsExactly("3:1", "1:2", "2:1");
    }

    @Test
    void compositeKeyLookup_mismatchedPairExcluded() {
        // (film 4, actor 1) is NOT a row in film_actor (film 4's cast is actor 2 only). Both
        // film 4 and actor 1 exist individually, but the composite JOIN rejects the pair.
        // (film 1, actor 1) is a real pair → returned.
        Map<String, Object> data = execute(
            "{ filmActorsByKey(key: [{filmId: 4, actorId: 1}, {filmId: 1, actorId: 1}]) { filmId actorId } }");
        assertThat(data).extractingByKey("filmActorsByKey", as(LIST))
            .hasSize(1)
            .first(as(MAP))
            .containsEntry("filmId", 1)
            .containsEntry("actorId", 1);
    }

    @Test
    void compositeKeyLookup_emptyInput_returnsEmpty() {
        Map<String, Object> data = execute(
            "{ filmActorsByKey(key: []) { filmId actorId } }");
        assertThat(data).extractingByKey("filmActorsByKey", as(LIST)).isEmpty();
    }

    // ===== R50 phase (g-C) — composite-PK NodeId @lookupKey via DecodedRecord =====
    //
    // FilmActor is a composite-PK NodeType (PK columns ACTOR_ID, FILM_ID per init.sql). Each
    // opaque ID decodes once per row at the arg layer to a Record2<Integer,Integer> and the
    // generator emits a VALUES(idx, actor_id, film_id) derived table joined on the full
    // composite key. Carrier-driven ThrowOnMismatch surfaces typeId mismatches as
    // GraphqlErrorException; missing rows are simply absent (positional output is dense, not
    // sparse, since orderBy(idx) is on the input, not the join target).

    @Test
    void compositeNodeIdLookup_listIds_returnsRowsInInputOrder() {
        // Encode three real film_actor pairs out of seed order to prove (idx) preservation:
        // (actor=2, film=1), (actor=3, film=2), (actor=2, film=4). The dispatcher decodes each
        // base64 ID to Record2(actor_id, film_id), then orderBy(idx) preserves input position.
        String id1 = no.sikt.graphitron.generated.util.NodeIdEncoder.encode("FilmActor", 2, 1);
        String id2 = no.sikt.graphitron.generated.util.NodeIdEncoder.encode("FilmActor", 3, 2);
        String id3 = no.sikt.graphitron.generated.util.NodeIdEncoder.encode("FilmActor", 2, 4);
        Map<String, Object> data = execute(
            "{ filmActorByNodeId(id: [\"" + id1 + "\", \"" + id2 + "\", \"" + id3 + "\"])"
            + " { filmId actorId } }");
        assertThat(data).extractingByKey("filmActorByNodeId", as(list(Map.class)))
            .extracting(r -> r.get("actorId") + ":" + r.get("filmId"))
            .containsExactly("2:1", "3:2", "2:4");
    }

    @Test
    void compositeNodeIdLookup_emptyInput_returnsEmpty() {
        Map<String, Object> data = execute("{ filmActorByNodeId(id: []) { filmId } }");
        assertThat(data).extractingByKey("filmActorByNodeId", as(LIST)).isEmpty();
    }

    @Test
    void compositeNodeIdLookup_unknownKey_excludedFromResult() {
        // (actor=99, film=99) decodes successfully but no such film_actor row exists. The JOIN
        // filters it out — the result includes the matching row only. Length-mismatch between
        // inputs and outputs is allowed by design (cf. compositeKeyLookup_mismatchedPairExcluded).
        String real    = no.sikt.graphitron.generated.util.NodeIdEncoder.encode("FilmActor", 1, 1);
        String missing = no.sikt.graphitron.generated.util.NodeIdEncoder.encode("FilmActor", 99, 99);
        Map<String, Object> data = execute(
            "{ filmActorByNodeId(id: [\"" + missing + "\", \"" + real + "\"]) { filmId actorId } }");
        assertThat(data).extractingByKey("filmActorByNodeId", as(LIST))
            .hasSize(1)
            .first(as(MAP))
            .containsEntry("actorId", 1)
            .containsEntry("filmId", 1);
    }

    @Test
    void compositeNodeIdLookup_typeMismatch_raisesError() {
        // ThrowOnMismatch contract: a NodeId encoded for the wrong typeId must surface as an
        // error rather than silently producing degenerate VALUES rows. A Customer-prefixed id
        // reaches decodeFilmActor, which returns null on prefix-mismatch; the generated
        // row-builder then throws GraphqlErrorException. The fetcher's try/catch routes the
        // throw through ErrorRouter.redact (privacy: the raw message is logged with a
        // correlation id, never surfaced), producing a single redacted error and null data.
        String wrongType = no.sikt.graphitron.generated.util.NodeIdEncoder.encode("Customer", 1);
        graphql.ExecutionResult result = executeRaw(
            "{ filmActorByNodeId(id: [\"" + wrongType + "\"]) { filmId actorId } }");
        assertThat(result.getErrors()).isNotEmpty();
    }

    // ===== C4: RecordTableField — @record parent + DataLoader language batch =====

    @Test
    void recordTableField_singleFilm_returnsLanguage() {
        // Film 1 (ACADEMY DINOSAUR) has language_id=1 (English).
        // filmDetails is a ConstructorField pass-through; language is a RecordTableField DataLoader.
        Map<String, Object> data = execute(
            "{ filmById(film_id: [\"1\"]) { languageId filmDetails { title language { name } } } }");
        var details = assertThat(data).extractingByKey("filmById", as(LIST))
            .hasSize(1)
            .first(as(MAP))
            .extractingByKey("filmDetails", as(MAP));
        details.containsEntry("title", "ACADEMY DINOSAUR");
        String langName = details.extractingByKey("language", as(LIST))
            .hasSize(1)
            .first(as(MAP))
            .extractingByKey("name", as(STRING)).actual();
        assertThat(langName.trim()).isEqualTo("English");
    }

    @Test
    void recordTableField_multipleParents_batchesIntoOneSqlRoundTrip() {
        // 5 films all have language_id=1. DataLoader should batch all 5 language lookups into 1
        // SQL SELECT (the rowsLanguage method) rather than firing 5 separate queries.
        // Expected: 2 round-trips — 1 for films root query + 1 for the batched language rows.
        QUERY_COUNT.set(0);
        Map<String, Object> data = execute(
            "{ films { languageId filmDetails { language { name } } } }");
        assertThat(QUERY_COUNT.get()).isEqualTo(2);
        // Every film maps to English (language_id=1 for all test-data films).
        assertThat(data).extractingByKey("films", as(list(Map.class)))
            .hasSize(5)
            .allSatisfy(f -> {
                String langName = assertThat(f.get("filmDetails")).asInstanceOf(MAP)
                    .extractingByKey("language", as(LIST))
                    .hasSize(1)
                    .first(as(MAP))
                    .extractingByKey("name", as(STRING)).actual();
                assertThat(langName.trim()).isEqualTo("English");
            });
    }

    @SuppressWarnings("unchecked")
    @Test
    void recordTableField_propertyField_resolvedFromSameRecord() {
        // title is a PropertyField on FilmDetails; it uses ColumnFetcher(DSL.field("title"))
        // which extracts from the same Film Record passed through by the ConstructorField.
        Map<String, Object> data = execute(
            "{ films { filmDetails { title } } }");
        List<Map<String, Object>> films = (List<Map<String, Object>>) data.get("films");
        assertThat(films).hasSize(5);
        assertThat(films).extracting(f -> ((Map<String, Object>) f.get("filmDetails")).get("title"))
            .containsExactly(
                "ACADEMY DINOSAUR", "ACE GOLDFINGER", "ADAPTATION HOLES",
                "AFFAIR PREJUDICE", "AGENT TRUMAN");
    }

    // ===== record-fields Phase 2: RecordLookupTableField — @record parent + @splitQuery + @lookupKey =====

    @Test
    void recordLookupTableField_singleFilm_returnsFilteredActors() {
        // FilmDetails.actorsByLookup: RecordLookupTableField — DataLoader-batched lookup keyed by
        // the Film record's film_id, narrowed by the caller's actor_id list via VALUES-join.
        // Film 1 (ACADEMY DINOSAUR) cast: PENELOPE (1), NICK (2).
        Map<String, Object> data = execute(
            "{ filmById(film_id: [\"1\"]) { filmDetails { actorsByLookup(actor_id: [1, 2]) { actorId firstName } } } }");
        assertThat(data).extractingByKey("filmById", as(LIST))
            .first(as(MAP))
            .extractingByKey("filmDetails", as(MAP))
            .extractingByKey("actorsByLookup", as(list(Map.class)))
            .extracting(a -> a.get("firstName"))
            .containsExactlyInAnyOrder("PENELOPE", "NICK");
    }

    @Test
    void recordLookupTableField_fkFilter_excludesActorsNotInFilm() {
        // actor_id: [1, 3] on Film 1 → actor 3 (ED) is not in film 1's cast; FK chain drops him.
        Map<String, Object> data = execute(
            "{ filmById(film_id: [\"1\"]) { filmDetails { actorsByLookup(actor_id: [1, 3]) { firstName } } } }");
        assertThat(data).extractingByKey("filmById", as(LIST))
            .first(as(MAP))
            .extractingByKey("filmDetails", as(MAP))
            .extractingByKey("actorsByLookup", as(list(Map.class)))
            .extracting(a -> a.get("firstName")).containsExactly("PENELOPE");
    }

    @SuppressWarnings("unchecked")
    @Test
    void recordLookupTableField_multipleParents_batchesIntoOneRoundTrip() {
        // 5 films + 1 batched RecordLookup child = 2 round-trips. Unbatched: 1 + 5 = 6.
        // Film 2 cast: PENELOPE (1), ED (3). Film 3 cast: PENELOPE (1). actor_id: [1, 3] →
        // film 1 gets {1}; film 2 gets {1, 3}; film 3 gets {1}.
        QUERY_COUNT.set(0);
        Map<String, Object> data = execute(
            "{ films { filmId filmDetails { actorsByLookup(actor_id: [1, 3]) { actorId } } } }");
        assertThat(QUERY_COUNT.get()).isEqualTo(2);
        List<Map<String, Object>> films = (List<Map<String, Object>>) data.get("films");

        var byId = films.stream().collect(java.util.stream.Collectors.toMap(
            f -> (Integer) f.get("filmId"),
            f -> (List<Map<String, Object>>) ((Map<String, Object>) f.get("filmDetails")).get("actorsByLookup")));

        assertThat(byId.get(1)).extracting(a -> a.get("actorId")).containsExactly(1);
        assertThat(byId.get(2)).extracting(a -> a.get("actorId")).containsExactlyInAnyOrder(1, 3);
        assertThat(byId.get(3)).extracting(a -> a.get("actorId")).containsExactly(1);
    }

    @Test
    void recordLookupTableField_emptyLookupInput_shortCircuitsNoChildQuery() {
        // Empty @lookupKey → inputRows helper returns new Row[0] → emptyScatter short-circuit.
        // No child round-trip fires.
        QUERY_COUNT.set(0);
        Map<String, Object> data = execute(
            "{ films { filmId filmDetails { actorsByLookup(actor_id: []) { actorId } } } }");
        assertThat(QUERY_COUNT.get()).isEqualTo(1);
        assertThat(data).extractingByKey("films", as(list(Map.class))).allSatisfy(f ->
            assertThat(f.get("filmDetails")).asInstanceOf(MAP)
                .extractingByKey("actorsByLookup", as(LIST)).isEmpty());
    }

    @Test
    void recordLookupTableField_nullLookupInput_shortCircuitsNoChildQuery() {
        // Omitting @lookupKey arg → null → rowCount=0 path → emptyScatter short-circuit.
        QUERY_COUNT.set(0);
        Map<String, Object> data = execute(
            "{ films { filmId filmDetails { actorsByLookup { actorId } } } }");
        assertThat(QUERY_COUNT.get()).isEqualTo(1);
        assertThat(data).extractingByKey("films", as(list(Map.class))).allSatisfy(f ->
            assertThat(f.get("filmDetails")).asInstanceOf(MAP)
                .extractingByKey("actorsByLookup", as(LIST)).isEmpty());
    }

    // ===== NestingField — plain-object nested types =====

    @Test
    void nestingField_nestedScalars_returnCorrectValues() {
        // Film 1 (ACADEMY DINOSAUR) was seeded with release_year 2006.
        Map<String, Object> data = execute(
            "{ filmById(film_id: [\"1\"]) { summary { title releaseYear } } }");
        assertThat(data).extractingByKey("filmById", as(LIST))
            .first(as(MAP))
            .extractingByKey("summary", as(MAP))
            .containsEntry("title", "ACADEMY DINOSAUR")
            .containsEntry("releaseYear", 2006);
    }

    @Test
    void nestingField_fieldNameRemap_resolvesToParentColumn() {
        // FilmSummary.originalTitle @field(name: "TITLE") resolves to FILM.TITLE despite
        // the distinct GraphQL field name. Locks the @field(name:) remap at nested depth.
        Map<String, Object> data = execute(
            "{ filmById(film_id: [\"1\"]) { summary { originalTitle } } }");
        assertThat(data).extractingByKey("filmById", as(LIST))
            .first(as(MAP))
            .extractingByKey("summary", as(MAP))
            .containsEntry("originalTitle", "ACADEMY DINOSAUR");
    }

    @Test
    void nestingField_multiLevelNesting_resolvesThroughTransparentNesting() {
        // Film.info.meta.title + Film.info.meta.length both resolve against the outer
        // Film table. ACADEMY DINOSAUR / 86 minutes in the seed data.
        Map<String, Object> data = execute(
            "{ filmById(film_id: [\"1\"]) { info { releaseYear meta { title length } } } }");
        var info = assertThat(data).extractingByKey("filmById", as(LIST))
            .first(as(MAP))
            .extractingByKey("info", as(MAP));
        info.containsEntry("releaseYear", 2006);
        info.extractingByKey("meta", as(MAP))
            .containsEntry("title", "ACADEMY DINOSAUR")
            .containsEntry("length", 86);
    }

    @Test
    void nestingField_onlyRequestedColumnsSelected() {
        // Requesting only summary.title must project FILM.TITLE — not releaseYear —
        // since $fields is selection-aware. One round-trip; correct value returned.
        QUERY_COUNT.set(0);
        Map<String, Object> data = execute(
            "{ filmById(film_id: [\"1\"]) { summary { title } } }");
        assertThat(QUERY_COUNT.get()).isEqualTo(1);
        assertThat(data).extractingByKey("filmById", as(LIST))
            .first(as(MAP))
            .extractingByKey("summary", as(MAP))
            .containsOnlyKeys("title")
            .containsEntry("title", "ACADEMY DINOSAUR");
    }

    @Test
    void nestingField_outerListOfFilms_nestedResolvesPerRow() {
        // Requesting summary across the films root list: each row carries its own
        // FilmSummary projection, including the per-row release_year.
        Map<String, Object> data = execute("{ films { filmId summary { releaseYear } } }");
        assertThat(data).extractingByKey("films", as(list(Map.class))).allSatisfy(f ->
            // All seeded films carry release_year 2006
            assertThat(f.get("summary")).asInstanceOf(MAP)
                .containsKey("releaseYear")
                .containsEntry("releaseYear", 2006));
    }

    @Test
    void nestingField_siblingOfTableFields_doesNotDisrupt() {
        // Nesting field projects via $fields on the same outer row as sibling column
        // fields; both must come back correctly on the same query.
        Map<String, Object> data = execute(
            "{ filmById(film_id: [\"1\"]) { filmId title summary { releaseYear } } }");
        var film = assertThat(data).extractingByKey("filmById", as(LIST))
            .first(as(MAP));
        film.containsEntry("filmId", 1)
            .containsEntry("title", "ACADEMY DINOSAUR");
        film.extractingByKey("summary", as(MAP))
            .containsEntry("releaseYear", 2006);
    }

    // ===== NestingField — nested inline TableField / LookupTableField (sfName threading) =====

    @Test
    void nestingField_withNestedInlineTableField_resolvesViaOuterTableAlias() {
        // Film.inlineBundle.language is an inline TableField nested inside a NestingField.
        // The switch arm at depth 1 references sf1.getSelectionSet() (not sf), proving the
        // sfName parameter is threaded through InlineTableFieldEmitter. Without it the
        // generated code would fail to compile (undefined sf at depth 1).
        Map<String, Object> data = execute(
            "{ filmById(film_id: [\"1\"]) { inlineBundle { language { languageId name } } } }");
        var language = assertThat(data).extractingByKey("filmById", as(LIST))
            .first(as(MAP))
            .extractingByKey("inlineBundle", as(MAP))
            .extractingByKey("language", as(MAP));
        language.containsEntry("languageId", 1);
        String name = language.extractingByKey("name", as(STRING)).actual();
        assertThat(name.trim()).isEqualTo("English");
    }

    @Test
    void nestingField_withNestedInlineLookupTableField_appliesLookupKey() {
        // Film.inlineBundle.actorsByKey is an inline LookupTableField nested inside a
        // NestingField. Exercises the sf1-threaded path through InlineLookupTableFieldEmitter
        // (inputRows call, empty-input short-circuit, buildInnerSelect all use sfName).
        Map<String, Object> data = execute(
            "{ filmById(film_id: [\"1\"]) { inlineBundle { actorsByKey(actor_id: [1, 2]) { actorId firstName } } } }");
        assertThat(data).extractingByKey("filmById", as(LIST))
            .first(as(MAP))
            .extractingByKey("inlineBundle", as(MAP))
            .extractingByKey("actorsByKey", as(list(Map.class)))
            .extracting(a -> a.get("firstName"))
            .containsExactlyInAnyOrder("PENELOPE", "NICK");
    }

    @Test
    void nestingField_withNestedInlineLookupTableField_emptyInputShortCircuit() {
        // Empty @lookupKey list hits the rows.length == 0 short-circuit branch in
        // InlineLookupTableFieldEmitter — which also uses sfName in the falseCondition
        // multiset. Parent row still carries the slot, populated as an empty list.
        Map<String, Object> data = execute(
            "{ filmById(film_id: [\"1\"]) { inlineBundle { actorsByKey(actor_id: []) { actorId } } } }");
        assertThat(data).extractingByKey("filmById", as(LIST))
            .first(as(MAP))
            .extractingByKey("inlineBundle", as(MAP))
            .extractingByKey("actorsByKey", as(LIST)).isEmpty();
    }

    // ===== Film.actorsConnection — @splitQuery + @asConnection (plan-split-query-connection §1) =====

    @SuppressWarnings("unchecked")
    @Test
    void splitQueryConnection_firstPagePerParent_batchesInOneRowsRoundTrip() {
        // film_actor seed: film 1 -> {1, 2}, film 2 -> {1, 3}. Two parent films request
        // actorsConnection(first: 1); the rows method partitions by parent idx, each parent
        // gets its own over-fetched top-2 slice via ROW_NUMBER() OVER (PARTITION BY idx).
        // Total round-trips: 1 (films root) + 1 (batched rowsActorsConnection).
        QUERY_COUNT.set(0);
        Map<String, Object> data = execute(
            "{ filmById(film_id: [\"1\", \"2\"]) { filmId actorsConnection(first: 1) "
                + "{ nodes { actorId } pageInfo { hasNextPage hasPreviousPage } } } }");
        assertThat(QUERY_COUNT.get()).isEqualTo(2);
        List<Map<String, Object>> films = (List<Map<String, Object>>) data.get("filmById");
        assertThat(films).hasSize(2);
        var byId = films.stream().collect(java.util.stream.Collectors.toMap(
            f -> (Integer) f.get("filmId"), f -> (Map<String, Object>) f.get("actorsConnection")));
        List<Map<String, Object>> film1Nodes = (List<Map<String, Object>>) byId.get(1).get("nodes");
        List<Map<String, Object>> film2Nodes = (List<Map<String, Object>>) byId.get(2).get("nodes");
        assertThat(film1Nodes).hasSize(1);
        assertThat(film1Nodes.get(0).get("actorId")).isEqualTo(1);
        assertThat(film2Nodes).hasSize(1);
        assertThat(film2Nodes.get(0).get("actorId")).isEqualTo(1);
        assertThat((Map<String, Object>) byId.get(1).get("pageInfo"))
            .extracting("hasNextPage", "hasPreviousPage").containsExactly(true, false);
        assertThat((Map<String, Object>) byId.get(2).get("pageInfo"))
            .extracting("hasNextPage", "hasPreviousPage").containsExactly(true, false);
    }

    @SuppressWarnings("unchecked")
    @Test
    void splitQueryConnection_withAfterCursor_pagesForwardPerParent() {
        // Page 1: actorsConnection(first: 1) → actor 1 for film 1, actor 1 for film 2.
        // Use the endCursor from film 1 to page forward; because both parents share the
        // same arg shape, the second query still batches them into one rows round-trip,
        // and each parent's WHERE (cols) > (cursor_vals) filter plays out inside ROW_NUMBER.
        Map<String, Object> page1 = execute(
            "{ filmById(film_id: [\"1\", \"2\"]) { filmId actorsConnection(first: 1) "
                + "{ edges { cursor } pageInfo { endCursor } } } }");
        String endCursorFilm1 = (String) ((Map<String, Object>) ((Map<String, Object>)
            ((List<Map<String, Object>>) page1.get("filmById")).get(0).get("actorsConnection")).get("pageInfo")).get("endCursor");
        assertThat(endCursorFilm1).isNotNull();
        QUERY_COUNT.set(0);
        Map<String, Object> page2 = execute(
            "{ filmById(film_id: [\"1\", \"2\"]) { filmId actorsConnection(first: 10, after: \""
                + endCursorFilm1 + "\") { nodes { actorId } pageInfo { hasNextPage } } } }");
        assertThat(QUERY_COUNT.get()).isEqualTo(2);
        List<Map<String, Object>> films = (List<Map<String, Object>>) page2.get("filmById");
        var byId = films.stream().collect(java.util.stream.Collectors.toMap(
            f -> (Integer) f.get("filmId"), f -> (Map<String, Object>) f.get("actorsConnection")));
        // Film 1 after actor_id=1 has just actor_id=2; film 2 after actor_id=1 has just actor_id=3.
        assertThat((List<Map<String, Object>>) byId.get(1).get("nodes"))
            .extracting(n -> n.get("actorId")).containsExactly(2);
        assertThat((List<Map<String, Object>>) byId.get(2).get("nodes"))
            .extracting(n -> n.get("actorId")).containsExactly(3);
        assertThat(((Map<String, Object>) byId.get(1).get("pageInfo")).get("hasNextPage")).isEqualTo(false);
    }

    @SuppressWarnings("unchecked")
    @Test
    void splitQueryConnection_dynamicOrderByArg_sortsEachPartitionByNamedField() {
        // plan-split-query-connection.md §2: @splitQuery + @asConnection with a dynamic @orderBy
        // argument. The emitted OrderBy helper accepts the FK-chain terminal alias so column refs
        // bind to the aliased jOOQ Actor table. Order by LAST_NAME descending, then take the first
        // row per parent — that's the actor with the latest last_name in each film.
        // Film 1 actors: {PENELOPE GUINESS, NICK WAHLBERG} → last-desc-first = NICK (WAHLBERG).
        // Film 2 actors: {PENELOPE GUINESS, ED CHASE}      → last-desc-first = PENELOPE (GUINESS).
        Map<String, Object> data = execute(
            "{ filmById(film_id: [\"1\", \"2\"]) { filmId actorsOrderedConnection("
                + "order: [{field: LAST_NAME, direction: DESC}], first: 1) "
                + "{ nodes { actorId firstName lastName } } } }");
        List<Map<String, Object>> films = (List<Map<String, Object>>) data.get("filmById");
        var byId = films.stream().collect(java.util.stream.Collectors.toMap(
            f -> (Integer) f.get("filmId"),
            f -> (List<Map<String, Object>>) ((Map<String, Object>) f.get("actorsOrderedConnection")).get("nodes")));
        assertThat(byId.get(1)).hasSize(1);
        assertThat(byId.get(1).get(0).get("lastName")).isEqualTo("WAHLBERG");
        assertThat(byId.get(2)).hasSize(1);
        assertThat(byId.get(2).get(0).get("lastName")).isEqualTo("GUINESS");
    }

    @SuppressWarnings("unchecked")
    @Test
    void splitQueryConnection_backwardPagination_returnsLastNAscending() {
        // last: 1 with no cursor: the CTE inverts the ORDER BY (actor_id DESC) so ROW_NUMBER()
        // picks the largest actor_id per partition, then ConnectionResult.trimmedResult()
        // re-reverses back to ascending for the GraphQL client.
        Map<String, Object> data = execute(
            "{ filmById(film_id: [\"1\", \"2\"]) { filmId actorsConnection(last: 1) "
                + "{ nodes { actorId } pageInfo { hasNextPage hasPreviousPage } } } }");
        List<Map<String, Object>> films = (List<Map<String, Object>>) data.get("filmById");
        var byId = films.stream().collect(java.util.stream.Collectors.toMap(
            f -> (Integer) f.get("filmId"), f -> (Map<String, Object>) f.get("actorsConnection")));
        // Film 1 last actor is 2; film 2 last actor is 3.
        assertThat((List<Map<String, Object>>) byId.get(1).get("nodes"))
            .extracting(n -> n.get("actorId")).containsExactly(2);
        assertThat((List<Map<String, Object>>) byId.get(2).get("nodes"))
            .extracting(n -> n.get("actorId")).containsExactly(3);
        assertThat(((Map<String, Object>) byId.get(1).get("pageInfo")).get("hasPreviousPage")).isEqualTo(true);
    }

    // ===== SplitTableField / SplitLookupTableField under NestingField
    // (plan-splittablefield-nestingfield) =====

    @SuppressWarnings("unchecked")
    @Test
    void splitTableField_nestedUnderNestingField_batchesPerOuterParent() {
        // FilmInfo.cast is a @splitQuery inside a plain-object NestingField. The outer Film
        // Record is passed through by the NestingField wiring, so key extraction reads
        // FILM_ID off env.getSource() just like at non-nested depth. Two parent films should
        // produce exactly one batched rowsCast invocation.
        // Seed: film 1 → {1, 2}, film 2 → {1, 3}.
        QUERY_COUNT.set(0);
        Map<String, Object> data = execute(
            "{ filmById(film_id: [\"1\", \"2\"]) { filmId info { cast { actorId } } } }");
        assertThat(QUERY_COUNT.get()).isEqualTo(2);
        List<Map<String, Object>> films = (List<Map<String, Object>>) data.get("filmById");
        var byId = films.stream().collect(java.util.stream.Collectors.toMap(
            f -> (Integer) f.get("filmId"),
            f -> (List<Map<String, Object>>) ((Map<String, Object>) f.get("info")).get("cast")));
        assertThat(byId.get(1)).extracting(a -> a.get("actorId")).containsExactlyInAnyOrder(1, 2);
        assertThat(byId.get(2)).extracting(a -> a.get("actorId")).containsExactlyInAnyOrder(1, 3);
    }

    // ===== argres Phase 4: @condition on INPUT_FIELD_DEFINITION =====

    @Test
    @SuppressWarnings("unchecked")
    void inputFieldCondition_tableInput_filtersByFilmId() {
        // FilmConditionInput.filmId carries @condition → condition is classified, wired,
        // and called at runtime. Phase 4b threads nested-arg extraction through ArgCallEmitter,
        // so filmId arrives as the actual String passed in the Map; filmIdCondition builds a
        // real Film.FILM_ID = ? predicate. Expect exactly one row matching filmId == 1.
        Map<String, Object> data = execute(
            "{ filmsWithInputFieldCondition(filter: {filmId: \"1\"}) { filmId } }");
        List<Map<String, Object>> films = (List<Map<String, Object>>) data.get("filmsWithInputFieldCondition");
        assertThat(films).extracting(f -> f.get("filmId")).containsExactly(1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void inputFieldCondition_plainInput_filmTable_filtersByFilmId() {
        // PlainFilmIdInput is used on Film and Language queries → conflict → PojoInputType →
        // PlainInputArg at each call site. For the Film call site, filmId resolves against
        // film → ColumnField with condition is classified, walkInputFieldConditions collects
        // it, and nested-arg extraction delivers the value to filmIdCondition.
        Map<String, Object> data = execute(
            "{ filmsByPlainInput(filter: {filmId: \"2\"}) { filmId } }");
        List<Map<String, Object>> films = (List<Map<String, Object>>) data.get("filmsByPlainInput");
        assertThat(films).extracting(f -> f.get("filmId")).containsExactly(2);
    }

    @Test
    @SuppressWarnings("unchecked")
    void inputFieldCondition_plainInput_languageTable_fieldNotResolved_noFilterApplied_returnsAllLanguages() {
        // For the Language call site, filmId does not exist in the language table →
        // classifyPlainInputFields skips the field → PlainInputArg.fields is empty →
        // walkInputFieldConditions adds nothing → field.filters() is empty → no WHERE.
        Map<String, Object> data = execute(
            "{ languagesByPlainInput(filter: {filmId: \"1\"}) { languageId } }");
        List<Map<String, Object>> languages = (List<Map<String, Object>>) data.get("languagesByPlainInput");
        assertThat(languages).isNotEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void inputFieldCondition_tableInput_overrideFlagOnRealColumn_explicitMethodStillFires() {
        // FilmConditionInputWithOverrideField.filmId carries @condition(override:true).
        // On a field that carries only an explicit @condition with no un-annotated siblings,
        // there's no implicit predicate to suppress, so the override flag is a no-op here;
        // the explicit method still fires. Runtime effect: filter by the passed filmId.
        Map<String, Object> data = execute(
            "{ filmsWithInputFieldOverride(filter: {filmId: \"3\"}) { filmId } }");
        List<Map<String, Object>> films = (List<Map<String, Object>>) data.get("filmsWithInputFieldOverride");
        assertThat(films).extracting(f -> f.get("filmId")).containsExactly(3);
    }

    @Test
    @SuppressWarnings("unchecked")
    void inputFieldCondition_tableInput_outerOverride_preservesInnerExplicitMethod() {
        // Divergence-pinning: outer @condition(override:true) composed with inner
        // @condition on FilmConditionInput.filmId. Legacy "outer owns everything" would
        // drop filmIdCondition and return films 2..5 (outerOverrideMethod is film_id >= 2).
        // Rewrite preserves inner explicit methods across the boundary, producing
        // (film_id >= 2) AND (film_id = 1) → no rows. A regression to legacy semantics
        // would make films non-empty and break this test by name.
        Map<String, Object> data = execute(
            "{ filmsOuterOverrideTableInput(filter: {filmId: \"1\"}) { filmId } }");
        List<Map<String, Object>> films = (List<Map<String, Object>>) data.get("filmsOuterOverrideTableInput");
        assertThat(films).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void inputFieldCondition_nestedTwoLevel_pathWalksThroughNestingField() {
        // NestedFilmInput (outer @table) contains NestingField 'inner' (plain InnerFilmInput);
        // InnerFilmInput.filmId carries @condition. walkInputFieldConditions recurses with
        // leafPath=["inner", "filmId"]; ArgCallEmitter emits a two-level instanceof-Map
        // ternary chain to reach env.getArgument("filter").get("inner").get("filmId").
        Map<String, Object> data = execute(
            "{ filmsNestedInput(filter: {inner: {filmId: \"3\"}}) { filmId } }");
        List<Map<String, Object>> films = (List<Map<String, Object>>) data.get("filmsNestedInput");
        assertThat(films).extracting(f -> f.get("filmId")).containsExactly(3);
    }

    @Test
    @SuppressWarnings("unchecked")
    void inputFieldCondition_plainInput_outerOverride_preservesInnerExplicitMethod() {
        // alf production shape: outer @condition(override:true) composed with a plain
        // (non-@table) input whose field carries @condition. Same divergence-pinning
        // assertion as the @table case: (film_id >= 2) AND (film_id = 1) → no rows.
        Map<String, Object> data = execute(
            "{ filmsOuterOverridePlainInput(filter: {filmId: \"1\"}) { filmId } }");
        List<Map<String, Object>> films = (List<Map<String, Object>>) data.get("filmsOuterOverridePlainInput");
        assertThat(films).isEmpty();
    }

    // ===== Implicit column conditions for @table input types =====

    @Test
    @SuppressWarnings("unchecked")
    void implicitInputCondition_filtersByColumn() {
        // FilmImplicitInput.filmId has no @condition — the implicit column-equality predicate
        // (film.FILM_ID = ?) is emitted by walkInputFieldConditions. filmId: "3" → one row.
        Map<String, Object> data = execute(
            "{ filmsWithImplicitInput(filter: {filmId: \"3\"}) { filmId } }");
        List<Map<String, Object>> films = (List<Map<String, Object>>) data.get("filmsWithImplicitInput");
        assertThat(films).extracting(f -> f.get("filmId")).containsExactly(3);
    }

    @Test
    @SuppressWarnings("unchecked")
    void implicitInputCondition_nullField_omitsPredicate_returnsAll() {
        // filmId: null → DSL.val(null, table.FILM_ID) null-guard skips the predicate →
        // no WHERE filter → all 5 films returned. "Absent means unconstrained" semantics.
        Map<String, Object> data = execute(
            "{ filmsWithImplicitInput(filter: {filmId: null}) { filmId } }");
        List<Map<String, Object>> films = (List<Map<String, Object>>) data.get("filmsWithImplicitInput");
        assertThat(films).hasSize(5);
    }

    @Test
    @SuppressWarnings("unchecked")
    void implicitInputCondition_parentFieldOverride_suppressesImplicit() {
        // filmsWithImplicitInputOuterOverride has @condition(override:true) → enclosingOverride=true
        // → implicit predicate for filmId is suppressed. Only outerOverrideMethod fires
        // (film_id >= 2). Query with filmId:"1" would return 0 rows if implicit were active
        // ((film_id >= 2) AND (film_id = 1)), but with suppression it returns 4 rows (2..5).
        Map<String, Object> data = execute(
            "{ filmsWithImplicitInputOuterOverride(filter: {filmId: \"1\"}) { filmId } }");
        List<Map<String, Object>> films = (List<Map<String, Object>>) data.get("filmsWithImplicitInputOuterOverride");
        assertThat(films).extracting(f -> f.get("filmId"))
            .containsExactlyInAnyOrder(2, 3, 4, 5);
    }

    @Test
    @SuppressWarnings("unchecked")
    void implicitInputCondition_twoFields_andsProperly() {
        // Two un-annotated fields: both emit implicit predicates, AND-ed in the GCF.
        // film 3 is ADAPTATION HOLES → match; filmId=3 + title="ACE GOLDFINGER" → no rows
        // (AND, not OR: the two predicates must agree).
        Map<String, Object> both = execute(
            "{ filmsWithMultiImplicit(filter: {filmId: \"3\", title: \"ADAPTATION HOLES\"}) { filmId } }");
        assertThat((List<Map<String, Object>>) both.get("filmsWithMultiImplicit"))
            .extracting(f -> f.get("filmId")).containsExactly(3);

        Map<String, Object> mismatch = execute(
            "{ filmsWithMultiImplicit(filter: {filmId: \"3\", title: \"ACE GOLDFINGER\"}) { filmId } }");
        assertThat((List<Map<String, Object>>) mismatch.get("filmsWithMultiImplicit")).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void implicitInputCondition_nestedTwoLevel_firesAtLeaf() {
        // NestedImplicitInput @table wraps InnerImplicitInput (plain) whose filmId has no
        // @condition. Implicit predicate binds to the outer @table's film_id column; the
        // NestedInputField path ["inner", "filmId"] drives call-site extraction.
        Map<String, Object> data = execute(
            "{ filmsWithNestedImplicit(filter: {inner: {filmId: \"2\"}}) { filmId } }");
        assertThat((List<Map<String, Object>>) data.get("filmsWithNestedImplicit"))
            .extracting(f -> f.get("filmId")).containsExactly(2);
    }

    @SuppressWarnings("unchecked")
    @Test
    void splitLookupTableField_nestedUnderNestingField_batchesPerOuterParent() {
        // FilmInfo.castByKey exercises the SplitLookupTableField arm under NestingField:
        // classifier, emitter, and wiring all route via BatchKeyField uniformly, so
        // @splitQuery + @lookupKey at nested depth takes the same path as the top-level
        // Film.actorsBySplitLookup (:732 above).
        // actor_id: [1, 2] → film 1 gets {1, 2}; film 2 gets {1}.
        QUERY_COUNT.set(0);
        Map<String, Object> data = execute(
            "{ filmById(film_id: [\"1\", \"2\"]) { filmId info { castByKey(actor_id: [1, 2]) { actorId } } } }");
        assertThat(QUERY_COUNT.get()).isEqualTo(2);
        List<Map<String, Object>> films = (List<Map<String, Object>>) data.get("filmById");
        var byId = films.stream().collect(java.util.stream.Collectors.toMap(
            f -> (Integer) f.get("filmId"),
            f -> (List<Map<String, Object>>) ((Map<String, Object>) f.get("info")).get("castByKey")));
        assertThat(byId.get(1)).extracting(a -> a.get("actorId")).containsExactlyInAnyOrder(1, 2);
        assertThat(byId.get(2)).extracting(a -> a.get("actorId")).containsExactly(1);
    }

    // ===== stores — directive-driven @asConnection (emit-time synthesis) =====

    @Test
    void stores_synthesisedConnection_returnsEdgesAndPageInfo() {
        // The QueryStoresConnection / QueryStoresEdge types are synthesised at emit time
        // (not hand-written). This test proves SDL → classifier → synthesis → programmatic
        // schema → fetcher runtime compose end-to-end.
        // The test DB has 2 stores; request first:1 so hasNextPage is true.
        Map<String, Object> data = execute(
            "{ stores(first: 1) { edges { cursor node { storeId } } pageInfo { hasNextPage hasPreviousPage } } }");
        var conn = assertThat(data).extractingByKey("stores", as(MAP));
        var firstEdge = conn.extractingByKey("edges", as(LIST))
            .hasSize(1)
            .first(as(MAP));
        firstEdge.containsKey("cursor");
        firstEdge.extractingByKey("node", as(MAP)).extractingByKey("storeId").isNotNull();
        conn.extractingByKey("pageInfo", as(MAP))
            .containsEntry("hasNextPage", true)
            .containsEntry("hasPreviousPage", false);
    }

    // ===== Query.node — Relay Global Object Identification =====

    @Test
    void node_customerById_roundTripsThroughDispatcher() {
        String id = no.sikt.graphitron.generated.util.NodeIdEncoder.encode("Customer", 1);
        Map<String, Object> data = execute(
            "{ node(id: \"" + id + "\") { __typename ... on Customer { firstName lastName } } }");
        assertThat(data).extractingByKey("node", as(MAP))
            .isNotNull()
            .containsEntry("__typename", "Customer")
            .containsEntry("firstName", "Mary")
            .containsEntry("lastName", "Smith");
    }

    @Test
    void node_filmById_dispatchesByTypeIdPrefix() {
        // Different NodeType (Film) — verifies the dispatcher routes by typeId prefix.
        String id = no.sikt.graphitron.generated.util.NodeIdEncoder.encode("Film", 1);
        Map<String, Object> data = execute(
            "{ node(id: \"" + id + "\") { __typename ... on Film { title } } }");
        assertThat(data).extractingByKey("node", as(MAP))
            .isNotNull()
            .containsEntry("__typename", "Film")
            .containsEntry("title", "ACADEMY DINOSAUR");
    }

    @Test
    void node_unknownTypeId_returnsNull() {
        // typeId prefix no NodeType claims → null (Relay spec: "if no such object exists, the
        // field returns null"). Dispatcher must not raise.
        String id = no.sikt.graphitron.generated.util.NodeIdEncoder.encode("NotARegisteredType", "1");
        Map<String, Object> data = execute("{ node(id: \"" + id + "\") { __typename } }");
        assertThat(data.get("node")).isNull();
    }

    @Test
    void node_garbageInput_returnsNull() {
        // Malformed base64 → null (opacity: decoding errors are not exposed to clients).
        Map<String, Object> data = execute("{ node(id: \"not-a-valid-base64-id\") { __typename } }");
        assertThat(data.get("node")).isNull();
    }

    @Test
    void node_referenceField_encodesFromFkMirrorThenRoundTrips() {
        // Customer.addressNodeId references Address.id via the customer_address_id_fkey FK
        // (FK-mirror collapse: customer.address_id mirrors address.address_id). The dispatcher
        // encodes the FK column on the parent and the resulting opaque ID round-trips through
        // Query.node back to the matching Address row — without ever loading the Address table
        // during the projection.
        Map<String, Object> data = execute(
            "{ customers { customerId addressNodeId } }");
        String addressNodeId = assertThat(data).extractingByKey("customers", as(LIST))
            .isNotEmpty()
            .first(as(MAP))
            .extractingByKey("addressNodeId", as(STRING))
            .isNotNull()
            .actual();

        Map<String, Object> nodeData = execute(
            "{ node(id: \"" + addressNodeId + "\") { __typename ... on Address { addressId district } } }");
        var node = assertThat(nodeData).extractingByKey("node", as(MAP)).isNotNull();
        node.containsEntry("__typename", "Address");
        node.extractingByKey("addressId").isNotNull();
        node.extractingByKey("district").isNotNull();
    }

    @Test
    void node_validPrefixNoSuchRow_returnsNull() {
        // Registered typeId but the row doesn't exist — null, not error.
        String id = no.sikt.graphitron.generated.util.NodeIdEncoder.encode("Customer", 999999);
        Map<String, Object> data = execute("{ node(id: \"" + id + "\") { __typename } }");
        assertThat(data.get("node")).isNull();
    }

    // ===== nodeId durability / opacity invariants =====
    //
    // The wire format must stay frozen across releases: an ID issued by release N must still
    // resolve under release N+k. These tests pin specific encoded forms and assert the
    // dispatcher can decode them, so any change to NodeIdEncoder (encoding scheme, escape
    // policy, column-order normalisation) breaks loudly here before it can silently
    // invalidate IDs already in circulation.

    @Test
    void node_pinnedSingleKeyId_stillResolves() {
        // base64-url-no-pad of UTF-8 "Customer:1". If the encoder ever switches to standard
        // base64, padding, a different separator, or any other format change, this exact
        // string stops resolving and the test fails. Treat the literal as a release contract.
        String pinnedId = "Q3VzdG9tZXI6MQ";
        assertThat(no.sikt.graphitron.generated.util.NodeIdEncoder.encode("Customer", 1))
            .isEqualTo(pinnedId);

        Map<String, Object> data = execute(
            "{ node(id: \"" + pinnedId + "\") { __typename ... on Customer { firstName lastName } } }");
        assertThat(data).extractingByKey("node", as(MAP))
            .isNotNull()
            .containsEntry("__typename", "Customer")
            .containsEntry("firstName", "Mary")
            .containsEntry("lastName", "Smith");
    }

    @Test
    void node_compositeKeyEncoding_isFrozen() {
        // The execution-tier schema only exposes single-key NodeTypes, but the encoder is
        // used for composite keys too (see NodeIdPipelineTest's `bar` fixture). Pin the
        // composite wire format directly so a "tidy up the CSV" refactor that, say, switches
        // the separator from ',' to '|' or drops the comma escape rule is caught here.
        String pinned = "Rm9vOjEsMg";  // base64-url-no-pad of "Foo:1,2"
        assertThat(no.sikt.graphitron.generated.util.NodeIdEncoder.encode("Foo", "1", "2"))
            .isEqualTo(pinned);
    }

    @Test
    void node_keyColumnsOrder_isLoadBearing() {
        // Composite keys encode positionally: encoder and decoder must agree on column order.
        // If anyone ever "normalises" by sorting args alphabetically, every previously-issued
        // composite ID stops matching. Asserting (1,2) ≠ (2,1) keeps the positional contract
        // honest. See plan-nodeid-directives.md "KjerneJooqGenerator contract".
        String fwd = no.sikt.graphitron.generated.util.NodeIdEncoder.encode("Foo", "1", "2");
        String rev = no.sikt.graphitron.generated.util.NodeIdEncoder.encode("Foo", "2", "1");
        assertThat(fwd).isNotEqualTo(rev);
    }

    // ===== Query.nodes — Relay batch dispatch =====

    @Test
    void nodes_emptyIds_returnsEmptyList() {
        Map<String, Object> data = execute("{ nodes(ids: []) { __typename } }");
        assertThat((List<?>) data.get("nodes")).isEmpty();
    }

    @Test
    void nodes_mixedTypeIds_resolvesEachAndPreservesOrder() {
        // Mixing typeIds in one call exercises the per-typeId hasIds branch in rowsNodes:
        // each typeId becomes one query, results scatter back to original positions.
        String c1 = no.sikt.graphitron.generated.util.NodeIdEncoder.encode("Customer", 1);
        String f1 = no.sikt.graphitron.generated.util.NodeIdEncoder.encode("Film", 1);
        String c2 = no.sikt.graphitron.generated.util.NodeIdEncoder.encode("Customer", 2);
        Map<String, Object> data = execute(
            "{ nodes(ids: [\"" + c1 + "\", \"" + f1 + "\", \"" + c2 + "\"]) {"
            + " __typename ... on Customer { firstName } ... on Film { title } } }");
        var nodes = assertThat(data).extractingByKey("nodes", as(LIST)).hasSize(3);
        nodes.element(0, as(MAP)).containsEntry("__typename", "Customer").containsEntry("firstName", "Mary");
        nodes.element(1, as(MAP)).containsEntry("__typename", "Film").containsEntry("title", "ACADEMY DINOSAUR");
        nodes.element(2, as(MAP)).containsEntry("__typename", "Customer").containsEntry("firstName", "Patricia");
    }

    @Test
    void nodes_garbageId_returnsNullEntry() {
        // Malformed base64 — null at that position, others still resolve.
        String c1 = no.sikt.graphitron.generated.util.NodeIdEncoder.encode("Customer", 1);
        Map<String, Object> data = execute(
            "{ nodes(ids: [\"" + c1 + "\", \"not-a-valid-base64-id\"]) {"
            + " __typename ... on Customer { firstName } } }");
        var nodes = assertThat(data).extractingByKey("nodes", as(LIST)).hasSize(2);
        nodes.element(0, as(MAP)).containsEntry("firstName", "Mary");
        nodes.element(1).isNull();
    }

    @Test
    void nodes_unknownTypeId_returnsNullEntry() {
        // typeId prefix no NodeType claims — null at that position (Relay spec).
        String unknown = no.sikt.graphitron.generated.util.NodeIdEncoder.encode("NotARegisteredType", "1");
        String c1 = no.sikt.graphitron.generated.util.NodeIdEncoder.encode("Customer", 1);
        Map<String, Object> data = execute(
            "{ nodes(ids: [\"" + unknown + "\", \"" + c1 + "\"]) {"
            + " __typename ... on Customer { firstName } } }");
        var nodes = assertThat(data).extractingByKey("nodes", as(LIST)).hasSize(2);
        nodes.element(0).isNull();
        nodes.element(1, as(MAP)).containsEntry("firstName", "Mary");
    }

    @Test
    void nodes_validPrefixNoSuchRow_returnsNullEntry() {
        // Registered typeId but the row doesn't exist — null at that position.
        String missing = no.sikt.graphitron.generated.util.NodeIdEncoder.encode("Customer", 999999);
        String c1 = no.sikt.graphitron.generated.util.NodeIdEncoder.encode("Customer", 1);
        Map<String, Object> data = execute(
            "{ nodes(ids: [\"" + c1 + "\", \"" + missing + "\"]) {"
            + " __typename ... on Customer { firstName } } }");
        var nodes = assertThat(data).extractingByKey("nodes", as(LIST)).hasSize(2);
        nodes.element(0, as(MAP)).containsEntry("firstName", "Mary");
        nodes.element(1).isNull();
    }

    @Test
    void nodes_perTypeIdBatch_emitsValuesJoinOrderByIdxShape() {
        // R50 phase (f-E): the per-typeId batch emission is asserted to be the lookup shape
        // (VALUES + JOIN + ORDER BY idx) rather than the legacy `WHERE row-IN`. Catches
        // regressions where EntityFetcherDispatch falls back to the old form. See
        // lift-nodeid-out-of-model.md "Query.nodes folds onto the lookup pipeline".
        String c1 = no.sikt.graphitron.generated.util.NodeIdEncoder.encode("Customer", 1);
        String c2 = no.sikt.graphitron.generated.util.NodeIdEncoder.encode("Customer", 2);
        String c3 = no.sikt.graphitron.generated.util.NodeIdEncoder.encode("Customer", 3);
        SQL_LOG.clear();
        execute("{ nodes(ids: [\"" + c1 + "\", \"" + c2 + "\", \"" + c3 + "\"]) {"
            + " __typename ... on Customer { firstName } } }");
        // SQL_LOG entries are already lowercased by the ExecuteListener at line ~67. jOOQ
        // renders "public"."customer" with quoted identifiers; match on `customer` to span
        // both quoted and unquoted forms.
        assertThat(SQL_LOG)
            .as("per-typeId Query.nodes batch must use the lookup-shape (VALUES + JOIN + ORDER BY idx)")
            .anyMatch(s -> s.contains("\"customer\"")
                && s.contains("values (")
                && s.contains("join ")
                && s.contains("order by"));
        // Negative pin: no statement falls back to the legacy WHERE row-IN keyset filter.
        assertThat(SQL_LOG)
            .as("no Query.nodes statement should regress to the legacy WHERE row-IN form")
            .noneMatch(s -> s.contains("\"customer\"") && s.contains("\"customer_id\" in ("));
    }

    @Test
    void nodes_paddedBase64Id_canonicalizesAndResolves() {
        // Regression test for the canonicalisation fix: encoder emits no-padding URL base64,
        // but the decoder accepts padded input where the total length is 4-byte-aligned.
        // "Customer:1" (10 bytes) encodes to 14 chars unpadded; padding to 16 chars adds "==".
        // Without canonicalize, rowsNodes keys positions by the literal padded id but encode()
        // produces the no-padding form, so the result row would never match the requested
        // position and result[i] would stay null. Pre-fix, nodes(ids:) silently disagreed with
        // node(id:) on inputs the decoder accepts.
        String canonical = no.sikt.graphitron.generated.util.NodeIdEncoder.encode("Customer", 1);
        String padded = canonical + "==";
        // Sanity: the padded form must round-trip via node(id:) too — pre-existing decoder
        // leniency. If this assertion fails, the encoder changed and the test premise no
        // longer holds.
        Map<String, Object> nodeData = execute(
            "{ node(id: \"" + padded + "\") { __typename ... on Customer { firstName } } }");
        assertThat(nodeData).extractingByKey("node", as(MAP))
            .containsEntry("firstName", "Mary");

        Map<String, Object> data = execute(
            "{ nodes(ids: [\"" + padded + "\"]) {"
            + " __typename ... on Customer { firstName } } }");
        assertThat(data).extractingByKey("nodes", as(LIST))
            .hasSize(1)
            .first(as(MAP))
            .as("padded id must resolve under nodes(ids:)")
            .isNotNull()
            .containsEntry("__typename", "Customer")
            .containsEntry("firstName", "Mary");
    }

    @Test
    void nodes_duplicateIds_recordRepeatsAtAllPositions() {
        // ids = [c1, c2, c1] — DataLoader caching dedups into the batch so the SQL hits the
        // row once; the result fans back to all three positions. Asserts both shape (length 3,
        // matching records) and that the duplicate slot is the same record value.
        String c1 = no.sikt.graphitron.generated.util.NodeIdEncoder.encode("Customer", 1);
        String c2 = no.sikt.graphitron.generated.util.NodeIdEncoder.encode("Customer", 2);
        Map<String, Object> data = execute(
            "{ nodes(ids: [\"" + c1 + "\", \"" + c2 + "\", \"" + c1 + "\"]) {"
            + " __typename ... on Customer { firstName lastName } } }");
        var nodes = assertThat(data).extractingByKey("nodes", as(LIST)).hasSize(3);
        nodes.element(0, as(MAP)).containsEntry("firstName", "Mary");
        nodes.element(1, as(MAP)).containsEntry("firstName", "Patricia");
        nodes.element(2, as(MAP)).containsEntry("firstName", "Mary");
    }

    @Test
    void nodes_idAndOtherFieldsTogether_resolvesBothFromSingleProjection() {
        // Locks down the duplicate-projection invariant: when the GraphQL selection includes
        // `id`, $fields adds the nodeKey columns, and rowsNodes also needs them for the
        // result-scatter encode. The generator dedups so each key column is projected once;
        // both the generated `id` resolver (NodeIdEncoder.encode(typeId, r.get(t.<col>))) and
        // the encode call inside rowsNodes read the same column. Asserting the encoded id
        // equals the canonical encode of the seeded customer_id verifies both reads agree.
        String c1 = no.sikt.graphitron.generated.util.NodeIdEncoder.encode("Customer", 1);
        Map<String, Object> data = execute(
            "{ nodes(ids: [\"" + c1 + "\"]) {"
            + " __typename id ... on Customer { firstName customerId } } }");
        assertThat(data).extractingByKey("nodes", as(LIST))
            .hasSize(1)
            .first(as(MAP))
            .containsEntry("__typename", "Customer")
            .containsEntry("id", c1)
            .containsEntry("firstName", "Mary")
            .containsEntry("customerId", 1);
    }

    @Test
    void nodes_singleTenant_oneSqlQueryPerTypeId() {
        // 3 customer ids + 2 film ids in the default empty-tenant loader: expect exactly 2
        // SQL queries (one Customer batch + one Film batch). N+1 / per-id dispatch would be
        // 5; cross-typeId merge would be 1 against an impossible UNION query.
        String c1 = no.sikt.graphitron.generated.util.NodeIdEncoder.encode("Customer", 1);
        String c2 = no.sikt.graphitron.generated.util.NodeIdEncoder.encode("Customer", 2);
        String c3 = no.sikt.graphitron.generated.util.NodeIdEncoder.encode("Customer", 3);
        String f1 = no.sikt.graphitron.generated.util.NodeIdEncoder.encode("Film", 1);
        String f2 = no.sikt.graphitron.generated.util.NodeIdEncoder.encode("Film", 2);

        QUERY_COUNT.set(0);
        execute("{ nodes(ids: [\"" + c1 + "\", \"" + f1 + "\", \"" + c2 + "\", \"" + f2 + "\", \"" + c3 + "\"]) { __typename } }");
        assertThat(QUERY_COUNT.get()).isEqualTo(2);
    }

    @Test
    void nodes_perTenantPartition_separateBatchPerTenant() {
        // Custom GraphitronContext maps each id to a tenant. 4 customer ids, 2 per tenant.
        // Expect 2 SQL queries (one Customer batch per tenant). The cbbc103 bug (resolve
        // tenant against the outer ids[] argument) would put all four into one loader → 1
        // query. Broken batching would be 4.
        String c1 = no.sikt.graphitron.generated.util.NodeIdEncoder.encode("Customer", 1);
        String c2 = no.sikt.graphitron.generated.util.NodeIdEncoder.encode("Customer", 2);
        String c3 = no.sikt.graphitron.generated.util.NodeIdEncoder.encode("Customer", 3);
        String c4 = no.sikt.graphitron.generated.util.NodeIdEncoder.encode("Customer", 4);
        Map<String, String> idToTenant = Map.of(
            c1, "tenantA", c3, "tenantA",
            c2, "tenantB", c4, "tenantB");

        var perIdTenantContext = new GraphitronContext() {
            @Override public DSLContext getDslContext(DataFetchingEnvironment env) { return dsl; }
            @Override public <T> T getContextArgument(DataFetchingEnvironment env, String name) { return null; }
            @Override public String getTenantId(DataFetchingEnvironment env) {
                String id = env.getArgument("id");
                return id == null ? "" : idToTenant.getOrDefault(id, "");
            }
        };

        QUERY_COUNT.set(0);
        executeWithContext(
            "{ nodes(ids: [\"" + c1 + "\", \"" + c2 + "\", \"" + c3 + "\", \"" + c4 + "\"]) {"
            + " __typename ... on Customer { firstName } } }",
            perIdTenantContext);
        assertThat(QUERY_COUNT.get())
            .as("two tenants, one Customer batch each")
            .isEqualTo(2);
    }

    @Test
    void stores_synthesisedConnection_cursorRoundTrip() {
        // Fetch page 1 cursor, use it for page 2, assert hasNextPage is false (2 stores total).
        Map<String, Object> page1 = execute(
            "{ stores(first: 1) { pageInfo { endCursor } } }");
        String endCursor = assertThat(page1).extractingByKey("stores", as(MAP))
            .extractingByKey("pageInfo", as(MAP))
            .extractingByKey("endCursor", as(STRING))
            .isNotNull()
            .actual();

        Map<String, Object> page2 = execute(
            "{ stores(first: 1, after: \"" + endCursor + "\") { nodes { storeId } pageInfo { hasNextPage } } }");
        var conn2 = assertThat(page2).extractingByKey("stores", as(MAP));
        conn2.extractingByKey("nodes", as(LIST)).hasSize(1);
        conn2.extractingByKey("pageInfo", as(MAP)).containsEntry("hasNextPage", false);
    }

    // ===== Service / tableMethod root fetchers =====

    @Test
    @SuppressWarnings("unchecked")
    void queryTableMethod_popularFilms_filtersAndProjectsSelectedColumns() {
        // SampleQueryService.popularFilms returns filmTable.where(RENTAL_RATE >= minRentalRate).
        // The generated jOOQ Film overrides where() to return Film (not Table<R>), so the
        // filtered derived table preserves the specific type required by Invariants §3 — no
        // downcast needed when the framework projects via FilmType.$fields(...). Of the 5
        // seeded films, only ACE GOLDFINGER (rental_rate=4.99) clears the >= 3.0 threshold.
        QUERY_COUNT.set(0);
        Map<String, Object> data = execute("{ popularFilms(minRentalRate: 3.0) { title } }");
        List<Map<String, Object>> films = (List<Map<String, Object>>) data.get("popularFilms");
        assertThat(films).extracting(f -> f.get("title"))
            .containsExactly("ACE GOLDFINGER");
        // tableMethod path runs exactly one SQL query (the projection SELECT over the
        // developer-returned filtered Table).
        assertThat(QUERY_COUNT.get()).isEqualTo(1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void queryServiceTable_filmsByService_returnsRecordsThatFlowThroughColumnFetchers() {
        // SampleQueryService.filmsByService runs its own SELECT and returns Result<FilmRecord>.
        // The framework does no projection; graphql-java's column fetchers walk the records via
        // field-name lookup. Asserting on title / rentalRate confirms the record traversal path works.
        Map<String, Object> data = execute(
            "{ filmsByService(ids: [1, 2]) { filmId title rentalRate } }");
        List<Map<String, Object>> films = (List<Map<String, Object>>) data.get("filmsByService");
        assertThat(films).extracting(f -> f.get("title"))
            .containsExactly("ACADEMY DINOSAUR", "ACE GOLDFINGER");
        assertThat(films).extracting(f -> f.get("filmId"))
            .containsExactly(1, 2);
    }

    @Test
    void queryServiceRecord_filmCount_returnsScalar() {
        // SampleQueryService.filmCount returns Integer; graphql-java coerces to Int!.
        // Five films are seeded by init.sql.
        Map<String, Object> data = execute("{ filmCount }");
        assertThat(data.get("filmCount")).isEqualTo(5);
    }

    @Test
    @SuppressWarnings("unchecked")
    void queryServiceTable_filmsByServiceRenamed_overrideBindsArgToDifferentlyNamedJavaParam() {
        // R53: GraphQL arg `ids` is bound to the Java parameter `filmIds` via argMapping
        // ("filmIds: ids" on the @service directive). Generated fetcher must read
        // env.getArgument("ids") (the GraphQL key) and pass it to the service method's `filmIds`
        // parameter — proves the graphqlArgName / Java-identifier split wires through end-to-end.
        Map<String, Object> data = execute(
            "{ filmsByServiceRenamed(ids: [1, 2]) { filmId title } }");
        List<Map<String, Object>> films = (List<Map<String, Object>>) data.get("filmsByServiceRenamed");
        assertThat(films).extracting(f -> f.get("filmId")).containsExactly(1, 2);
    }

    // ===== TableInterfaceType (Track A) =====

    @Test
    @SuppressWarnings("unchecked")
    void allContent_returnsAllRowsWithTypeName() {
        // allContent selects from the shared 'content' table and routes each row to FilmContent
        // or ShortContent via the TypeResolver. init.sql seeds 2 FILM rows and 2 SHORT rows.
        Map<String, Object> data = execute(
            "{ allContent { __typename contentId title } }");
        List<Map<String, Object>> items = (List<Map<String, Object>>) data.get("allContent");
        assertThat(items).hasSize(4);
        assertThat(items).extracting(i -> i.get("__typename"))
            .containsExactlyInAnyOrder("FilmContent", "FilmContent", "ShortContent", "ShortContent");
    }

    @Test
    @SuppressWarnings("unchecked")
    void allContent_typeRouting_filmContentHasLength() {
        // FilmContent-typed rows expose the 'length' field; ShortContent rows do not.
        // This exercises inline fragment dispatch and confirms that the TypeResolver
        // correctly routes FILM rows to FilmContent.
        Map<String, Object> data = execute("""
            { allContent {
                __typename
                ... on FilmContent { length }
            } }
            """);
        List<Map<String, Object>> items = (List<Map<String, Object>>) data.get("allContent");
        var filmItems = items.stream()
            .filter(i -> "FilmContent".equals(i.get("__typename")))
            .toList();
        assertThat(filmItems).hasSize(2);
        assertThat(filmItems).allSatisfy(i ->
            assertThat(i.get("length")).as("FilmContent.length").isNotNull());
    }

    @Test
    @SuppressWarnings("unchecked")
    void allContent_onlyReturnsKnownDiscriminatorValues() {
        // The generated WHERE IN ('FILM','SHORT') filter must exclude any rows with unknown
        // discriminator values that cannot be routed by the TypeResolver. If the filter is
        // missing, those rows would cause a null type resolution and an opaque runtime error.
        // init.sql seeds only FILM and SHORT rows so this test confirms no mystery rows appear.
        Map<String, Object> data = execute("{ allContent { __typename } }");
        List<Map<String, Object>> items = (List<Map<String, Object>>) data.get("allContent");
        assertThat(items).extracting(i -> i.get("__typename"))
            .allSatisfy(t -> assertThat(t).isIn("FilmContent", "ShortContent"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void filmContent_singleValue_routesToFilmContent() {
        // Film #1 (ACADEMY DINOSAUR) has a content entry with CONTENT_TYPE='FILM' and film_id=1.
        // filmContent is a ChildField.TableInterfaceField on Film, resolved via the FK
        // content.film_id → film.film_id. The TypeResolver must route it to FilmContent.
        Map<String, Object> data = execute(
            "{ filmById(film_id: [\"1\"]) { filmContent { __typename contentId title } } }");
        List<Map<String, Object>> films = (List<Map<String, Object>>) data.get("filmById");
        assertThat(films).hasSize(1);
        var film = films.get(0);
        var content = (Map<String, Object>) film.get("filmContent");
        assertThat(content).isNotNull();
        assertThat(content.get("__typename")).isEqualTo("FilmContent");
        assertThat(content.get("title")).isEqualTo("ACADEMY DINOSAUR (extended)");
    }

    @Test
    @SuppressWarnings("unchecked")
    void filmContent_filmWithNoContent_returnsNull() {
        // Film #3 has no linked content entry; the FK join produces no match so the
        // single-value fetcher must return null (not an error).
        Map<String, Object> data = execute(
            "{ filmById(film_id: [\"3\"]) { filmContent { __typename } } }");
        List<Map<String, Object>> films = (List<Map<String, Object>>) data.get("filmById");
        assertThat(films).hasSize(1);
        var content = films.get(0).get("filmContent");
        assertThat(content).isNull();
    }

    // ===== TableInterfaceType cross-table participant fields =====
    //
    // FilmContent.rating lives on the joined film table (FK content.film_id → film.film_id).
    // ShortContent.description lives on the content table itself but is populated only on
    // SHORT rows. The interface fetcher emits a discriminator-gated LEFT JOIN per cross-table
    // participant field; the per-field DataFetcher reads the projected value back from the
    // result Record by alias.

    @Test
    @SuppressWarnings("unchecked")
    void allContent_crossTableField_joinsFilmAndReturnsRatingForFilmContent() {
        // FilmContent.rating reaches into the joined film row via content_film_id_fkey. The
        // film rows seeded in init.sql carry ratings, so requesting the inline-fragment field
        // must return a non-null value for every FilmContent and a null for every ShortContent
        // (whose discriminator-gated JOIN never matches).
        Map<String, Object> data = execute("""
            { allContent {
                __typename
                ... on FilmContent { rating }
            } }
            """);
        List<Map<String, Object>> items = (List<Map<String, Object>>) data.get("allContent");
        var filmItems = items.stream()
            .filter(i -> "FilmContent".equals(i.get("__typename")))
            .toList();
        assertThat(filmItems).hasSize(2);
        assertThat(filmItems).allSatisfy(i ->
            assertThat(i.get("rating")).as("FilmContent.rating sourced from joined film row").isNotNull());
    }

    @Test
    @SuppressWarnings("unchecked")
    void allContent_crossTableField_leftJoinOmittedWhenNotRequested() {
        // When the cross-table field is not selected, the conditional LEFT JOIN must be skipped
        // so no over-fetching occurs. The SQL_LOG capture lets us assert no `left join film`
        // appears when only same-table columns are projected.
        SQL_LOG.clear();
        execute("{ allContent { __typename contentId title } }");
        var contentSql = SQL_LOG.stream()
            .filter(s -> s.contains("from \"content\"") || s.contains("from content"))
            .toList();
        assertThat(contentSql)
            .as("content fetcher SQL must not LEFT JOIN film when no cross-table field is selected")
            .allSatisfy(sql -> assertThat(sql).doesNotContain("left join \"film\"")
                                              .doesNotContain("left join film"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void allContent_crossTableField_leftJoinPresentWhenRequested() {
        // Inverse of the previous test: requesting the cross-table field must drive the LEFT JOIN.
        // Relaxed to match either {LEFT JOIN, LEFT OUTER JOIN} (jOOQ for Postgres typically
        // renders the latter) and either quoted or unquoted table names.
        SQL_LOG.clear();
        execute("""
            { allContent {
                __typename
                ... on FilmContent { rating }
            } }
            """);
        var joinedFilm = SQL_LOG.stream()
            .anyMatch(s -> s.contains("join") && s.contains("film") && s.contains("content"));
        assertThat(joinedFilm)
            .as("content fetcher SQL must JOIN film when FilmContent.rating is selected; captured: " + SQL_LOG)
            .isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void allContent_filmContentOnly_isolatesLengthFromShortDescription() {
        // FilmContent.length lives on the content table; ShortContent.description also lives on
        // the content table but in a different column. Per-participant projection (FilmContent.$fields
        // vs ShortContent.$fields) plus the type-routing TypeResolver guarantee FILM rows expose
        // length only and SHORT rows expose description only — even though both sit on the same row.
        Map<String, Object> data = execute("""
            { allContent {
                __typename
                ... on FilmContent { length }
                ... on ShortContent { description }
            } }
            """);
        List<Map<String, Object>> items = (List<Map<String, Object>>) data.get("allContent");
        var filmItems = items.stream()
            .filter(i -> "FilmContent".equals(i.get("__typename")))
            .toList();
        var shortItems = items.stream()
            .filter(i -> "ShortContent".equals(i.get("__typename")))
            .toList();
        assertThat(filmItems).hasSize(2);
        assertThat(shortItems).hasSize(2);
        assertThat(filmItems).allSatisfy(i ->
            assertThat(i.get("length")).as("FilmContent.length populated").isNotNull());
        assertThat(filmItems).allSatisfy(i ->
            assertThat(i.containsKey("description"))
                .as("FilmContent rows do not surface ShortContent.description").isFalse());
        assertThat(shortItems).allSatisfy(i ->
            assertThat(i.get("description")).as("ShortContent.description populated").isNotNull());
        assertThat(shortItems).allSatisfy(i ->
            assertThat(i.containsKey("length"))
                .as("ShortContent rows do not surface FilmContent.length").isFalse());
    }

    @Test
    @SuppressWarnings("unchecked")
    void allContent_allParticipantFieldsTogether_routePerType() {
        // Triple-axis projection: FilmContent.length (same-table), ShortContent.description
        // (same-table, different column), and FilmContent.rating (cross-table via LEFT JOIN to
        // film). Each row carries the field appropriate to its type and not the others.
        Map<String, Object> data = execute("""
            { allContent {
                __typename
                ... on FilmContent { length rating }
                ... on ShortContent { description }
            } }
            """);
        List<Map<String, Object>> items = (List<Map<String, Object>>) data.get("allContent");
        var filmItems = items.stream()
            .filter(i -> "FilmContent".equals(i.get("__typename")))
            .toList();
        var shortItems = items.stream()
            .filter(i -> "ShortContent".equals(i.get("__typename")))
            .toList();
        assertThat(filmItems).hasSize(2);
        assertThat(shortItems).hasSize(2);
        assertThat(filmItems).allSatisfy(i -> {
            assertThat(i.get("length")).isNotNull();
            assertThat(i.get("rating")).isNotNull();
        });
        assertThat(shortItems).allSatisfy(i ->
            assertThat(i.get("description")).isNotNull());
    }

    // ===== Multi-table polymorphic InterfaceType / UnionType (Track B2) =====
    //
    // Searchable is implemented by Film (table: film) and Actor (table: actor) on
    // heterogeneous tables. The generated fetcher is two-stage: stage 1 is a narrow
    // UNION ALL projecting (typename, pk0, sort) per branch; stage 2 dispatches per
    // typename via ValuesJoinRowBuilder. Records carry a synthetic __typename column
    // so the schema-class TypeResolver routes each row back to its concrete type.

    @Test
    @SuppressWarnings("unchecked")
    void search_returnsAllParticipantTypes() {
        // init.sql seeds 5 films + 3 actors. The search fetcher returns 5 Film rows and 3
        // Actor rows under the Searchable interface contract; __typename resolves per row.
        Map<String, Object> data = execute("{ search { __typename name } }");
        List<Map<String, Object>> items = (List<Map<String, Object>>) data.get("search");
        assertThat(items).hasSize(8);
        long films = items.stream().filter(i -> "Film".equals(i.get("__typename"))).count();
        long actors = items.stream().filter(i -> "Actor".equals(i.get("__typename"))).count();
        assertThat(films).isEqualTo(5);
        assertThat(actors).isEqualTo(3);
    }

    @Test
    @SuppressWarnings("unchecked")
    void search_inlineFragmentsResolvePerType() {
        // Inline fragments must dispatch per concrete type: Film rows have filmId+title,
        // Actor rows have actorId+firstName. The schema-class TypeResolver reads __typename
        // off each Record and returns the matching ObjectType so graphql-java picks the
        // correct field arm per row.
        Map<String, Object> data = execute("""
            { search {
                __typename
                ... on Film { filmId title }
                ... on Actor { actorId firstName }
            } }
            """);
        List<Map<String, Object>> items = (List<Map<String, Object>>) data.get("search");
        var filmRows = items.stream()
            .filter(i -> "Film".equals(i.get("__typename")))
            .toList();
        var actorRows = items.stream()
            .filter(i -> "Actor".equals(i.get("__typename")))
            .toList();
        assertThat(filmRows).hasSize(5);
        assertThat(actorRows).hasSize(3);
        assertThat(filmRows).allSatisfy(i -> {
            assertThat(i.get("filmId")).as("Film.filmId").isNotNull();
            assertThat(i.get("title")).as("Film.title").isNotNull();
        });
        assertThat(actorRows).allSatisfy(i -> {
            assertThat(i.get("actorId")).as("Actor.actorId").isNotNull();
            assertThat(i.get("firstName")).as("Actor.firstName").isNotNull();
        });
    }

    @Test
    @SuppressWarnings("unchecked")
    void search_interfaceFieldName_resolvesPerParticipantColumn() {
        // Searchable.name remaps to FILM.TITLE on Film and ACTOR.FIRST_NAME on Actor.
        // Stage 2's per-typename SELECT projects each participant's $fields(env, t, env)
        // contribution, so the typed Record carries the right column under the interface
        // alias on each row.
        Map<String, Object> data = execute("{ search { __typename name } }");
        List<Map<String, Object>> items = (List<Map<String, Object>>) data.get("search");
        // ACADEMY DINOSAUR is the first film seeded in init.sql; PENELOPE is the first actor.
        // The interface field 'name' must surface those values, sourced from each
        // participant's distinct concrete column.
        assertThat(items).filteredOn(i -> "Film".equals(i.get("__typename")))
            .extracting(i -> i.get("name"))
            .contains("ACADEMY DINOSAUR");
        assertThat(items).filteredOn(i -> "Actor".equals(i.get("__typename")))
            .extracting(i -> i.get("name"))
            .contains("PENELOPE");
    }

    @Test
    @SuppressWarnings("unchecked")
    void documents_unionVariant_returnsSameShape() {
        // Document = Film | Actor exercises the union variant's wiring (QueryUnionField).
        // MultiTablePolymorphicEmitter produces the same two-stage shape for both
        // QueryInterfaceField and QueryUnionField; this asserts the union form returns the
        // same row count and __typename routing as the interface form.
        Map<String, Object> data = execute("""
            { documents {
                __typename
                ... on Film { filmId }
                ... on Actor { actorId }
            } }
            """);
        List<Map<String, Object>> items = (List<Map<String, Object>>) data.get("documents");
        assertThat(items).hasSize(8);
        assertThat(items).extracting(i -> (String) i.get("__typename"))
            .allSatisfy(tn -> assertThat(tn).isIn("Film", "Actor"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void search_orderedBySortKey_acrossParticipants() {
        // Stage 1's ORDER BY runs database-side on the synthetic __sort__ column. Single-PK
        // participants project their PK directly, so the result interleaves Film and Actor
        // rows by their PK values. With Film PKs 1..5 and Actor PKs 1..3, the leading rows
        // (sort key 1..3) carry both types; trailing rows (sort key 4..5) are Films only.
        Map<String, Object> data = execute("""
            { search {
                __typename
                ... on Film { filmId }
                ... on Actor { actorId }
            } }
            """);
        List<Map<String, Object>> items = (List<Map<String, Object>>) data.get("search");
        assertThat(items.subList(items.size() - 2, items.size()))
            .as("trailing rows carry the highest sort keys (Film 4 and 5; Actor PKs cap at 3)")
            .allSatisfy(i -> assertThat(i.get("__typename")).isEqualTo("Film"));
    }

    // ===== Multi-table polymorphic Connection (Track B4a) =====
    //
    // searchConnection wraps the same Searchable interface as Query.search but in @asConnection
    // form. The emitter wraps the per-branch UNION ALL in a derived table 'pages' so cursor
    // decode + .seek + LIMIT N+1 apply uniformly across the union; each typed stage-2 Record
    // carries both __typename and __sort__ so ConnectionHelper.encodeCursor can read the sort
    // key on each edge. Default page size is 5 (set in fixture) so a default-args query returns
    // exactly 5 of the 8 total rows. The emitter adds (sortField ASC, typenameField ASC) as a
    // composite ORDER BY so rows tied on sort key (e.g. Film(1)/Actor(1)) order deterministically.

    @Test
    @SuppressWarnings("unchecked")
    void searchConnection_firstPage_returnsFirstNAcrossParticipants() {
        // first: 3 returns the 3 lowest sort keys: Actor(1), Film(1), Actor(2). Tie order uses
        // typename ASC so 'Actor' precedes 'Film' on shared sort keys.
        Map<String, Object> data = execute("""
            { searchConnection(first: 3) {
                nodes { __typename ... on Film { filmId } ... on Actor { actorId } }
                pageInfo { hasNextPage hasPreviousPage }
            } }
            """);
        var conn = (Map<String, Object>) data.get("searchConnection");
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) conn.get("nodes");
        assertThat(nodes).hasSize(3);
        var pageInfo = (Map<String, Object>) conn.get("pageInfo");
        assertThat(pageInfo.get("hasNextPage")).isEqualTo(true);
        assertThat(pageInfo.get("hasPreviousPage")).isEqualTo(false);
    }

    @Test
    @SuppressWarnings("unchecked")
    void searchConnection_defaultPageSize_returnsFiveOfEight() {
        // Fixture sets defaultFirstValue: 5. With 5 films + 3 actors = 8 rows total, an
        // unargumented query returns the first 5 by sort key and reports hasNextPage=true.
        Map<String, Object> data = execute("""
            { searchConnection { nodes { __typename } pageInfo { hasNextPage } } }
            """);
        var conn = (Map<String, Object>) data.get("searchConnection");
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) conn.get("nodes");
        assertThat(nodes).hasSize(5);
        var pageInfo = (Map<String, Object>) conn.get("pageInfo");
        assertThat(pageInfo.get("hasNextPage")).isEqualTo(true);
    }

    @Test
    @SuppressWarnings("unchecked")
    void searchConnection_withAfterCursor_returnsNextPage() {
        // Page 1 (first: 3) + after cursor → page 2 (first: 3) returns the next 3 by sort key.
        Map<String, Object> page1Data = execute("""
            { searchConnection(first: 3) {
                edges { cursor }
                pageInfo { endCursor }
            } }
            """);
        var conn1 = (Map<String, Object>) page1Data.get("searchConnection");
        var pageInfo1 = (Map<String, Object>) conn1.get("pageInfo");
        String endCursor = (String) pageInfo1.get("endCursor");
        assertThat(endCursor).isNotNull();

        Map<String, Object> page2Data = execute(
            "{ searchConnection(first: 3, after: \"" + endCursor + "\") { "
            + "nodes { __typename } pageInfo { hasNextPage } } }");
        var conn2 = (Map<String, Object>) page2Data.get("searchConnection");
        List<Map<String, Object>> nodes2 = (List<Map<String, Object>>) conn2.get("nodes");
        assertThat(nodes2).hasSize(3);
        var pageInfo2 = (Map<String, Object>) conn2.get("pageInfo");
        // 8 total - 3 (page1) - 3 (page2) = 2 remaining → hasNextPage true.
        assertThat(pageInfo2.get("hasNextPage")).isEqualTo(true);
    }

    @Test
    @SuppressWarnings("unchecked")
    void searchConnection_lastPage_hasNextPageFalse() {
        // first: 8 returns all 8 rows; hasNextPage is false because over-fetch returns 8 rows
        // (less than pageSize+1 = 9).
        Map<String, Object> data = execute("""
            { searchConnection(first: 8) {
                nodes { __typename }
                pageInfo { hasNextPage }
            } }
            """);
        var conn = (Map<String, Object>) data.get("searchConnection");
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) conn.get("nodes");
        assertThat(nodes).hasSize(8);
        var pageInfo = (Map<String, Object>) conn.get("pageInfo");
        assertThat(pageInfo.get("hasNextPage")).isEqualTo(false);
    }

    @Test
    @SuppressWarnings("unchecked")
    void searchConnection_inlineFragmentsResolvePerTypeOnConnectionPath() {
        // Confirms the connection path retains TypeResolver routing: inline fragments dispatch
        // per concrete type so Film rows have filmId+title, Actor rows have actorId+firstName.
        Map<String, Object> data = execute("""
            { searchConnection(first: 8) {
                nodes {
                    __typename
                    ... on Film { filmId title }
                    ... on Actor { actorId firstName }
                }
            } }
            """);
        var conn = (Map<String, Object>) data.get("searchConnection");
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) conn.get("nodes");
        var filmRows = nodes.stream().filter(i -> "Film".equals(i.get("__typename"))).toList();
        var actorRows = nodes.stream().filter(i -> "Actor".equals(i.get("__typename"))).toList();
        assertThat(filmRows).hasSize(5);
        assertThat(actorRows).hasSize(3);
        assertThat(filmRows).allSatisfy(i -> {
            assertThat(i.get("filmId")).as("Film.filmId").isNotNull();
            assertThat(i.get("title")).as("Film.title").isNotNull();
        });
        assertThat(actorRows).allSatisfy(i -> {
            assertThat(i.get("actorId")).as("Actor.actorId").isNotNull();
            assertThat(i.get("firstName")).as("Actor.firstName").isNotNull();
        });
    }

    @Test
    @SuppressWarnings("unchecked")
    void documentsConnection_unionVariant_works() {
        // Union variant equivalent: documentsConnection wraps `Document = Film | Actor` in
        // @asConnection. Same emitter path as searchConnection; this test pins parity.
        Map<String, Object> data = execute("""
            { documentsConnection(first: 4) {
                nodes { __typename ... on Film { filmId } ... on Actor { actorId } }
                pageInfo { hasNextPage }
            } }
            """);
        var conn = (Map<String, Object>) data.get("documentsConnection");
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) conn.get("nodes");
        assertThat(nodes).hasSize(4);
        assertThat(nodes).extracting(i -> (String) i.get("__typename"))
            .allSatisfy(tn -> assertThat(tn).isIn("Film", "Actor"));
        var pageInfo = (Map<String, Object>) conn.get("pageInfo");
        assertThat(pageInfo.get("hasNextPage")).isEqualTo(true);
    }

    @Test
    @SuppressWarnings("unchecked")
    void searchConnection_totalCount_returnsTotalRowCountAcrossParticipants() {
        // B4b: ConnectionResult now carries the UNION-ALL derived table 'pages' so
        // ConnectionHelper.totalCount runs SELECT count(*) FROM (UNION ALL Film + Actor) AS pages.
        // 5 films + 3 actors = 8 total; the count is independent of the page window.
        Map<String, Object> data = execute("""
            { searchConnection(first: 1) { totalCount } }
            """);
        var conn = (Map<String, Object>) data.get("searchConnection");
        assertThat(conn.get("totalCount")).isEqualTo(8);
    }

    @Test
    @SuppressWarnings("unchecked")
    void searchConnection_totalCount_independentOfAfterCursor() {
        // The count is over the full UNION ALL, not the page window — so paging past the start
        // with an after cursor still reports the full 8.
        Map<String, Object> page1Data = execute("""
            { searchConnection(first: 3) { pageInfo { endCursor } } }
            """);
        var pageInfo1 = (Map<String, Object>) ((Map<String, Object>) page1Data.get("searchConnection")).get("pageInfo");
        String endCursor = (String) pageInfo1.get("endCursor");

        Map<String, Object> page2Data = execute(
            "{ searchConnection(first: 3, after: \"" + endCursor + "\") { totalCount } }");
        var conn = (Map<String, Object>) page2Data.get("searchConnection");
        assertThat(conn.get("totalCount")).isEqualTo(8);
    }

    @Test
    @SuppressWarnings("unchecked")
    void searchConnection_totalCount_isLazyOnSelection_noCountSqlWhenUnselected() {
        // The polymorphic totalCount path should remain lazy on selection just like the
        // single-table case. graphql-java only invokes the resolver when the client picks
        // the field; no SELECT count should fire for an unselected totalCount.
        SQL_LOG.clear();
        execute("{ searchConnection(first: 2) { nodes { __typename } pageInfo { hasNextPage } } }");
        assertThat(SQL_LOG)
            .as("no SELECT count statement should be issued when totalCount is not selected")
            .noneMatch(s -> s.contains("select count"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void searchConnection_totalCount_selected_issuesCountSql() {
        // Companion ratchet: when totalCount IS selected, exactly one count statement runs;
        // pages-derived-table emission shows up as a `select count(*) from (...) as "pages"` shape.
        SQL_LOG.clear();
        execute("{ searchConnection(first: 2) { totalCount nodes { __typename } } }");
        assertThat(SQL_LOG)
            .filteredOn(s -> s.contains("select count"))
            .as("selecting totalCount should issue exactly one SELECT count statement")
            .hasSize(1);
    }

    @Test
    @SuppressWarnings("unchecked")
    void documentsConnection_totalCount_unionVariant_returnsTotalRowCount() {
        // Union variant parity: same emitter path, same totalCount shape.
        Map<String, Object> data = execute("""
            { documentsConnection(first: 1) { totalCount } }
            """);
        var conn = (Map<String, Object>) data.get("documentsConnection");
        assertThat(conn.get("totalCount")).isEqualTo(8);
    }

    // ===== Composite-PK participants in connection mode (R36 item 1) =====
    //
    // pagedItems wraps `interface PagedItem` (PagedA + PagedB, both composite (Integer, Integer)
    // PK) in @asConnection. The polymorphic emitter projects DSL.jsonbArray(k1, k2) as the
    // synthetic __sort__ column and types it as JSONB so PostgreSQL's lexicographic JSONB
    // ordering reproduces the multi-column ordering across the union, and
    // ConnectionHelper.encodeCursor / decodeCursor round-trip JSONB through JSONB.toString()
    // and Convert.convert(String, JSONB.class).
    //
    // Seed (init.sql):
    //   paged_a (k1, k2, name): (1,1,'A-1-1'), (1,2,'A-1-2'), (2,1,'A-2-1')
    //   paged_b (k1, k2, name): (1,1,'B-1-1'), (1,3,'B-1-3'), (3,1,'B-3-1')
    // JSONB lexicographic order is element-wise: [1,1] < [1,2] < [1,3] < [2,1] < [3,1]. With the
    // (sort ASC, typename ASC) tiebreaker, the global order is:
    //   PagedA[1,1], PagedB[1,1], PagedA[1,2], PagedB[1,3], PagedA[2,1], PagedB[3,1].

    @Test
    @SuppressWarnings("unchecked")
    void pagedItemsConnection_firstPage_returnsFirstThreeAcrossCompositePkParticipants() {
        // first: 3 returns the 3 lowest sort keys: PagedA[1,1], PagedB[1,1] (typename tiebreaker
        // resolves the [1,1] tie alphabetically: 'PagedA' < 'PagedB'), PagedA[1,2].
        Map<String, Object> data = execute("""
            { pagedItems(first: 3) {
                nodes {
                    __typename
                    ... on PagedA { k1 k2 name }
                    ... on PagedB { k1 k2 name }
                }
                pageInfo { hasNextPage hasPreviousPage }
            } }
            """);
        var conn = (Map<String, Object>) data.get("pagedItems");
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) conn.get("nodes");
        assertThat(nodes).hasSize(3);
        assertThat(nodes).extracting(i -> i.get("__typename") + ":" + i.get("k1") + "," + i.get("k2"))
            .containsExactly("PagedA:1,1", "PagedB:1,1", "PagedA:1,2");
        var pageInfo = (Map<String, Object>) conn.get("pageInfo");
        assertThat(pageInfo.get("hasNextPage")).isEqualTo(true);
        assertThat(pageInfo.get("hasPreviousPage")).isEqualTo(false);
    }

    @Test
    @SuppressWarnings("unchecked")
    void pagedItemsConnection_afterCursor_returnsNextPage() {
        // Page 1 (first: 3) + after endCursor → page 2 (first: 3) returns the next 3 by sort.
        // The cursor encodes the JSONB sort value of the last row on page 1, base64-wrapped via
        // ConnectionHelper.encodeCursor; decodeCursor parses it back as a JSONB Field for seek.
        Map<String, Object> page1Data = execute("""
            { pagedItems(first: 3) { pageInfo { endCursor } } }
            """);
        String endCursor = (String) ((Map<String, Object>) ((Map<String, Object>) page1Data.get("pagedItems")).get("pageInfo")).get("endCursor");
        assertThat(endCursor).isNotNull();

        Map<String, Object> page2Data = execute(
            "{ pagedItems(first: 3, after: \"" + endCursor + "\") { "
            + "nodes { __typename ... on PagedA { k1 k2 } ... on PagedB { k1 k2 } } "
            + "pageInfo { hasNextPage } } }");
        var conn2 = (Map<String, Object>) page2Data.get("pagedItems");
        List<Map<String, Object>> nodes2 = (List<Map<String, Object>>) conn2.get("nodes");
        assertThat(nodes2).hasSize(3);
        assertThat(nodes2).extracting(i -> i.get("__typename") + ":" + i.get("k1") + "," + i.get("k2"))
            .containsExactly("PagedB:1,3", "PagedA:2,1", "PagedB:3,1");
        var pageInfo2 = (Map<String, Object>) conn2.get("pageInfo");
        // 6 total - 3 (page1) - 3 (page2) = 0 remaining → hasNextPage false.
        assertThat(pageInfo2.get("hasNextPage")).isEqualTo(false);
    }

    @Test
    @SuppressWarnings("unchecked")
    void pagedItemsConnection_lastPage_hasNextPageFalse() {
        // first: 6 returns all 6 rows; over-fetch sees 6 rows (< pageSize+1 = 7) → hasNextPage false.
        Map<String, Object> data = execute("""
            { pagedItems(first: 6) {
                nodes { __typename }
                pageInfo { hasNextPage }
            } }
            """);
        var conn = (Map<String, Object>) data.get("pagedItems");
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) conn.get("nodes");
        assertThat(nodes).hasSize(6);
        var pageInfo = (Map<String, Object>) conn.get("pageInfo");
        assertThat(pageInfo.get("hasNextPage")).isEqualTo(false);
    }

    @Test
    @SuppressWarnings("unchecked")
    void pagedItemsConnection_totalCount_returnsTotalAcrossCompositePkParticipants() {
        // totalCount runs SELECT count(*) FROM (UNION ALL paged_a + paged_b) AS pages,
        // independent of the page window. 3 + 3 = 6.
        Map<String, Object> data = execute("""
            { pagedItems(first: 1) { totalCount } }
            """);
        var conn = (Map<String, Object>) data.get("pagedItems");
        assertThat(conn.get("totalCount")).isEqualTo(6);
    }

    // ===== ChildField.UnionField (Track B3) =====
    //
    // Address.occupants returns a union of Customer + Staff rows whose address_id matches
    // the parent Address record. Stage-1 narrow UNION ALL adds a per-branch
    // .where(<participant>.address_id = parentRecord.address_id) predicate; stage-2
    // dispatches per typename, the same shape B2 uses for the root case.

    @Test
    @SuppressWarnings("unchecked")
    void addressOccupants_returnsCustomersAndStaffForAddress() {
        // init.sql seeds:
        //   address_id=1: Customers Mary + Barbara (2), no staff
        //   address_id=3: Customer Linda (1), Staff Mike (1) — mixed types
        // Linda is Customer 3 with store_id=2; navigate Customer.address to reach Address(3).
        Map<String, Object> data = execute("""
            { customerById(customer_id: ["3"], store_id: "2") {
                firstName
                address {
                    addressId
                    occupants {
                        __typename
                        ... on Customer { firstName }
                        ... on Staff { firstName }
                    }
                }
            } }
            """);
        List<Map<String, Object>> customers = (List<Map<String, Object>>) data.get("customerById");
        assertThat(customers).hasSize(1);
        var address = (Map<String, Object>) customers.get(0).get("address");
        assertThat(address.get("addressId")).isEqualTo(3);
        var occupants = (List<Map<String, Object>>) address.get("occupants");
        assertThat(occupants).hasSize(2);
        var byType = occupants.stream()
            .collect(java.util.stream.Collectors.groupingBy(o -> (String) o.get("__typename")));
        assertThat(byType.get("Customer")).hasSize(1);
        assertThat(byType.get("Staff")).hasSize(1);
        assertThat((String) byType.get("Customer").get(0).get("firstName")).isEqualTo("Linda");
        assertThat((String) byType.get("Staff").get(0).get("firstName")).isEqualTo("Mike");
    }

    @Test
    @SuppressWarnings("unchecked")
    void addressOccupants_homogeneousAddress_returnsOnlyOneType() {
        // Address(1) has only Customers (Mary, Barbara) — the Staff branch's stage-1 SELECT
        // returns no rows, and the per-typename dispatch skips selectStaffForOccupants
        // because byType.containsKey("Staff") is false. Mary is Customer 1 in store 1.
        Map<String, Object> data = execute("""
            { customerById(customer_id: ["1"], store_id: "1") {
                address {
                    addressId
                    occupants { __typename }
                }
            } }
            """);
        List<Map<String, Object>> customers = (List<Map<String, Object>>) data.get("customerById");
        var address = (Map<String, Object>) customers.get(0).get("address");
        assertThat(address.get("addressId")).isEqualTo(1);
        var occupants = (List<Map<String, Object>>) address.get("occupants");
        assertThat(occupants).hasSize(2);
        assertThat(occupants).extracting(o -> o.get("__typename"))
            .containsOnly("Customer");
    }

    @Test
    @SuppressWarnings("unchecked")
    void addressOccupants_perBranchWhereScopesToAddress() {
        // Two independent address parents must each scope their UNION ALL branches to their
        // own address_id. Mary (address 1, all-customer occupants) and Linda (address 3,
        // mixed occupants) have disjoint occupant sets; the per-branch parentRecord-FK
        // predicate is what keeps those scopes independent.
        Map<String, Object> data = execute("""
            { customers(active: true) {
                customerId
                firstName
                address {
                    addressId
                    occupants { __typename ... on Customer { firstName } ... on Staff { firstName } }
                }
            } }
            """);
        List<Map<String, Object>> customers = (List<Map<String, Object>>) data.get("customers");
        var byCustomerId = customers.stream()
            .collect(java.util.stream.Collectors.toMap(
                c -> (Integer) c.get("customerId"),
                c -> (Map<String, Object>) c.get("address")));
        var addressOfMary = byCustomerId.get(1);
        var addressOfLinda = byCustomerId.get(3);
        assertThat(addressOfMary.get("addressId")).isEqualTo(1);
        assertThat(addressOfLinda.get("addressId")).isEqualTo(3);
        var maryOccupants = (List<Map<String, Object>>) addressOfMary.get("occupants");
        var lindaOccupants = (List<Map<String, Object>>) addressOfLinda.get("occupants");
        assertThat(maryOccupants).extracting(o -> o.get("firstName"))
            .as("address 1 occupants are only Customers (Mary, Barbara) — no Staff there")
            .containsExactlyInAnyOrder("Mary", "Barbara");
        assertThat(lindaOccupants).extracting(o -> o.get("firstName"))
            .as("address 3 occupants are mixed (Customer Linda + Staff Mike)")
            .containsExactlyInAnyOrder("Linda", "Mike");
    }

    // ===== ChildField.UnionField + @asConnection (Track B4c-1) =====
    //
    // Address.occupantsConnection wraps the same union as Address.occupants but in connection
    // mode. Per-branch parent-FK WHERE (B3) lives inside each UNION ALL branch; the outer
    // pagesTable derived table feeds .seek/.limit and ConnectionResult.table() so totalCount
    // counts only this parent's occupants. Per-parent inline shape — DataLoader-batched
    // windowed CTE form is the B4c-2 follow-up.

    @Test
    @SuppressWarnings("unchecked")
    void addressOccupantsConnection_returnsParentScopedRows() {
        // Address(3) has Linda (Customer) + Mike (Staff). The connection returns both nodes;
        // hasNextPage is false because pageSize 5 over-fetches and only 2 rows exist.
        Map<String, Object> data = execute("""
            { customerById(customer_id: ["3"], store_id: "2") {
                address {
                    addressId
                    occupantsConnection {
                        nodes { __typename ... on Customer { firstName } ... on Staff { firstName } }
                        pageInfo { hasNextPage hasPreviousPage }
                    }
                }
            } }
            """);
        var customers = (List<Map<String, Object>>) data.get("customerById");
        var address = (Map<String, Object>) customers.get(0).get("address");
        assertThat(address.get("addressId")).isEqualTo(3);
        var conn = (Map<String, Object>) address.get("occupantsConnection");
        var nodes = (List<Map<String, Object>>) conn.get("nodes");
        assertThat(nodes).hasSize(2);
        assertThat(nodes).extracting(n -> n.get("firstName"))
            .containsExactlyInAnyOrder("Linda", "Mike");
        var pageInfo = (Map<String, Object>) conn.get("pageInfo");
        assertThat(pageInfo.get("hasNextPage")).isEqualTo(false);
        assertThat(pageInfo.get("hasPreviousPage")).isEqualTo(false);
    }

    @Test
    @SuppressWarnings("unchecked")
    void addressOccupantsConnection_totalCount_isParentScoped() {
        // The per-branch parentRecord-FK WHERE inside each UNION ALL branch is what makes
        // totalCount count only this parent's occupants. Address(2) has 2 customers
        // (Patricia, Elizabeth) + 1 staff (Jon) = 3; Address(3) has 1 customer (Linda) +
        // 1 staff (Mike) = 2. If the WHERE were dropped the count would collapse to the
        // global occupant total.
        Map<String, Object> data = execute("""
            { customerById(customer_id: ["2"], store_id: "1") {
                address { addressId occupantsConnection { totalCount } }
            } }
            """);
        var customers = (List<Map<String, Object>>) data.get("customerById");
        var address = (Map<String, Object>) customers.get(0).get("address");
        assertThat(address.get("addressId")).isEqualTo(2);
        var conn = (Map<String, Object>) address.get("occupantsConnection");
        assertThat(conn.get("totalCount")).isEqualTo(3);

        Map<String, Object> data3 = execute("""
            { customerById(customer_id: ["3"], store_id: "2") {
                address { addressId occupantsConnection { totalCount } }
            } }
            """);
        var address3 = (Map<String, Object>) ((List<Map<String, Object>>) data3.get("customerById"))
            .get(0).get("address");
        assertThat(((Map<String, Object>) address3.get("occupantsConnection")).get("totalCount"))
            .isEqualTo(2);
    }

    @Test
    @SuppressWarnings("unchecked")
    void addressOccupantsConnection_paginationWithAfterCursor() {
        // Address(2) has 3 occupants. Sort keys: Customer 2 (Patricia), Customer 5 (Elizabeth),
        // Staff 2 (Jon). Tie order with __typename ASC at sort=2 places Customer before Staff.
        // first: 2 returns the first 2 by sort key; after-cursor advances to the third.
        Map<String, Object> page1Data = execute("""
            { customerById(customer_id: ["2"], store_id: "1") {
                address { occupantsConnection(first: 2) {
                    edges { cursor node { __typename ... on Customer { firstName } ... on Staff { firstName } } }
                    pageInfo { endCursor hasNextPage }
                } }
            } }
            """);
        var page1Conn = (Map<String, Object>) ((Map<String, Object>) ((List<Map<String, Object>>) page1Data
            .get("customerById")).get(0).get("address")).get("occupantsConnection");
        var edges1 = (List<Map<String, Object>>) page1Conn.get("edges");
        assertThat(edges1).hasSize(2);
        var pageInfo1 = (Map<String, Object>) page1Conn.get("pageInfo");
        assertThat(pageInfo1.get("hasNextPage")).isEqualTo(true);
        String endCursor = (String) pageInfo1.get("endCursor");
        assertThat(endCursor).isNotNull();

        Map<String, Object> page2Data = execute(
            "{ customerById(customer_id: [\"2\"], store_id: \"1\") { "
            + "address { occupantsConnection(first: 2, after: \"" + endCursor + "\") { "
            + "nodes { __typename ... on Customer { firstName } ... on Staff { firstName } } "
            + "pageInfo { hasNextPage } } } } }");
        var page2Conn = (Map<String, Object>) ((Map<String, Object>) ((List<Map<String, Object>>) page2Data
            .get("customerById")).get(0).get("address")).get("occupantsConnection");
        var nodes2 = (List<Map<String, Object>>) page2Conn.get("nodes");
        assertThat(nodes2).hasSize(1);
        assertThat(((Map<String, Object>) page2Conn.get("pageInfo")).get("hasNextPage")).isEqualTo(false);
    }

    @Test
    @SuppressWarnings("unchecked")
    void addressOccupantsConnection_dataLoaderBatchesAcrossParents() {
        // R36 Track B4c-2 batching ratchet: with N parents fetching occupantsConnection in
        // one request, the DataLoader collapses the per-branch UNION-ALL page-rows query to
        // ONE invocation regardless of N. The {@code as "parentInput"} VALUES table is unique
        // to the rows-method (stage 2's per-typename helpers use {@code customerInput} /
        // {@code staffInput} aliases), so a single occurrence in SQL_LOG = one batched query.
        // Without B4c-2 (or under B4c-1's per-parent inline shape) this would fire one
        // UNION-ALL per parent.
        SQL_LOG.clear();
        Map<String, Object> data = execute("""
            { customers(active: true) {
                customerId
                address {
                    addressId
                    occupantsConnection(first: 1) {
                        edges { node { __typename } }
                    }
                }
            } }
            """);
        var customers = (List<Map<String, Object>>) data.get("customers");
        assertThat(customers)
            .as("customers list should be non-empty (sakila has 599 active customers)")
            .isNotEmpty();
        // Every customer carries an address with an occupantsConnection sublist.
        assertThat(customers).allSatisfy(c -> {
            var address = (Map<String, Object>) c.get("address");
            assertThat(address.get("addressId")).isNotNull();
            var conn = (Map<String, Object>) address.get("occupantsConnection");
            assertThat(conn).isNotNull();
        });
        // The page-rows rows-method fires ONE UNION-ALL with parentInput VALUES regardless
        // of how many parents we ask about — that is the whole point of the DataLoader
        // promotion from B4c-1 (per-parent inline) to B4c-2 (batched).
        var parentInputQueries = SQL_LOG.stream()
            .filter(s -> s.contains("as \"parentinput\""))
            .toList();
        assertThat(parentInputQueries)
            .as("rows-method fires once for the entire batch; SQL_LOG: " + parentInputQueries)
            .hasSize(1);
    }

    // ===== Composite-PK parent for child interface @asConnection (R36 item 2) =====
    //
    // Project's PK is (org_id, project_id); ProjectNote and ProjectEvent FK back via
    // composite (org_id, project_id) → project. The B4c-2 RowN widening pins Row1 → Row2 at the
    // unit-tier (TypeFetcherGeneratorTest); these tests carry it through to PostgreSQL.
    //
    // Seed (init.sql):
    //   project (org_id, project_id, name): (1,100,'Atlas'), (1,101,'Beacon'), (2,100,'Cipher')
    //   project_note (note_id auto, org_id, project_id, body):
    //     (1, 1, 100, 'Atlas-N1'), (2, 1, 100, 'Atlas-N2'), (3, 1, 100, 'Atlas-N3'),
    //     (4, 1, 101, 'Beacon-N1'), (5, 1, 101, 'Beacon-N2'), (6, 2, 100, 'Cipher-N1')
    //   project_event (event_id auto, org_id, project_id, summary):
    //     (1, 1, 100, 'Atlas-E1'),
    //     (2, 1, 101, 'Beacon-E1'), (3, 1, 101, 'Beacon-E2'),
    //     (4, 2, 100, 'Cipher-E1')
    // Per-parent counts: Atlas 3+1=4, Beacon 2+2=4, Cipher 1+1=2.

    @Test
    @SuppressWarnings("unchecked")
    void projectItemsConnection_returnsParentScopedRows() {
        // Each parent's connection counts only its own children. Atlas has 4 items, Cipher has 2.
        // The parent PK columns (orgId, projectId) must be projected so the child fetcher can
        // extract them from env.getSource() — selecting them explicitly satisfies the framework's
        // jOOQ row-type contract on the parent Record.
        Map<String, Object> data = execute("""
            { projects { name orgId projectId items { nodes { __typename } } } }
            """);
        var projects = (List<Map<String, Object>>) data.get("projects");
        var byName = projects.stream()
            .collect(java.util.stream.Collectors.toMap(p -> (String) p.get("name"), java.util.function.Function.identity()));
        var atlasItems = (List<Map<String, Object>>) ((Map<String, Object>) byName.get("Atlas").get("items")).get("nodes");
        var beaconItems = (List<Map<String, Object>>) ((Map<String, Object>) byName.get("Beacon").get("items")).get("nodes");
        var cipherItems = (List<Map<String, Object>>) ((Map<String, Object>) byName.get("Cipher").get("items")).get("nodes");
        assertThat(atlasItems).hasSize(4);
        assertThat(beaconItems).hasSize(4);
        assertThat(cipherItems).hasSize(2);
    }

    @Test
    @SuppressWarnings("unchecked")
    void projectItemsConnection_totalCount_isParentScoped() {
        // Per-parent ConnectionResult uses idxField.eq(i) so totalCount restricts to the carrier
        // parent's slice of the union. Cipher should report exactly 2; Atlas exactly 4.
        Map<String, Object> data = execute("""
            { projects { name orgId projectId items { totalCount } } }
            """);
        var projects = (List<Map<String, Object>>) data.get("projects");
        var byName = projects.stream()
            .collect(java.util.stream.Collectors.toMap(p -> (String) p.get("name"), java.util.function.Function.identity()));
        assertThat(((Map<String, Object>) byName.get("Atlas").get("items")).get("totalCount")).isEqualTo(4);
        assertThat(((Map<String, Object>) byName.get("Beacon").get("items")).get("totalCount")).isEqualTo(4);
        assertThat(((Map<String, Object>) byName.get("Cipher").get("items")).get("totalCount")).isEqualTo(2);
    }

    @Test
    @SuppressWarnings("unchecked")
    void projectItemsConnection_paginationWithAfterCursor() {
        // Sort order within a parent is (sortField ASC, __typename ASC). Beacon has Notes 4, 5
        // and Events 2, 3. PK ASC interleave: Event(2), Event(3), Note(4), Note(5). first: 2
        // returns the first two Events; after-cursor advances to the two Notes.
        Map<String, Object> page1Data = execute("""
            { projects {
                name orgId projectId
                items(first: 2) {
                    edges { node { __typename ... on ProjectNote { noteId } ... on ProjectEvent { eventId } } }
                    pageInfo { endCursor hasNextPage }
                }
            } }
            """);
        var projects = (List<Map<String, Object>>) page1Data.get("projects");
        var beacon = projects.stream().filter(p -> "Beacon".equals(p.get("name"))).findFirst().orElseThrow();
        var page1Conn = (Map<String, Object>) beacon.get("items");
        var pageInfo1 = (Map<String, Object>) page1Conn.get("pageInfo");
        assertThat(pageInfo1.get("hasNextPage")).isEqualTo(true);
        String endCursor = (String) pageInfo1.get("endCursor");
        assertThat(endCursor).isNotNull();

        // Page 2: filter to Beacon only via the after cursor on the second projects fetch.
        // Since `projects` returns all parents, we re-fetch with after cursor and filter again.
        Map<String, Object> page2Data = execute(
            "{ projects { name orgId projectId items(first: 2, after: \"" + endCursor + "\") { "
            + "nodes { __typename ... on ProjectNote { noteId } ... on ProjectEvent { eventId } } "
            + "pageInfo { hasNextPage } } } }");
        var projects2 = (List<Map<String, Object>>) page2Data.get("projects");
        var beacon2 = projects2.stream().filter(p -> "Beacon".equals(p.get("name"))).findFirst().orElseThrow();
        var page2Conn = (Map<String, Object>) beacon2.get("items");
        var nodes2 = (List<Map<String, Object>>) page2Conn.get("nodes");
        assertThat(nodes2).extracting(n -> n.get("__typename"))
            .as("Beacon page 2 should be the two Notes after the two Events")
            .containsExactly("ProjectNote", "ProjectNote");
        assertThat(((Map<String, Object>) page2Conn.get("pageInfo")).get("hasNextPage")).isEqualTo(false);
    }

    @Test
    @SuppressWarnings("unchecked")
    void projectItemsConnection_inlineFragmentResolvesPerType() {
        // Inline fragments dispatch per concrete type so ProjectNote rows expose noteId+body and
        // ProjectEvent rows expose eventId+summary. The TypeResolver routes via __typename on
        // each typed Record.
        Map<String, Object> data = execute("""
            { projects {
                name orgId projectId
                items {
                    nodes {
                        __typename
                        ... on ProjectNote { noteId body }
                        ... on ProjectEvent { eventId summary }
                    }
                }
            } }
            """);
        var projects = (List<Map<String, Object>>) data.get("projects");
        var atlas = projects.stream().filter(p -> "Atlas".equals(p.get("name"))).findFirst().orElseThrow();
        var atlasItems = (List<Map<String, Object>>) ((Map<String, Object>) atlas.get("items")).get("nodes");
        var noteRows = atlasItems.stream().filter(n -> "ProjectNote".equals(n.get("__typename"))).toList();
        var eventRows = atlasItems.stream().filter(n -> "ProjectEvent".equals(n.get("__typename"))).toList();
        assertThat(noteRows).hasSize(3);
        assertThat(eventRows).hasSize(1);
        assertThat(noteRows).allSatisfy(n -> {
            assertThat(n.get("noteId")).as("ProjectNote.noteId").isNotNull();
            assertThat((String) n.get("body")).as("ProjectNote.body").startsWith("Atlas-N");
        });
        assertThat(eventRows).allSatisfy(e -> {
            assertThat(e.get("eventId")).as("ProjectEvent.eventId").isNotNull();
            assertThat((String) e.get("summary")).as("ProjectEvent.summary").isEqualTo("Atlas-E1");
        });
    }

    @Test
    @SuppressWarnings("unchecked")
    void projectItemsConnection_dataLoaderBatchesAcrossParents() {
        // R36 Track B4c-2 batching ratchet for composite-PK parents: with 3 parents (Atlas,
        // Beacon, Cipher) fetching items in one request, the DataLoader collapses the per-branch
        // UNION-ALL page-rows query to ONE invocation. parentInput VALUES is keyed Row3<Integer,
        // Integer, Integer> (idx + composite parent PK) and the per-branch JOIN ON emits an
        // AND-chain over (org_id, project_id). Without B4c-2's RowN widening this would have
        // failed to compile; without B4c-2's batching this would fire 3 separate UNION-ALLs.
        SQL_LOG.clear();
        Map<String, Object> data = execute("""
            { projects {
                name orgId projectId
                items(first: 1) {
                    edges { node { __typename } }
                }
            } }
            """);
        var projects = (List<Map<String, Object>>) data.get("projects");
        assertThat(projects).hasSize(3);
        // The page-rows rows-method fires once across all 3 parents.
        var parentInputQueries = SQL_LOG.stream()
            .filter(s -> s.contains("as \"parentinput\""))
            .toList();
        assertThat(parentInputQueries)
            .as("rows-method fires once for the composite-PK-parent batch; SQL_LOG: " + parentInputQueries)
            .hasSize(1);
        // The composite FK AND-chain shows up as two equality conjuncts on the FK columns.
        assertThat(parentInputQueries.get(0))
            .as("composite-FK JOIN predicate joins on both org_id and project_id")
            .contains("\"org_id\"")
            .contains("\"project_id\"");
    }

    @Test
    @SuppressWarnings("unchecked")
    void addressOccupantsConnection_inlineFragmentResolvesPerType() {
        // Connection-path TypeResolver routing: inline fragments dispatch per concrete type so
        // Customer rows expose customerId+firstName and Staff rows expose staffId+firstName.
        Map<String, Object> data = execute("""
            { customerById(customer_id: ["3"], store_id: "2") {
                address { occupantsConnection {
                    nodes {
                        __typename
                        ... on Customer { customerId firstName }
                        ... on Staff { staffId firstName }
                    }
                } }
            } }
            """);
        var address = (Map<String, Object>) ((List<Map<String, Object>>) data.get("customerById"))
            .get(0).get("address");
        var conn = (Map<String, Object>) address.get("occupantsConnection");
        var nodes = (List<Map<String, Object>>) conn.get("nodes");
        var customerRow = nodes.stream().filter(n -> "Customer".equals(n.get("__typename"))).findFirst().orElseThrow();
        var staffRow = nodes.stream().filter(n -> "Staff".equals(n.get("__typename"))).findFirst().orElseThrow();
        assertThat(customerRow.get("customerId")).isNotNull();
        assertThat(customerRow.get("firstName")).isEqualTo("Linda");
        assertThat(staffRow.get("staffId")).isNotNull();
        assertThat(staffRow.get("firstName")).isEqualTo("Mike");
    }

    // ===== R22 Phase 2: INSERT emitter (DML mutation, TableBoundReturnType) =====

    @Test
    @SuppressWarnings("unchecked")
    void createFilm_insertsRowAndReturnsProjectedFilm() {
        // INSERT mutation with @table return: emitter runs
        // `dsl.insertInto(film, title, language_id).values(...).returningResult($fields).fetchOne(r -> r)`,
        // and graphql-java walks the returned Record for the selected columns. This is the
        // first execution-tier coverage for R22's RETURNING-with-multiset shape on PostgreSQL —
        // DELETE shipped without one. The test cleans up its inserted row in a finally block
        // so other tests' film-count assumptions are preserved.
        String marker = "R22-PHASE-2-FILM-" + java.util.UUID.randomUUID();
        int countBefore = dsl.fetchCount(org.jooq.impl.DSL.table("film"));
        try {
            Map<String, Object> data = execute("""
                mutation {
                    createFilm(in: { title: "%s", languageId: 1 }) {
                        title
                        languageId
                        rentalRate
                    }
                }
                """.formatted(marker));

            Map<String, Object> created = (Map<String, Object>) data.get("createFilm");
            assertThat(created).containsEntry("title", marker);
            assertThat(created).containsEntry("languageId", 1);
            // rental_rate has a DB-side default of 4.99 that flows back through the RETURNING
            // projection, even though the input does not supply it.
            assertThat(((Number) created.get("rentalRate")).doubleValue()).isEqualTo(4.99);

            int countAfter = dsl.fetchCount(org.jooq.impl.DSL.table("film"));
            assertThat(countAfter)
                .as("createFilm inserted exactly one row")
                .isEqualTo(countBefore + 1);
        } finally {
            dsl.deleteFrom(org.jooq.impl.DSL.table("film"))
                .where(org.jooq.impl.DSL.field("title").eq(marker))
                .execute();
        }
    }

    // ===== R22 Phase 4: UPDATE emitter (DML mutation, TableBoundReturnType) =====

    @Test
    @SuppressWarnings("unchecked")
    void updateFilm_updatesRowAndReturnsProjectedFilm() {
        // UPDATE mutation with @table return: emitter runs
        // `dsl.update(film).set(title, val).where(film_id.eq(val)).returningResult($fields)
        //  .fetchOne(r -> r)`. Verifies the SET clause writes, the WHERE clause matches, and the
        // RETURNING projection flows back through graphql-java for the selected columns.
        // Pre-inserts a marker row by jOOQ so the test does not depend on (or mutate) any
        // pre-existing Sakila row, and cleans up in finally.
        String originalMarker = "R22-PHASE-4-FILM-" + java.util.UUID.randomUUID();
        String updatedMarker  = "R22-PHASE-4-FILM-UPDATED-" + java.util.UUID.randomUUID();
        var filmTable = org.jooq.impl.DSL.table("film");
        var filmIdCol = org.jooq.impl.DSL.field("film_id", Integer.class);
        Integer filmId = dsl.insertInto(filmTable)
            .set(org.jooq.impl.DSL.field("title"), originalMarker)
            .set(org.jooq.impl.DSL.field("language_id"), (short) 1)
            .returningResult(filmIdCol)
            .fetchOne()
            .value1();
        try {
            Map<String, Object> data = execute("""
                mutation {
                    updateFilm(in: { filmId: %d, title: "%s" }) {
                        title
                        languageId
                    }
                }
                """.formatted(filmId, updatedMarker));

            Map<String, Object> updated = (Map<String, Object>) data.get("updateFilm");
            assertThat(updated).containsEntry("title", updatedMarker);
            // languageId was not in the SET clause, so it carries through unchanged.
            assertThat(updated).containsEntry("languageId", 1);

            // Re-read by PK to confirm the SET clause actually wrote, not just that RETURNING
            // returned the projected shape.
            String dbTitle = dsl.select(org.jooq.impl.DSL.field("title", String.class))
                .from(filmTable).where(filmIdCol.eq(filmId)).fetchOne().value1();
            assertThat(dbTitle).isEqualTo(updatedMarker);
        } finally {
            dsl.deleteFrom(filmTable).where(filmIdCol.eq(filmId)).execute();
        }
    }

    // ===== R22 Phase 5: UPSERT emitter (DML mutation, TableBoundReturnType) =====

    @Test
    @SuppressWarnings("unchecked")
    void upsertFilm_updateBranch_writesAndReturnsProjectedFilm() {
        // UPSERT-on-existing-row: emitter runs `dsl.insertInto(film, ...).values(...)
        // .onConflict(film_id).doUpdate().set(title, ...).set(language_id, ...)
        // .returningResult($fields).fetchOne(r -> r)`. The pre-inserted row triggers the
        // ON CONFLICT branch; verifies the SET clause writes and RETURNING projects.
        String originalMarker = "R22-PHASE-5-UPSERT-" + java.util.UUID.randomUUID();
        String upsertedMarker = "R22-PHASE-5-UPSERTED-" + java.util.UUID.randomUUID();
        var filmTable = org.jooq.impl.DSL.table("film");
        var filmIdCol = org.jooq.impl.DSL.field("film_id", Integer.class);
        Integer filmId = dsl.insertInto(filmTable)
            .set(org.jooq.impl.DSL.field("title"), originalMarker)
            .set(org.jooq.impl.DSL.field("language_id"), (short) 1)
            .returningResult(filmIdCol)
            .fetchOne()
            .value1();
        try {
            Map<String, Object> data = execute("""
                mutation {
                    upsertFilm(in: { filmId: %d, title: "%s", languageId: 1 }) {
                        title
                        languageId
                    }
                }
                """.formatted(filmId, upsertedMarker));

            Map<String, Object> upserted = (Map<String, Object>) data.get("upsertFilm");
            assertThat(upserted).containsEntry("title", upsertedMarker);
            assertThat(upserted).containsEntry("languageId", 1);

            String dbTitle = dsl.select(org.jooq.impl.DSL.field("title", String.class))
                .from(filmTable).where(filmIdCol.eq(filmId)).fetchOne().value1();
            assertThat(dbTitle).isEqualTo(upsertedMarker);
        } finally {
            dsl.deleteFrom(filmTable).where(filmIdCol.eq(filmId)).execute();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void upsertFilm_insertBranch_writesAndReturnsProjectedFilm() {
        // UPSERT-on-novel-row: the supplied filmId does not exist, so the INSERT branch fires.
        // Pick an id well above the Sakila max (~1000) to avoid collisions with other tests.
        // We can't simply omit filmId because the schema marks it non-null; the INSERT branch
        // therefore exercises the user-supplied-PK shape, which is the canonical UPSERT case.
        int filmId = 999_001;
        String marker = "R22-PHASE-5-INSERT-BRANCH-" + java.util.UUID.randomUUID();
        var filmTable = org.jooq.impl.DSL.table("film");
        var filmIdCol = org.jooq.impl.DSL.field("film_id", Integer.class);
        // Pre-clean in case a prior failed run left the row behind.
        dsl.deleteFrom(filmTable).where(filmIdCol.eq(filmId)).execute();
        try {
            Map<String, Object> data = execute("""
                mutation {
                    upsertFilm(in: { filmId: %d, title: "%s", languageId: 1 }) {
                        title
                        languageId
                    }
                }
                """.formatted(filmId, marker));

            Map<String, Object> upserted = (Map<String, Object>) data.get("upsertFilm");
            assertThat(upserted).containsEntry("title", marker);
            assertThat(upserted).containsEntry("languageId", 1);

            String dbTitle = dsl.select(org.jooq.impl.DSL.field("title", String.class))
                .from(filmTable).where(filmIdCol.eq(filmId)).fetchOne().value1();
            assertThat(dbTitle).isEqualTo(marker);
        } finally {
            dsl.deleteFrom(filmTable).where(filmIdCol.eq(filmId)).execute();
        }
    }
}
