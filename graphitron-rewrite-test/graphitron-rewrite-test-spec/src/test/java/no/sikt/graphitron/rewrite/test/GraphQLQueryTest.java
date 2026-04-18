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

        var input = ExecutionInput.newExecutionInput()
            .query(query)
            .graphQLContext(builder -> builder.put("graphitronContext", context))
            .build();

        var result = graphql.execute(input);
        assertThat(result.getErrors()).isEmpty();
        return result.getData();
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
}
