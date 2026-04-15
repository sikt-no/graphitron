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
        postgres = new PostgreSQLContainer<>("postgres:18-alpine");
        postgres.start();

        dsl = DSL.using(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());

        // Load schema and test data from the shared fixtures init.sql (on classpath via graphitron-rewrite-test-fixtures)
        var initSql = readClasspathResource("init.sql");
        dsl.execute(initSql);

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
        assertThat(films).extracting(f -> f.get("title"))
            .containsExactlyInAnyOrder("ACADEMY DINOSAUR", "ADAPTATION HOLES");
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
}
