package no.sikt.graphitron.rewrite.test.querydb;

import graphql.ExecutionResult;
import graphql.GraphQL;
import graphql.schema.GraphQLSchema;
import no.sikt.graphitron.generated.Graphitron;
import no.sikt.graphitron.generated.util.NodeIdEncoder;
import no.sikt.graphitron.rewrite.test.tier.ExecutionTier;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R322 execution-tier coverage: the runtime value-agreement check that fires when more than one writer
 * lands on a single row column. This class pins the {@code @service} jOOQ-record path (the
 * {@code createStorageBinRecord} helper's {@code NodeIdEncoder.requireColumnAgreement} call); the
 * mutation INSERT/UPDATE path is exercised by {@link DmlValueAgreementExecutionTest}.
 *
 * <p>The {@code agreeStorageBin} fixture is a {@link no.sikt.graphitron.rewrite.test.jooq.tables.records.StorageBinRecord}
 * {@code @service} param whose input carries two writers on {@code bin_id}: the same-table identity
 * {@code @nodeId(typeName: "StorageBin")} decode and a plain {@code @field}. Agreement is defined by the
 * destination column's coercion, so the four rows of the matrix the spec calls out are:
 * <ul>
 *   <li>agreement on a format-variant wire value ({@code "01"} coerces to the decoded {@code 1});</li>
 *   <li>disagreement (distinct values) throws and the service never runs;</li>
 *   <li>the presence guard (an omitted nullable writer leaves the lone decode and does not throw).</li>
 * </ul>
 */
@ExecutionTier
@SuppressWarnings("unchecked")
class NodeIdValueAgreementExecutionTest {

    static PostgreSQLContainer postgres;
    static DSLContext dsl;
    static GraphQL graphql;

    @BeforeAll
    static void startDatabase() {
        var localUrl = System.getProperty("test.db.url");
        if (localUrl != null) {
            var user = System.getProperty("test.db.username", "postgres");
            var pass = System.getProperty("test.db.password", "postgres");
            dsl = DSL.using(localUrl, user, pass);
        } else {
            postgres = new PostgreSQLContainer("postgres:18-alpine").withInitScript("init.sql");
            postgres.start();
            dsl = DSL.using(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        }
        GraphQLSchema schema = Graphitron.buildSchema(b -> {});
        graphql = GraphQL.newGraphQL(schema).build();
    }

    @AfterAll
    static void stopDatabase() {
        if (postgres != null) postgres.stop();
    }

    private Map<String, Object> execute(String query) {
        var input = Graphitron.newExecutionInput(dsl, "test-user").query(query).build();
        var result = graphql.execute(input);
        assertThat(result.getErrors()).as("graphql errors: " + result.getErrors()).isEmpty();
        return result.getData();
    }

    private ExecutionResult executeRaw(String query) {
        var input = Graphitron.newExecutionInput(dsl, "test-user").query(query).build();
        return graphql.execute(input);
    }

    @Test
    void serviceRecord_twoWritersAgreeOnFormatVariantWireValue_buildsRecord() {
        // The plain @field supplies "01"; the same-table identity decodes StorageBin(1) to "1". Both
        // target bin_id (integer). String.valueOf("01") != "1", but bin_id's DataType.convert collapses
        // both to 1 — the coerced-comparison choice (D3) is what keeps this from false-throwing.
        String binId1 = NodeIdEncoder.encode("StorageBin", 1);
        Map<String, Object> data = execute(
            "mutation { agreeStorageBin(in: {binId: \"" + binId1 + "\", binIdText: \"01\"}) }");
        assertThat(data).extractingByKey("agreeStorageBin").isEqualTo("bin_id=1");
    }

    @Test
    void serviceRecord_twoWritersDisagree_throwsAndServiceNeverRuns() {
        // The decode resolves bin_id to 1; the plain @field supplies "2". They coerce to different values,
        // so requireColumnAgreement throws inside createStorageBinRecord before the service body — the
        // mutation surfaces an error and yields no value (the silent last-write-wins drop is what R322 closes).
        String binId1 = NodeIdEncoder.encode("StorageBin", 1);
        ExecutionResult result = executeRaw(
            "mutation { agreeStorageBin(in: {binId: \"" + binId1 + "\", binIdText: \"2\"}) }");
        assertThat(result.getErrors())
            .as("disagreeing writers on bin_id must surface a value-agreement error")
            .isNotEmpty();
        Map<String, Object> data = result.getData();
        assertThat(data.get("agreeStorageBin"))
            .as("the mutation does not succeed when the two writers disagree")
            .isNull();
    }

    @Test
    void serviceRecord_omittedNullableWriter_leavesLoneDecode_doesNotThrow() {
        // Only the decode is present (binIdText omitted). The presence guard means a single present writer
        // is never a conflict, even though an absent writer's "value" would differ — no agreement check fires.
        String binId7 = NodeIdEncoder.encode("StorageBin", 7);
        Map<String, Object> data = execute(
            "mutation { agreeStorageBin(in: {binId: \"" + binId7 + "\"}) }");
        assertThat(data).extractingByKey("agreeStorageBin").isEqualTo("bin_id=7");
    }
}
