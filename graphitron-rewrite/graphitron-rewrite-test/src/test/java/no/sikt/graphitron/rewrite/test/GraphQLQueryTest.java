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

/**
 * End-to-end tests that execute GraphQL queries against a real PostgreSQL database
 * using the generated wiring, field resolvers, and table methods.
 *
 * <p>This verifies that the generated code actually works — not just that it compiles.
 */
class GraphQLQueryTest {

    static PostgreSQLContainer<?> postgres;
    static DSLContext dsl;
    static GraphQL graphql;
    static final AtomicInteger QUERY_COUNT = new AtomicInteger();

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
        // call QUERY_COUNT.set(0) before executing and assert on the count afterward.
        dsl.configuration().set(new org.jooq.impl.DefaultExecuteListenerProvider(
            new org.jooq.impl.DefaultExecuteListener() {
                @Override
                public void executeStart(org.jooq.ExecuteContext ctx) {
                    QUERY_COUNT.incrementAndGet();
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
        // Relay spec: must reject when both first and last are supplied.
        // graphql-java wraps the fetcher's IllegalArgumentException into an execution error.
        var result = executeRaw(
            "{ filmsConnection(first: 2, last: 2) { nodes { title } } }");
        assertThat(result.getErrors()).isNotEmpty();
        assertThat(result.getErrors().get(0).getMessage())
            .containsIgnoringCase("first")
            .containsIgnoringCase("last");
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

    // ===== single-cardinality @splitQuery (plan-single-cardinality-split-query.md) =====

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
    void node_validPrefixNoSuchRow_returnsNull() {
        // Registered typeId but the row doesn't exist — null, not error.
        String id = no.sikt.graphitron.generated.util.NodeIdEncoder.encode("Customer", 999999);
        Map<String, Object> data = execute("{ node(id: \"" + id + "\") { __typename } }");
        assertThat(data.get("node")).isNull();
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
}
