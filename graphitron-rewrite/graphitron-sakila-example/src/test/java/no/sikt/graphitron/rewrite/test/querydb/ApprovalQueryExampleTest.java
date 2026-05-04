package no.sikt.graphitron.rewrite.test.querydb;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import graphql.ExecutionInput;
import graphql.GraphQL;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.GraphQLSchema;
import no.sikt.graphitron.generated.Graphitron;
import no.sikt.graphitron.generated.schema.GraphitronContext;
import no.sikt.graphitron.rewrite.test.tier.ExecutionTier;
import org.dataloader.DataLoaderRegistry;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Approval-style worked example: execute a {@code .graphql} file, serialise the response
 * as canonical JSON, compare against a checked-in {@code .approved.json} sibling.
 *
 * <p>Copy this file into your own consumer module's tests to seed an "approval" pattern.
 * The {@code @BeforeAll} block is the boilerplate every query-to-database test shares
 * (Postgres up, schema built, engine wired); the assertion shape is "actual JSON must
 * equal approved JSON, byte-for-byte". When a query's expected result legitimately
 * changes, replace the {@code .approved.json} file with the new output.
 *
 * <p>Failure mode: when the actual output diverges from the approved file, the assertion
 * also writes the divergent result to {@code <name>.actual.json} next to the approved
 * file so the next iteration is "diff the two files; if the new shape is correct, mv
 * actual onto approved".
 */
@ExecutionTier
class ApprovalQueryExampleTest {

    static PostgreSQLContainer postgres;
    static DSLContext dsl;
    static GraphQL graphql;
    static ObjectMapper json;

    @BeforeAll
    static void startDatabase() {
        String localUrl = System.getProperty("test.db.url");
        if (localUrl != null) {
            dsl = DSL.using(localUrl,
                System.getProperty("test.db.username", "postgres"),
                System.getProperty("test.db.password", "postgres"));
        } else {
            postgres = new PostgreSQLContainer("postgres:18-alpine").withInitScript("init.sql");
            postgres.start();
            dsl = DSL.using(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        }
        GraphQLSchema schema = Graphitron.buildSchema(b -> {});
        graphql = GraphQL.newGraphQL(schema).build();
        json = new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT);
    }

    @AfterAll
    static void stopDatabase() {
        if (postgres != null) {
            postgres.stop();
        }
    }

    @Test
    void filmsBasicMatchesApproved() throws IOException {
        verifyApproved("approval/queries/films_basic.graphql", "approval/approvals/films_basic.approved.json");
    }

    private void verifyApproved(String queryPath, String approvedPath) throws IOException {
        Path approvalsRoot = Path.of("src/test/resources");
        String query = Files.readString(approvalsRoot.resolve(queryPath));
        Map<String, Object> data = execute(query);
        String actual = json.writerWithDefaultPrettyPrinter().writeValueAsString(data);

        Path approvedFile = approvalsRoot.resolve(approvedPath);
        String approved = Files.readString(approvedFile).trim();
        if (!actual.trim().equals(approved)) {
            Path actualFile = approvedFile.resolveSibling(
                approvedFile.getFileName().toString().replace(".approved.json", ".actual.json"));
            Files.writeString(actualFile, actual);
            assertThat(actual).as(
                "actual GraphQL response written to %s; mv onto %s if the new shape is intentional",
                actualFile, approvedFile)
                .isEqualTo(approved);
        }
    }

    private Map<String, Object> execute(String query) {
        GraphitronContext context = new GraphitronContext() {
            @Override public DSLContext getDslContext(DataFetchingEnvironment env) { return dsl; }
            @Override public <T> T getContextArgument(DataFetchingEnvironment env, String name) { return null; }
        };
        ExecutionInput input = ExecutionInput.newExecutionInput()
            .query(query)
            .graphQLContext(b -> b.put(GraphitronContext.class, context))
            .dataLoaderRegistry(new DataLoaderRegistry())
            .build();
        var result = graphql.execute(input);
        assertThat(result.getErrors()).isEmpty();
        return result.getData();
    }
}
