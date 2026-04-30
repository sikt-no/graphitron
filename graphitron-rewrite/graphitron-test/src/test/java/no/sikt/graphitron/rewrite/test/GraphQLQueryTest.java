package no.sikt.graphitron.rewrite.test;

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

import static org.assertj.core.api.Assertions.assertThat;
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
        List<Map<String, Object>> customers = (List<Map<String, Object>>) data.get("customers");
        List<Map<String, Object>> films = (List<Map<String, Object>>) data.get("films");

        assertThat(customers).hasSize(5);
        assertThat(customers.get(0)).containsKey("firstName");

        assertThat(films).hasSize(5);
        assertThat(films.get(0)).containsKey("title");
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
        List<Map<String, Object>> customers = (List<Map<String, Object>>) data.get("customers");
        assertThat(customers).hasSize(5);
        // Customers should have firstName and lastName, not title or rating
        assertThat(customers.get(0).keySet()).containsExactlyInAnyOrder("firstName", "lastName");
    }

    // ===== customers query =====

    @Test
    void customers_returnsAllCustomers() {
        Map<String, Object> data = execute("{ customers { customerId firstName lastName } }");
        List<Map<String, Object>> customers = (List<Map<String, Object>>) data.get("customers");
        assertThat(customers).hasSize(5);
    }

    @Test
    void customers_filteredByActive() {
        Map<String, Object> data = execute("{ customers(active: true) { customerId firstName } }");
        List<Map<String, Object>> customers = (List<Map<String, Object>>) data.get("customers");
        assertThat(customers).hasSize(3);
        assertThat(customers).extracting(c -> c.get("firstName"))
            .containsExactlyInAnyOrder("Mary", "Patricia", "Linda");
    }

    // ===== films query =====

    @Test
    void films_returnsAllFilms() {
        Map<String, Object> data = execute("{ films { filmId title } }");
        List<Map<String, Object>> films = (List<Map<String, Object>>) data.get("films");
        assertThat(films).hasSize(5);
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
        List<Map<String, Object>> films = (List<Map<String, Object>>) data.get("films");
        assertThat(films).hasSize(5);
        for (var f : films) {
            String title = (String) f.get("title");
            String titleUppercase = (String) f.get("titleUppercase");
            assertThat(titleUppercase)
                .as("titleUppercase must equal title.toUpperCase() for film '%s'", title)
                .isEqualTo(title.toUpperCase());
        }
    }

    @Test
    void films_isEnglish_resolvesViaExternalFieldExpression() {
        // R48 ComputedField execution-tier fixture: @externalField(reference: ...) inlines
        // FilmExtensions.isEnglish(table) (Field<Boolean>(LANGUAGE_ID = 1)) into Film.$fields().
        // ColumnFetcher reads the projected alias from the result Record at request time.
        // All seeded films have language_id=1, so the expression resolves to true for each.
        Map<String, Object> data = execute("{ films { title isEnglish } }");
        List<Map<String, Object>> films = (List<Map<String, Object>>) data.get("films");
        assertThat(films).hasSize(5);
        assertThat(films).extracting(f -> f.get("isEnglish"))
            .containsOnly(Boolean.TRUE);
        assertThat(films).extracting(f -> f.get("title"))
            .doesNotContainNull();
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
        List<Map<String, Object>> films = (List<Map<String, Object>>) data.get("filmsBySameTableNodeId");
        assertThat(films).extracting(f -> f.get("filmId")).containsExactlyInAnyOrder(2, 4);
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
        List<Map<String, Object>> films = (List<Map<String, Object>>) data.get("filmsBySameTableNodeId");
        assertThat(films).isEmpty();
    }

    @Test
    void films_filteredByRating() {
        // Test data: ACADEMY DINOSAUR=PG, ACE GOLDFINGER=G, ADAPTATION HOLES=NC_17,
        //            AFFAIR PREJUDICE=G, AGENT TRUMAN=PG
        Map<String, Object> data = execute("{ films(rating: G) { title } }");
        List<Map<String, Object>> films = (List<Map<String, Object>>) data.get("films");
        assertThat(films).extracting(f -> f.get("title"))
            .containsExactlyInAnyOrder("ACE GOLDFINGER", "AFFAIR PREJUDICE");
    }

    @Test
    void films_filteredByTextRating() {
        // TextRating enum maps to varchar column via @field(name:) — NC_17 → "NC-17"
        Map<String, Object> data = execute("{ films(textRating: NC_17) { title } }");
        List<Map<String, Object>> films = (List<Map<String, Object>>) data.get("films");
        assertThat(films).extracting(f -> f.get("title"))
            .containsExactly("ADAPTATION HOLES");
    }

    @Test
    void films_filteredByTextRating_simpleValue() {
        // G maps to "G" (no @field mapping needed)
        Map<String, Object> data = execute("{ films(textRating: G) { title } }");
        List<Map<String, Object>> films = (List<Map<String, Object>>) data.get("films");
        assertThat(films).extracting(f -> f.get("title"))
            .containsExactlyInAnyOrder("ACE GOLDFINGER", "AFFAIR PREJUDICE");
    }

    @Test
    void films_orderedByFilmId() {
        Map<String, Object> data = execute("{ films { title } }");
        List<Map<String, Object>> films = (List<Map<String, Object>>) data.get("films");
        assertThat(films).extracting(f -> f.get("title"))
            .containsExactly("ACADEMY DINOSAUR", "ACE GOLDFINGER", "ADAPTATION HOLES",
                "AFFAIR PREJUDICE", "AGENT TRUMAN");
    }

    @Test
    void films_selectsOnlyRequestedFields() {
        // Only request 'title' — should still work even though filmId etc. are not selected
        Map<String, Object> data = execute("{ films { title } }");
        List<Map<String, Object>> films = (List<Map<String, Object>>) data.get("films");
        assertThat(films).isNotEmpty();
        assertThat(films.get(0)).containsKey("title");
    }

    // ===== filmById lookup query =====

    @Test
    void filmById_returnsRequestedFilms() {
        Map<String, Object> data = execute("{ filmById(film_id: [\"1\", \"3\"]) { filmId title } }");
        List<Map<String, Object>> films = (List<Map<String, Object>>) data.get("filmById");
        assertThat(films).hasSize(2);
        // containsExactly (not InAnyOrder) — VALUES+JOIN preserves input order by joining on the
        // derived table's idx column. See docs/argument-resolution.md Phase 1.
        assertThat(films).extracting(f -> f.get("title"))
            .containsExactly("ACADEMY DINOSAUR", "ADAPTATION HOLES");
    }

    @Test
    void filmById_preservesInputOrder() {
        // VALUES+JOIN ordering evidence: request IDs in a non-sorted order and assert output order
        // matches input order. This is the one thing IN/EQ could not do, so it's the
        // behaviour-level proof that the emitter uses ordered VALUES+JOIN.
        Map<String, Object> data = execute("{ filmById(film_id: [\"3\", \"1\", \"2\"]) { filmId title } }");
        List<Map<String, Object>> films = (List<Map<String, Object>>) data.get("filmById");
        assertThat(films).extracting(f -> f.get("filmId"))
            .containsExactly(3, 1, 2);
    }

    @Test
    void filmById_singleId_returnsOneFilm() {
        Map<String, Object> data = execute("{ filmById(film_id: [\"2\"]) { title } }");
        List<Map<String, Object>> films = (List<Map<String, Object>>) data.get("filmById");
        assertThat(films).hasSize(1);
        assertThat(films.get(0).get("title")).isEqualTo("ACE GOLDFINGER");
    }

    // ===== languageByKey lookup query =====

    @Test
    void languageByKey_returnsRequestedLanguages() {
        Map<String, Object> data = execute("{ languageByKey(language_id: [1, 2]) { languageId } }");
        List<Map<String, Object>> langs = (List<Map<String, Object>>) data.get("languageByKey");
        assertThat(langs).hasSize(2);
        assertThat(langs).extracting(l -> l.get("languageId"))
            .containsExactlyInAnyOrder(1, 2);
    }

    @Test
    void languageByKey_singleId_returnsOneLanguage() {
        Map<String, Object> data = execute("{ languageByKey(language_id: [3]) { languageId } }");
        List<Map<String, Object>> langs = (List<Map<String, Object>>) data.get("languageByKey");
        assertThat(langs).hasSize(1);
        assertThat(langs.get(0).get("languageId")).isEqualTo(3);
    }

    // ===== customerById lookup query =====

    @Test
    void customerById_listKeyAndScalarKey_filtersCorrectly() {
        // Customers 1,2,4 are in store 1; 3,5 are in store 2
        Map<String, Object> data = execute(
            "{ customerById(customer_id: [\"1\", \"2\", \"4\", \"3\"], store_id: \"1\") { customerId } }");
        List<Map<String, Object>> customers = (List<Map<String, Object>>) data.get("customerById");
        // Only IDs 1, 2, 4 are in store 1
        assertThat(customers).hasSize(3);
        assertThat(customers).extracting(c -> c.get("customerId"))
            .containsExactlyInAnyOrder(1, 2, 4);
    }

    @Test
    void customerById_noMatchForStore_returnsEmpty() {
        // Customer 3 is in store 2, requesting store 1 → no match
        Map<String, Object> data = execute(
            "{ customerById(customer_id: [\"3\"], store_id: \"1\") { customerId } }");
        List<Map<String, Object>> customers = (List<Map<String, Object>>) data.get("customerById");
        assertThat(customers).isEmpty();
    }

    // ===== filmsConnection — forward pagination =====

    @Test
    void filmsConnection_firstPage_returnsFirstNFilms() {
        Map<String, Object> data = execute(
            "{ filmsConnection(first: 2) { nodes { title } pageInfo { hasNextPage hasPreviousPage } } }");
        var conn = (Map<String, Object>) data.get("filmsConnection");
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) conn.get("nodes");
        assertThat(nodes).hasSize(2);
        assertThat(nodes).extracting(n -> n.get("title"))
            .containsExactly("ACADEMY DINOSAUR", "ACE GOLDFINGER");
        var pageInfo = (Map<String, Object>) conn.get("pageInfo");
        assertThat(pageInfo.get("hasNextPage")).isEqualTo(true);
        assertThat(pageInfo.get("hasPreviousPage")).isEqualTo(false);
    }

    @Test
    void filmsConnection_defaultPageSize_returnsUpToDefault() {
        // Default page size is 100; test DB has 5 films, so all 5 are returned
        Map<String, Object> data = execute(
            "{ filmsConnection { nodes { filmId } } }");
        var conn = (Map<String, Object>) data.get("filmsConnection");
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) conn.get("nodes");
        assertThat(nodes).hasSize(5);
    }

    @Test
    void filmsConnection_withAfterCursor_returnsNextPage() {
        // Get page 1 cursor, then use it to get page 2
        Map<String, Object> page1Data = execute(
            "{ filmsConnection(first: 2) { edges { cursor node { title } } pageInfo { endCursor } } }");
        var conn1 = (Map<String, Object>) page1Data.get("filmsConnection");
        var pageInfo1 = (Map<String, Object>) conn1.get("pageInfo");
        String endCursor = (String) pageInfo1.get("endCursor");
        assertThat(endCursor).isNotNull();

        Map<String, Object> page2Data = execute(
            "{ filmsConnection(first: 2, after: \"" + endCursor + "\") { nodes { title } pageInfo { hasNextPage } } }");
        var conn2 = (Map<String, Object>) page2Data.get("filmsConnection");
        List<Map<String, Object>> nodes2 = (List<Map<String, Object>>) conn2.get("nodes");
        assertThat(nodes2).extracting(n -> n.get("title"))
            .containsExactly("ADAPTATION HOLES", "AFFAIR PREJUDICE");
        var pageInfo2 = (Map<String, Object>) conn2.get("pageInfo");
        assertThat(pageInfo2.get("hasNextPage")).isEqualTo(true);
    }

    @Test
    void filmsConnection_lastPage_hasNextPageFalse() {
        Map<String, Object> data = execute(
            "{ filmsConnection(first: 5) { nodes { title } pageInfo { hasNextPage } } }");
        var conn = (Map<String, Object>) data.get("filmsConnection");
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) conn.get("nodes");
        assertThat(nodes).hasSize(5);
        var pageInfo = (Map<String, Object>) conn.get("pageInfo");
        assertThat(pageInfo.get("hasNextPage")).isEqualTo(false);
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
        var conn = (Map<String, Object>) data.get("filmsConnection");
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) conn.get("nodes");
        assertThat(nodes).hasSize(2);
        // last 2 in ascending film_id order: AFFAIR PREJUDICE, AGENT TRUMAN
        assertThat(nodes).extracting(n -> n.get("title"))
            .containsExactly("AFFAIR PREJUDICE", "AGENT TRUMAN");
        var pageInfo = (Map<String, Object>) conn.get("pageInfo");
        assertThat(pageInfo.get("hasPreviousPage")).isEqualTo(true);
        assertThat(pageInfo.get("hasNextPage")).isEqualTo(false);
    }

    @Test
    void filmsConnection_backward_withBeforeCursor_returnsPrevPage() {
        // First get the last page to obtain a before cursor (startCursor of last page)
        Map<String, Object> lastPageData = execute(
            "{ filmsConnection(last: 2) { nodes { title } pageInfo { startCursor } } }");
        var lastConn = (Map<String, Object>) lastPageData.get("filmsConnection");
        var lastPageInfo = (Map<String, Object>) lastConn.get("pageInfo");
        String startCursor = (String) lastPageInfo.get("startCursor");
        assertThat(startCursor).isNotNull();

        // Paginate backwards before that cursor
        Map<String, Object> prevPageData = execute(
            "{ filmsConnection(last: 2, before: \"" + startCursor + "\") { nodes { title } } }");
        var prevConn = (Map<String, Object>) prevPageData.get("filmsConnection");
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) prevConn.get("nodes");
        // last: 2 returns [AFFAIR PREJUDICE (4), AGENT TRUMAN (5)]; startCursor = cursor(4).
        // "2 items before cursor(4)" in ascending order = items 2, 3: ACE GOLDFINGER, ADAPTATION HOLES.
        assertThat(nodes).extracting(n -> n.get("title"))
            .containsExactly("ACE GOLDFINGER", "ADAPTATION HOLES");
    }

    // ===== filmsConnection / stores — totalCount =====

    @Test
    void filmsConnection_totalCount_returnsTotalRowCount() {
        // Structural FilmsConnection declares totalCount: Int; root fetcher binds (table, condition)
        // onto ConnectionResult so ConnectionHelper.totalCount issues SELECT count(*) on demand.
        Map<String, Object> data = execute(
            "{ filmsConnection { totalCount } }");
        var conn = (Map<String, Object>) data.get("filmsConnection");
        assertThat(conn.get("totalCount")).isEqualTo(5);
    }

    @Test
    void filmsOrderedConnection_totalCount_underFilter_appliesSamePredicate() {
        // SELECT count(*) is run with the same Condition the page query used; rating=G filter
        // restricts the count to the 2 G-rated films in the seed data.
        Map<String, Object> data = execute(
            "{ filmsOrderedConnection(rating: G, first: 1) { totalCount nodes { title } } }");
        var conn = (Map<String, Object>) data.get("filmsOrderedConnection");
        assertThat(conn.get("totalCount")).isEqualTo(2);
        var nodes = (List<Map<String, Object>>) conn.get("nodes");
        assertThat(nodes).hasSize(1); // page-trimmed by first:1
    }

    @Test
    void synthesisedConnection_totalCount_returnsRowCount() {
        // `stores: [Store!]! @asConnection` synthesises QueryStoresConnection which always carries
        // totalCount: Int. Two stores are seeded.
        Map<String, Object> data = execute(
            "{ stores { totalCount } }");
        var conn = (Map<String, Object>) data.get("stores");
        assertThat(conn.get("totalCount")).isEqualTo(2);
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
        var conn = (Map<String, Object>) data.get("filmsOrderedConnection");
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) conn.get("nodes");
        assertThat(nodes).hasSize(2);
        assertThat(nodes).extracting(n -> n.get("title"))
            .containsExactly("ACADEMY DINOSAUR", "ACE GOLDFINGER");
    }

    @Test
    void filmsOrderedConnection_orderByTitle_paginatesAlphabetically() {
        Map<String, Object> data = execute(
            "{ filmsOrderedConnection(order: [{field: TITLE, direction: ASC}], first: 3) { nodes { title } } }");
        var conn = (Map<String, Object>) data.get("filmsOrderedConnection");
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) conn.get("nodes");
        assertThat(nodes).hasSize(3);
        // ACADEMY DINOSAUR < ACE GOLDFINGER < ADAPTATION HOLES alphabetically
        assertThat(nodes).extracting(n -> n.get("title"))
            .containsExactly("ACADEMY DINOSAUR", "ACE GOLDFINGER", "ADAPTATION HOLES");
    }

    @Test
    void filmsOrderedConnection_filterPlusOrderPlusPagination_combinesAllThree() {
        // Exercises buildFilters + buildOrderBySpec + buildPaginationSpec on one field.
        // Seed data: two G-rated films — ACE GOLDFINGER, AFFAIR PREJUDICE.
        Map<String, Object> data = execute(
            "{ filmsOrderedConnection(rating: G, order: [{field: TITLE, direction: ASC}], first: 1) { " +
            "nodes { title } pageInfo { hasNextPage } } }");
        var conn = (Map<String, Object>) data.get("filmsOrderedConnection");
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) conn.get("nodes");
        var pageInfo = (Map<String, Object>) conn.get("pageInfo");
        assertThat(nodes).extracting(n -> n.get("title")).containsExactly("ACE GOLDFINGER");
        assertThat(pageInfo.get("hasNextPage")).isEqualTo(true);
    }

    @Test
    void filmsOrderedConnection_orderByTitle_cursorNavigation() {
        // Get page 1 ordered by title, then follow cursor
        Map<String, Object> page1Data = execute(
            "{ filmsOrderedConnection(order: [{field: TITLE, direction: ASC}], first: 2) { " +
            "nodes { title } pageInfo { endCursor hasNextPage } } }");
        var conn1 = (Map<String, Object>) page1Data.get("filmsOrderedConnection");
        var pageInfo1 = (Map<String, Object>) conn1.get("pageInfo");
        String endCursor = (String) pageInfo1.get("endCursor");
        assertThat(endCursor).isNotNull();
        assertThat(pageInfo1.get("hasNextPage")).isEqualTo(true);

        Map<String, Object> page2Data = execute(
            "{ filmsOrderedConnection(order: [{field: TITLE, direction: ASC}], first: 2, after: \"" +
            endCursor + "\") { nodes { title } } }");
        var conn2 = (Map<String, Object>) page2Data.get("filmsOrderedConnection");
        List<Map<String, Object>> nodes2 = (List<Map<String, Object>>) conn2.get("nodes");
        assertThat(nodes2).extracting(n -> n.get("title"))
            .containsExactly("ADAPTATION HOLES", "AFFAIR PREJUDICE");
    }

    // ===== G5 inline TableField — single-hop FK =====

    @Test
    void inlineTableField_singleHopFk_returnsNestedRecord() {
        // Customer 1 is in store 1, with address_id=1 → '47 MySakila Drive'
        Map<String, Object> data = execute(
            "{ customerById(customer_id: [\"1\"], store_id: \"1\") { customerId address { addressId address } } }");
        List<Map<String, Object>> customers = (List<Map<String, Object>>) data.get("customerById");
        assertThat(customers).hasSize(1);
        var address = (Map<String, Object>) customers.get(0).get("address");
        assertThat(address).isNotNull();
        assertThat(address.get("addressId")).isEqualTo(1);
        assertThat(address.get("address")).isEqualTo("47 MySakila Drive");
    }

    // ===== G5 inline TableField — multi-hop FK =====

    @Test
    void inlineTableField_multiHopFk_walksTwoFkHops() {
        // customer 1, store 1, address 1 '47 MySakila Drive'; customer 2, store 1, address 2.
        Map<String, Object> data = execute(
            "{ customerById(customer_id: [\"1\", \"2\"], store_id: \"1\") { customerId storeAddress { address } } }");
        List<Map<String, Object>> customers = (List<Map<String, Object>>) data.get("customerById");
        assertThat(customers).hasSize(2);

        var customer1 = customers.stream().filter(c -> ((Integer) c.get("customerId")) == 1).findFirst().orElseThrow();
        assertThat(((Map<String, Object>) customer1.get("storeAddress")).get("address"))
            .isEqualTo("47 MySakila Drive");
    }

    // ===== G5 inline TableField — single-hop FK, list cardinality =====

    @Test
    void inlineTableField_listCardinality_returnsAllChildren() {
        // Store 1 holds customers 1, 2, 4. Store 2 holds 3, 5. Order by customer_id (PK).
        Map<String, Object> data = execute(
            "{ storeById(store_id: [1]) { storeId customers { customerId firstName } } }");
        List<Map<String, Object>> stores = (List<Map<String, Object>>) data.get("storeById");
        assertThat(stores).hasSize(1);

        List<Map<String, Object>> customers = (List<Map<String, Object>>) stores.get(0).get("customers");
        assertThat(customers).extracting(c -> c.get("customerId"))
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
        List<Map<String, Object>> cats = (List<Map<String, Object>>) data.get("categoryById");
        assertThat(cats).hasSize(1);
        assertThat(cats.get(0).get("name")).isEqualTo("Thriller");

        var parent = (Map<String, Object>) cats.get(0).get("parent");
        assertThat(parent.get("name")).isEqualTo("Action");

        var grandparent = (Map<String, Object>) parent.get("parent");
        assertThat(grandparent.get("name")).isEqualTo("Genre");
    }

    @Test
    void inlineTableField_selfRef_listCardinality_returnsChildren() {
        // Genre (id=1) has children: Action, Animation, Comedy (ids 2, 3, 4).
        Map<String, Object> data = execute(
            "{ categoryById(category_id: [1]) { name children { name } } }");
        List<Map<String, Object>> cats = (List<Map<String, Object>>) data.get("categoryById");
        assertThat(cats).hasSize(1);
        assertThat(cats.get(0).get("name")).isEqualTo("Genre");

        List<Map<String, Object>> children = (List<Map<String, Object>>) cats.get(0).get("children");
        assertThat(children).extracting(c -> c.get("name"))
            .containsExactly("Action", "Animation", "Comedy");
    }

    @Test
    void inlineTableField_selfRef_nonRootCategory_hasNoChildren() {
        // Thriller (id=5) is a leaf — children list is empty.
        Map<String, Object> data = execute(
            "{ categoryById(category_id: [5]) { name children { name } } }");
        List<Map<String, Object>> cats = (List<Map<String, Object>>) data.get("categoryById");
        List<Map<String, Object>> children = (List<Map<String, Object>>) cats.get(0).get("children");
        assertThat(children).isEmpty();
    }

    @Test
    void inlineTableField_selfRef_optionalParent_nullable() {
        // Genre (id=1) has no parent — parent is null.
        Map<String, Object> data = execute(
            "{ categoryById(category_id: [1]) { name parent { name } } }");
        List<Map<String, Object>> cats = (List<Map<String, Object>>) data.get("categoryById");
        assertThat(cats.get(0).get("parent")).isNull();
    }

    // ===== argres Phase 2a — inline LookupTableField (Film.actors via film_actor junction) =====

    @Test
    void inlineLookupTableField_returnsMatchingActors() {
        // Film 1 (ACADEMY DINOSAUR) cast: PENELOPE (id=1), NICK (id=2).
        Map<String, Object> data = execute(
            "{ filmById(film_id: [\"1\"]) { actors(actor_id: [1, 2]) { actorId firstName } } }");
        List<Map<String, Object>> films = (List<Map<String, Object>>) data.get("filmById");
        List<Map<String, Object>> actors = (List<Map<String, Object>>) films.get(0).get("actors");
        assertThat(actors).extracting(a -> a.get("firstName"))
            .containsExactlyInAnyOrder("PENELOPE", "NICK");
    }

    @Test
    void inlineLookupTableField_preservesInputOrder() {
        // Input [2, 1] should return NICK before PENELOPE.
        Map<String, Object> data = execute(
            "{ filmById(film_id: [\"1\"]) { actors(actor_id: [2, 1]) { firstName } } }");
        List<Map<String, Object>> films = (List<Map<String, Object>>) data.get("filmById");
        List<Map<String, Object>> actors = (List<Map<String, Object>>) films.get(0).get("actors");
        assertThat(actors).extracting(a -> a.get("firstName"))
            .containsExactly("NICK", "PENELOPE");
    }

    @Test
    void inlineLookupTableField_fkFilter_excludesActorsNotInFilm() {
        // Film 1 cast: actors 1, 2. Actor 3 (ED) is not in film 1 — the FK chain drops him.
        Map<String, Object> data = execute(
            "{ filmById(film_id: [\"1\"]) { actors(actor_id: [1, 3]) { firstName } } }");
        List<Map<String, Object>> films = (List<Map<String, Object>>) data.get("filmById");
        List<Map<String, Object>> actors = (List<Map<String, Object>>) films.get(0).get("actors");
        assertThat(actors).extracting(a -> a.get("firstName")).containsExactly("PENELOPE");
    }

    @Test
    void inlineLookupTableField_emptyInput_returnsEmpty() {
        Map<String, Object> data = execute(
            "{ filmById(film_id: [\"1\"]) { actors(actor_id: []) { firstName } } }");
        List<Map<String, Object>> films = (List<Map<String, Object>>) data.get("filmById");
        List<Map<String, Object>> actors = (List<Map<String, Object>>) films.get(0).get("actors");
        assertThat(actors).isEmpty();
    }

    @Test
    void inlineLookupTableField_nullInput_returnsEmpty() {
        // actor_id is optional; omitting it should short-circuit to an empty list (n=0 path).
        Map<String, Object> data = execute(
            "{ filmById(film_id: [\"1\"]) { actors { firstName } } }");
        List<Map<String, Object>> films = (List<Map<String, Object>>) data.get("filmById");
        List<Map<String, Object>> actors = (List<Map<String, Object>>) films.get(0).get("actors");
        assertThat(actors).isEmpty();
    }

    @Test
    void inlineLookupTableField_acrossMultipleParents_perFilmFiltering() {
        // Film 2 (ACE GOLDFINGER) cast: PENELOPE (1), ED (3). Film 3 cast: PENELOPE only.
        // Same input [1, 3] on both → film 2 has both; film 3 has only PENELOPE.
        Map<String, Object> data = execute(
            "{ filmById(film_id: [\"2\", \"3\"]) { filmId actors(actor_id: [1, 3]) { firstName } } }");
        List<Map<String, Object>> films = (List<Map<String, Object>>) data.get("filmById");
        assertThat(films).hasSize(2);

        var film2 = films.get(0);
        assertThat(film2.get("filmId")).isEqualTo(2);
        var film2Actors = (List<Map<String, Object>>) film2.get("actors");
        assertThat(film2Actors).extracting(a -> a.get("firstName"))
            .containsExactlyInAnyOrder("PENELOPE", "ED");

        var film3 = films.get(1);
        assertThat(film3.get("filmId")).isEqualTo(3);
        var film3Actors = (List<Map<String, Object>>) film3.get("actors");
        assertThat(film3Actors).extracting(a -> a.get("firstName")).containsExactly("PENELOPE");
    }

    // ===== argres Phase 2b: Split(Lookup)TableField DataLoader fan-out =====

    @Test
    void splitTableField_singleParent_returnsItsChildren() {
        // Language.films (SplitTableField) — language 1 has films 1-5 seeded.
        Map<String, Object> data = execute(
            "{ languageByKey(language_id: [1]) { languageId films { filmId } } }");
        List<Map<String, Object>> langs = (List<Map<String, Object>>) data.get("languageByKey");
        assertThat(langs).hasSize(1);
        List<Map<String, Object>> films = (List<Map<String, Object>>) langs.get(0).get("films");
        assertThat(films).extracting(f -> f.get("filmId"))
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

        List<Map<String, Object>> langs = (List<Map<String, Object>>) data.get("languageByKey");
        assertThat(langs).hasSize(3);

        var byId = langs.stream().collect(java.util.stream.Collectors.toMap(
            l -> (Integer) l.get("languageId"), l -> l));
        assertThat((List<?>) byId.get(1).get("films")).hasSize(5);
        assertThat((List<?>) byId.get(2).get("films")).isEmpty();
        assertThat((List<?>) byId.get(3).get("films")).isEmpty();
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
        List<Map<String, Object>> langs = (List<Map<String, Object>>) data.get("languageByKey");
        assertThat(langs).extracting(l -> l.get("languageId")).containsExactly(3, 1, 2);
        assertThat((List<?>) langs.get(0).get("films")).isEmpty();
        assertThat((List<?>) langs.get(1).get("films")).hasSize(5);
        assertThat((List<?>) langs.get(2).get("films")).isEmpty();
    }

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
        List<Map<String, Object>> films = (List<Map<String, Object>>) data.get("films");

        assertThat(films).allSatisfy(f ->
            assertThat((List<?>) f.get("actorsBySplitLookup")).isEmpty());
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
        List<Map<String, Object>> films = (List<Map<String, Object>>) data.get("films");

        assertThat(films).allSatisfy(f ->
            assertThat((List<?>) f.get("actorsBySplitLookup")).isEmpty());
    }

    // ===== single-cardinality @splitQuery =====

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
        List<Map<String, Object>> stores = (List<Map<String, Object>>) data.get("storeById");
        assertThat(stores).hasSize(1);
        assertThat(stores.get(0).get("storeId")).isEqualTo(2);
        assertThat(stores.get(0).get("manager")).isNull();
    }

    @Test
    void splitTableField_singleCardinality_nonNullFk_resolvesManager() {
        // Store 1 has manager_staff_id = 1 → Mike Hillyer. Covers the happy path for
        // Store.manager alongside the null-FK test above.
        Map<String, Object> data = execute(
            "{ storeById(store_id: [1]) { storeId manager { staffId firstName } } }");
        List<Map<String, Object>> stores = (List<Map<String, Object>>) data.get("storeById");
        assertThat(stores).hasSize(1);
        Map<String, Object> manager = (Map<String, Object>) stores.get(0).get("manager");
        assertThat(manager).isNotNull();
        assertThat(manager.get("staffId")).isEqualTo(1);
        assertThat(manager.get("firstName")).isEqualTo("Mike");
    }

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
        List<Map<String, Object>> rows = (List<Map<String, Object>>) data.get("filmActorsByKey");
        assertThat(rows).hasSize(2);
        assertThat(rows).extracting(r -> r.get("filmId") + ":" + r.get("actorId"))
            .containsExactly("1:2", "2:3");
    }

    @Test
    void compositeKeyLookup_preservesInputOrder() {
        // VALUES+JOIN preserves input order via the derived table's idx column, even for composite
        // keys. Reverse the input to prove ordering is not coincidentally PK-sorted.
        Map<String, Object> data = execute(
            "{ filmActorsByKey(key: [{filmId: 3, actorId: 1}, {filmId: 1, actorId: 2}, {filmId: 2, actorId: 1}]) { filmId actorId } }");
        List<Map<String, Object>> rows = (List<Map<String, Object>>) data.get("filmActorsByKey");
        assertThat(rows).extracting(r -> r.get("filmId") + ":" + r.get("actorId"))
            .containsExactly("3:1", "1:2", "2:1");
    }

    @Test
    void compositeKeyLookup_mismatchedPairExcluded() {
        // (film 4, actor 1) is NOT a row in film_actor (film 4's cast is actor 2 only). Both
        // film 4 and actor 1 exist individually, but the composite JOIN rejects the pair.
        // (film 1, actor 1) is a real pair → returned.
        Map<String, Object> data = execute(
            "{ filmActorsByKey(key: [{filmId: 4, actorId: 1}, {filmId: 1, actorId: 1}]) { filmId actorId } }");
        List<Map<String, Object>> rows = (List<Map<String, Object>>) data.get("filmActorsByKey");
        assertThat(rows).hasSize(1);
        assertThat(rows.get(0).get("filmId")).isEqualTo(1);
        assertThat(rows.get(0).get("actorId")).isEqualTo(1);
    }

    @Test
    void compositeKeyLookup_emptyInput_returnsEmpty() {
        Map<String, Object> data = execute(
            "{ filmActorsByKey(key: []) { filmId actorId } }");
        List<Map<String, Object>> rows = (List<Map<String, Object>>) data.get("filmActorsByKey");
        assertThat(rows).isEmpty();
    }

    // ===== C4: RecordTableField — @record parent + DataLoader language batch =====

    @Test
    void recordTableField_singleFilm_returnsLanguage() {
        // Film 1 (ACADEMY DINOSAUR) has language_id=1 (English).
        // filmDetails is a ConstructorField pass-through; language is a RecordTableField DataLoader.
        Map<String, Object> data = execute(
            "{ filmById(film_id: [\"1\"]) { languageId filmDetails { title language { name } } } }");
        List<Map<String, Object>> films = (List<Map<String, Object>>) data.get("filmById");
        assertThat(films).hasSize(1);
        var details = (Map<String, Object>) films.get(0).get("filmDetails");
        assertThat(details.get("title")).isEqualTo("ACADEMY DINOSAUR");
        List<Map<String, Object>> langs = (List<Map<String, Object>>) details.get("language");
        assertThat(langs).hasSize(1);
        assertThat(langs.get(0).get("name").toString().trim()).isEqualTo("English");
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
        List<Map<String, Object>> films = (List<Map<String, Object>>) data.get("films");
        assertThat(films).hasSize(5);
        // Every film maps to English (language_id=1 for all test-data films).
        assertThat(films).allSatisfy(f -> {
            var details = (Map<String, Object>) f.get("filmDetails");
            List<Map<String, Object>> langs = (List<Map<String, Object>>) details.get("language");
            assertThat(langs).hasSize(1);
            assertThat(langs.get(0).get("name").toString().trim()).isEqualTo("English");
        });
    }

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
        List<Map<String, Object>> films = (List<Map<String, Object>>) data.get("filmById");
        var details = (Map<String, Object>) films.get(0).get("filmDetails");
        List<Map<String, Object>> actors = (List<Map<String, Object>>) details.get("actorsByLookup");
        assertThat(actors).extracting(a -> a.get("firstName"))
            .containsExactlyInAnyOrder("PENELOPE", "NICK");
    }

    @Test
    void recordLookupTableField_fkFilter_excludesActorsNotInFilm() {
        // actor_id: [1, 3] on Film 1 → actor 3 (ED) is not in film 1's cast; FK chain drops him.
        Map<String, Object> data = execute(
            "{ filmById(film_id: [\"1\"]) { filmDetails { actorsByLookup(actor_id: [1, 3]) { firstName } } } }");
        List<Map<String, Object>> films = (List<Map<String, Object>>) data.get("filmById");
        var details = (Map<String, Object>) films.get(0).get("filmDetails");
        List<Map<String, Object>> actors = (List<Map<String, Object>>) details.get("actorsByLookup");
        assertThat(actors).extracting(a -> a.get("firstName")).containsExactly("PENELOPE");
    }

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
        List<Map<String, Object>> films = (List<Map<String, Object>>) data.get("films");

        assertThat(films).allSatisfy(f -> {
            var details = (Map<String, Object>) f.get("filmDetails");
            assertThat((List<?>) details.get("actorsByLookup")).isEmpty();
        });
    }

    @Test
    void recordLookupTableField_nullLookupInput_shortCircuitsNoChildQuery() {
        // Omitting @lookupKey arg → null → rowCount=0 path → emptyScatter short-circuit.
        QUERY_COUNT.set(0);
        Map<String, Object> data = execute(
            "{ films { filmId filmDetails { actorsByLookup { actorId } } } }");
        assertThat(QUERY_COUNT.get()).isEqualTo(1);
        List<Map<String, Object>> films = (List<Map<String, Object>>) data.get("films");

        assertThat(films).allSatisfy(f -> {
            var details = (Map<String, Object>) f.get("filmDetails");
            assertThat((List<?>) details.get("actorsByLookup")).isEmpty();
        });
    }

    // ===== NestingField — plain-object nested types =====

    @Test
    void nestingField_nestedScalars_returnCorrectValues() {
        // Film 1 (ACADEMY DINOSAUR) was seeded with release_year 2006.
        Map<String, Object> data = execute(
            "{ filmById(film_id: [\"1\"]) { summary { title releaseYear } } }");
        List<Map<String, Object>> films = (List<Map<String, Object>>) data.get("filmById");
        var summary = (Map<String, Object>) films.get(0).get("summary");
        assertThat(summary).containsEntry("title", "ACADEMY DINOSAUR");
        assertThat(summary).containsEntry("releaseYear", 2006);
    }

    @Test
    void nestingField_fieldNameRemap_resolvesToParentColumn() {
        // FilmSummary.originalTitle @field(name: "TITLE") resolves to FILM.TITLE despite
        // the distinct GraphQL field name. Locks the @field(name:) remap at nested depth.
        Map<String, Object> data = execute(
            "{ filmById(film_id: [\"1\"]) { summary { originalTitle } } }");
        var films = (List<Map<String, Object>>) data.get("filmById");
        var summary = (Map<String, Object>) films.get(0).get("summary");
        assertThat(summary).containsEntry("originalTitle", "ACADEMY DINOSAUR");
    }

    @Test
    void nestingField_multiLevelNesting_resolvesThroughTransparentNesting() {
        // Film.info.meta.title + Film.info.meta.length both resolve against the outer
        // Film table. ACADEMY DINOSAUR / 86 minutes in the seed data.
        Map<String, Object> data = execute(
            "{ filmById(film_id: [\"1\"]) { info { releaseYear meta { title length } } } }");
        var films = (List<Map<String, Object>>) data.get("filmById");
        var info = (Map<String, Object>) films.get(0).get("info");
        assertThat(info).containsEntry("releaseYear", 2006);
        var meta = (Map<String, Object>) info.get("meta");
        assertThat(meta).containsEntry("title", "ACADEMY DINOSAUR");
        assertThat(meta).containsEntry("length", 86);
    }

    @Test
    void nestingField_onlyRequestedColumnsSelected() {
        // Requesting only summary.title must project FILM.TITLE — not releaseYear —
        // since $fields is selection-aware. One round-trip; correct value returned.
        QUERY_COUNT.set(0);
        Map<String, Object> data = execute(
            "{ filmById(film_id: [\"1\"]) { summary { title } } }");
        assertThat(QUERY_COUNT.get()).isEqualTo(1);
        var films = (List<Map<String, Object>>) data.get("filmById");
        var summary = (Map<String, Object>) films.get(0).get("summary");
        assertThat(summary).containsOnlyKeys("title");
        assertThat(summary).containsEntry("title", "ACADEMY DINOSAUR");
    }

    @Test
    void nestingField_outerListOfFilms_nestedResolvesPerRow() {
        // Requesting summary across the films root list: each row carries its own
        // FilmSummary projection, including the per-row release_year.
        Map<String, Object> data = execute("{ films { filmId summary { releaseYear } } }");
        var films = (List<Map<String, Object>>) data.get("films");
        assertThat(films).allSatisfy(f -> {
            var summary = (Map<String, Object>) f.get("summary");
            assertThat(summary).containsKey("releaseYear");
            // All seeded films carry release_year 2006
            assertThat(summary).containsEntry("releaseYear", 2006);
        });
    }

    @Test
    void nestingField_siblingOfTableFields_doesNotDisrupt() {
        // Nesting field projects via $fields on the same outer row as sibling column
        // fields; both must come back correctly on the same query.
        Map<String, Object> data = execute(
            "{ filmById(film_id: [\"1\"]) { filmId title summary { releaseYear } } }");
        var films = (List<Map<String, Object>>) data.get("filmById");
        assertThat(films.get(0)).containsEntry("filmId", 1);
        assertThat(films.get(0)).containsEntry("title", "ACADEMY DINOSAUR");
        var summary = (Map<String, Object>) films.get(0).get("summary");
        assertThat(summary).containsEntry("releaseYear", 2006);
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
        var films = (List<Map<String, Object>>) data.get("filmById");
        var bundle = (Map<String, Object>) films.get(0).get("inlineBundle");
        var language = (Map<String, Object>) bundle.get("language");
        assertThat(language.get("languageId")).isEqualTo(1);
        assertThat(language.get("name").toString().trim()).isEqualTo("English");
    }

    @Test
    void nestingField_withNestedInlineLookupTableField_appliesLookupKey() {
        // Film.inlineBundle.actorsByKey is an inline LookupTableField nested inside a
        // NestingField. Exercises the sf1-threaded path through InlineLookupTableFieldEmitter
        // (inputRows call, empty-input short-circuit, buildInnerSelect all use sfName).
        Map<String, Object> data = execute(
            "{ filmById(film_id: [\"1\"]) { inlineBundle { actorsByKey(actor_id: [1, 2]) { actorId firstName } } } }");
        var films = (List<Map<String, Object>>) data.get("filmById");
        var bundle = (Map<String, Object>) films.get(0).get("inlineBundle");
        var actors = (List<Map<String, Object>>) bundle.get("actorsByKey");
        assertThat(actors).extracting(a -> a.get("firstName"))
            .containsExactlyInAnyOrder("PENELOPE", "NICK");
    }

    @Test
    void nestingField_withNestedInlineLookupTableField_emptyInputShortCircuit() {
        // Empty @lookupKey list hits the rows.length == 0 short-circuit branch in
        // InlineLookupTableFieldEmitter — which also uses sfName in the falseCondition
        // multiset. Parent row still carries the slot, populated as an empty list.
        Map<String, Object> data = execute(
            "{ filmById(film_id: [\"1\"]) { inlineBundle { actorsByKey(actor_id: []) { actorId } } } }");
        var films = (List<Map<String, Object>>) data.get("filmById");
        var bundle = (Map<String, Object>) films.get(0).get("inlineBundle");
        assertThat((List<?>) bundle.get("actorsByKey")).isEmpty();
    }

    // ===== Film.actorsConnection — @splitQuery + @asConnection (plan-split-query-connection §1) =====

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
        var conn = (Map<String, Object>) data.get("stores");
        List<Map<String, Object>> edges = (List<Map<String, Object>>) conn.get("edges");
        assertThat(edges).hasSize(1);
        assertThat(edges.get(0)).containsKey("cursor");
        assertThat(((Map<String, Object>) edges.get(0).get("node")).get("storeId")).isNotNull();
        var pageInfo = (Map<String, Object>) conn.get("pageInfo");
        assertThat(pageInfo.get("hasNextPage")).isEqualTo(true);
        assertThat(pageInfo.get("hasPreviousPage")).isEqualTo(false);
    }

    // ===== Query.node — Relay Global Object Identification =====

    @Test
    void node_customerById_roundTripsThroughDispatcher() {
        String id = no.sikt.graphitron.generated.util.NodeIdEncoder.encode("Customer", 1);
        Map<String, Object> data = execute(
            "{ node(id: \"" + id + "\") { __typename ... on Customer { firstName lastName } } }");
        var node = (Map<String, Object>) data.get("node");
        assertThat(node).isNotNull();
        assertThat(node.get("__typename")).isEqualTo("Customer");
        assertThat(node.get("firstName")).isEqualTo("Mary");
        assertThat(node.get("lastName")).isEqualTo("Smith");
    }

    @Test
    void node_filmById_dispatchesByTypeIdPrefix() {
        // Different NodeType (Film) — verifies the dispatcher routes by typeId prefix.
        String id = no.sikt.graphitron.generated.util.NodeIdEncoder.encode("Film", 1);
        Map<String, Object> data = execute(
            "{ node(id: \"" + id + "\") { __typename ... on Film { title } } }");
        var node = (Map<String, Object>) data.get("node");
        assertThat(node).isNotNull();
        assertThat(node.get("__typename")).isEqualTo("Film");
        assertThat(node.get("title")).isEqualTo("ACADEMY DINOSAUR");
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
        List<Map<String, Object>> customers = (List<Map<String, Object>>) data.get("customers");
        assertThat(customers).isNotEmpty();
        String addressNodeId = (String) customers.get(0).get("addressNodeId");
        assertThat(addressNodeId).isNotNull();

        Map<String, Object> nodeData = execute(
            "{ node(id: \"" + addressNodeId + "\") { __typename ... on Address { addressId district } } }");
        var node = (Map<String, Object>) nodeData.get("node");
        assertThat(node).isNotNull();
        assertThat(node.get("__typename")).isEqualTo("Address");
        assertThat(node.get("addressId")).isNotNull();
        assertThat(node.get("district")).isNotNull();
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
        var node = (Map<String, Object>) data.get("node");
        assertThat(node).isNotNull();
        assertThat(node.get("__typename")).isEqualTo("Customer");
        assertThat(node.get("firstName")).isEqualTo("Mary");
        assertThat(node.get("lastName")).isEqualTo("Smith");
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
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) data.get("nodes");
        assertThat(nodes).hasSize(3);
        assertThat(nodes.get(0).get("__typename")).isEqualTo("Customer");
        assertThat(nodes.get(0).get("firstName")).isEqualTo("Mary");
        assertThat(nodes.get(1).get("__typename")).isEqualTo("Film");
        assertThat(nodes.get(1).get("title")).isEqualTo("ACADEMY DINOSAUR");
        assertThat(nodes.get(2).get("__typename")).isEqualTo("Customer");
        assertThat(nodes.get(2).get("firstName")).isEqualTo("Patricia");
    }

    @Test
    void nodes_garbageId_returnsNullEntry() {
        // Malformed base64 — null at that position, others still resolve.
        String c1 = no.sikt.graphitron.generated.util.NodeIdEncoder.encode("Customer", 1);
        Map<String, Object> data = execute(
            "{ nodes(ids: [\"" + c1 + "\", \"not-a-valid-base64-id\"]) {"
            + " __typename ... on Customer { firstName } } }");
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) data.get("nodes");
        assertThat(nodes).hasSize(2);
        assertThat(nodes.get(0).get("firstName")).isEqualTo("Mary");
        assertThat(nodes.get(1)).isNull();
    }

    @Test
    void nodes_unknownTypeId_returnsNullEntry() {
        // typeId prefix no NodeType claims — null at that position (Relay spec).
        String unknown = no.sikt.graphitron.generated.util.NodeIdEncoder.encode("NotARegisteredType", "1");
        String c1 = no.sikt.graphitron.generated.util.NodeIdEncoder.encode("Customer", 1);
        Map<String, Object> data = execute(
            "{ nodes(ids: [\"" + unknown + "\", \"" + c1 + "\"]) {"
            + " __typename ... on Customer { firstName } } }");
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) data.get("nodes");
        assertThat(nodes).hasSize(2);
        assertThat(nodes.get(0)).isNull();
        assertThat(nodes.get(1).get("firstName")).isEqualTo("Mary");
    }

    @Test
    void nodes_validPrefixNoSuchRow_returnsNullEntry() {
        // Registered typeId but the row doesn't exist — null at that position.
        String missing = no.sikt.graphitron.generated.util.NodeIdEncoder.encode("Customer", 999999);
        String c1 = no.sikt.graphitron.generated.util.NodeIdEncoder.encode("Customer", 1);
        Map<String, Object> data = execute(
            "{ nodes(ids: [\"" + c1 + "\", \"" + missing + "\"]) {"
            + " __typename ... on Customer { firstName } } }");
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) data.get("nodes");
        assertThat(nodes).hasSize(2);
        assertThat(nodes.get(0).get("firstName")).isEqualTo("Mary");
        assertThat(nodes.get(1)).isNull();
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
        assertThat(((Map<String, Object>) nodeData.get("node")).get("firstName")).isEqualTo("Mary");

        Map<String, Object> data = execute(
            "{ nodes(ids: [\"" + padded + "\"]) {"
            + " __typename ... on Customer { firstName } } }");
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) data.get("nodes");
        assertThat(nodes).hasSize(1);
        assertThat(nodes.get(0)).as("padded id must resolve under nodes(ids:)").isNotNull();
        assertThat(nodes.get(0).get("__typename")).isEqualTo("Customer");
        assertThat(nodes.get(0).get("firstName")).isEqualTo("Mary");
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
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) data.get("nodes");
        assertThat(nodes).hasSize(3);
        assertThat(nodes.get(0).get("firstName")).isEqualTo("Mary");
        assertThat(nodes.get(1).get("firstName")).isEqualTo("Patricia");
        assertThat(nodes.get(2).get("firstName")).isEqualTo("Mary");
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
        List<Map<String, Object>> nodes = (List<Map<String, Object>>) data.get("nodes");
        assertThat(nodes).hasSize(1);
        assertThat(nodes.get(0).get("__typename")).isEqualTo("Customer");
        assertThat(nodes.get(0).get("id")).isEqualTo(c1);
        assertThat(nodes.get(0).get("firstName")).isEqualTo("Mary");
        assertThat(nodes.get(0).get("customerId")).isEqualTo(1);
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
        String endCursor = (String) ((Map<String, Object>) ((Map<String, Object>) page1.get("stores")).get("pageInfo")).get("endCursor");
        assertThat(endCursor).isNotNull();

        Map<String, Object> page2 = execute(
            "{ stores(first: 1, after: \"" + endCursor + "\") { nodes { storeId } pageInfo { hasNextPage } } }");
        var conn2 = (Map<String, Object>) page2.get("stores");
        List<Map<String, Object>> nodes2 = (List<Map<String, Object>>) conn2.get("nodes");
        assertThat(nodes2).hasSize(1);
        var pageInfo2 = (Map<String, Object>) conn2.get("pageInfo");
        assertThat(pageInfo2.get("hasNextPage")).isEqualTo(false);
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
}
