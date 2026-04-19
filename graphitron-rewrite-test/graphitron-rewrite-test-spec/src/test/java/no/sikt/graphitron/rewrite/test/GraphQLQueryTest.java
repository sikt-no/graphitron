package no.sikt.graphitron.rewrite.test;

import graphql.ExecutionInput;
import graphql.GraphQL;
import graphql.schema.GraphQLSchema;
import graphql.schema.idl.SchemaGenerator;
import graphql.schema.idl.SchemaParser;
import no.sikt.graphitron.rewrite.test.generated.rewrite.GraphitronWiring;
import no.sikt.graphql.GraphitronContext;
import graphql.schema.DataFetchingEnvironment;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.Result;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
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

        // Build GraphQL schema from the SDL used by the generator
        var sdl = Files.readString(Path.of("src/main/resources/graphql/schema.graphqls"));
        // Add directives so the schema parses (the generator needs them, and so does SchemaGenerator)
        var directives = readClasspathResource("directives.graphqls");
        var registry = new SchemaParser().parse(directives + "\n" + sdl);

        var wiring = GraphitronWiring.build()
            .build();

        GraphQLSchema schema = new SchemaGenerator().makeExecutableSchema(registry, wiring);
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
            @Override
            public String getDataLoaderName(DataFetchingEnvironment env) {
                return env.getExecutionStepInfo().getPath().toString().replaceAll("/\\d+", "");
            }
        };

        // DataLoader registry is per-request; Split* fetchers call computeIfAbsent on it.
        // graphql-java requires one explicitly even for non-DataLoader queries.
        var input = ExecutionInput.newExecutionInput()
            .query(query)
            .graphQLContext(builder -> builder.put("graphitronContext", context))
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
            @Override
            public String getDataLoaderName(DataFetchingEnvironment env) {
                return env.getExecutionStepInfo().getPath().toString().replaceAll("/\\d+", "");
            }
        };

        var input = ExecutionInput.newExecutionInput()
            .query(query)
            .graphQLContext(builder -> builder.put("graphitronContext", context))
            .build();

        return graphql.execute(input);
    }

    private static String readClasspathResource(String name) {
        try (InputStream is = GraphQLQueryTest.class.getClassLoader().getResourceAsStream(name)) {
            if (is == null) throw new IllegalStateException(name + " not found on classpath");
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
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
}
