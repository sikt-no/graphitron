package no.sikt.graphitron.rewrite.test.querydb;

import no.sikt.graphitron.rewrite.test.SakilaDatabase;
import graphql.ExecutionInput;
import graphql.GraphQL;
import graphql.schema.DataFetchingEnvironment;
import no.sikt.graphitron.generated.Graphitron;
import no.sikt.graphitron.generated.schema.GraphitronContext;
import no.sikt.graphitron.rewrite.test.tier.ExecutionTier;
import org.dataloader.DataLoaderRegistry;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.LIST;
import static org.assertj.core.api.InstanceOfAssertFactories.MAP;

/**
 * Match-style worked example: load a {@code .graphql} file, execute against the in-process
 * engine, assert specific paths on the response.
 *
 * <p>Copy this file into your own consumer module's tests to seed a "match" pattern. The
 * {@code @BeforeAll} block is the boilerplate every query-to-database test shares (Postgres
 * up, schema built, engine wired). Each {@code @Test} method is one query: drop a {@code
 * .graphql} file under {@code src/test/resources/match/queries/} and write a sibling test
 * that loads it.
 */
@ExecutionTier
class MatchQueryExampleTest {

    static DSLContext dsl;
    static GraphQL graphql;

    @BeforeAll
    static void startDatabase() {
        dsl = SakilaDatabase.dsl();

        graphql = Graphitron.newGraphQL().build();
    }

    @Test
    void allCustomersHaveAFirstName() throws IOException {
        Map<String, Object> data = execute(readQuery("match/queries/customers_basic.graphql"));

        assertThat(data).extractingByKey("customers", as(LIST))
            .hasSize(5)
            .allSatisfy(row -> assertThat(row).asInstanceOf(MAP).extractingByKey("firstName").isNotNull());
    }

    private static String readQuery(String resourceRelativePath) throws IOException {
        return Files.readString(Path.of("src/test/resources").resolve(resourceRelativePath));
    }

    private Map<String, Object> execute(String query) {
        ExecutionInput input = Graphitron.newExecutionInput(dsl, "{}", "test-user").query(query).build();
        var result = graphql.execute(input);
        assertThat(result.getErrors()).isEmpty();
        return result.getData();
    }
}
