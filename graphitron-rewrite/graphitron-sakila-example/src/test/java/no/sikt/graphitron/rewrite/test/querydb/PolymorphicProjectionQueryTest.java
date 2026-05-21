package no.sikt.graphitron.rewrite.test.querydb;

import graphql.ExecutionInput;
import graphql.GraphQL;
import graphql.schema.DataFetchingEnvironment;
import graphql.schema.GraphQLSchema;
import no.sikt.graphitron.generated.Graphitron;
import no.sikt.graphitron.generated.schema.GraphitronContext;
import no.sikt.graphitron.rewrite.test.tier.ExecutionTier;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * R108 execution-tier proof: the rendered SQL for an asymmetric inline-fragment query on a
 * multi-table polymorphic field projects only the columns the active fragment selected,
 * per participant.
 *
 * <p>The {@code AddressOccupant} union resolves to {@code Customer | Staff}. Both participant
 * tables expose a {@code first_name} column behind the GraphQL field {@code firstName}. Before
 * R108 the Stage-2 per-typename SELECT received the parent's flattened
 * {@code DataFetchingFieldSelectionSet}, so an asymmetric fragment query asking for
 * {@code firstName} only on {@code Customer} still produced {@code SELECT "staff"."first_name"}
 * in the Staff-branch SQL; the wire payload stayed correct (graphql-java drops the inactive
 * value at serialisation) but the SQL over-selected. R108 wraps the selection set through
 * {@code PolymorphicSelectionSet.restrictTo} at the Stage-2 emit site so each per-typename
 * SELECT projects only columns matching that variant.
 *
 * <p>SQL capture mirrors the {@code CompositeKeyLookupQueryTest} pattern: a jOOQ
 * {@link org.jooq.ExecuteListener} appends each rendered statement (lower-cased) to
 * {@link #SQL_LOG}; tests grep the log for the per-table SELECTs and assert on the
 * column references.
 */
@ExecutionTier
class PolymorphicProjectionQueryTest {

    static PostgreSQLContainer postgres;
    static DSLContext dsl;
    static GraphQL graphql;
    static final java.util.List<String> SQL_LOG = new java.util.concurrent.CopyOnWriteArrayList<>();

    @BeforeAll
    static void startDatabase() throws Exception {
        var localUrl = System.getProperty("test.db.url");
        if (localUrl != null) {
            var user = System.getProperty("test.db.username", "postgres");
            var pass = System.getProperty("test.db.password", "postgres");
            dsl = DSL.using(localUrl, user, pass);
        } else {
            postgres = new PostgreSQLContainer("postgres:18-alpine")
                .withInitScript("init.sql");
            postgres.start();
            dsl = DSL.using(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
        }
        dsl.configuration().set(new org.jooq.impl.DefaultExecuteListenerProvider(
            new org.jooq.ExecuteListener() {
                @Override
                public void executeStart(org.jooq.ExecuteContext ctx) {
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

    @BeforeEach
    void clearSqlLog() {
        SQL_LOG.clear();
    }

    private Map<String, Object> execute(String query) {        var input = Graphitron.newExecutionInput(dsl, "test-user").query(query).build();
        var result = graphql.execute(input);
        assertThat(result.getErrors()).isEmpty();
        return result.getData();
    }

    @Test
    void asymmetricFragment_customerOnly_staffSelectOmitsFirstName() {
        // Query: occupants { __typename ... on Customer { firstName } }. Pre-R108, the Staff
        // Stage-2 SELECT received the flattened selection set and projected first_name even
        // though no Staff branch requested it; the response stayed correct because graphql-java
        // dropped the value at serialisation, but the SQL over-selected. R108 wraps the
        // selection set so Staff sees no Customer-side SelectedFields.
        execute("""
            { customers(active: true) { address { occupants {
                __typename
                ... on Customer { firstName }
            } } } }
            """);

        var customerSelect = findStage2Select("customer");
        assertThat(customerSelect)
            .as("Customer branch keeps the requested firstName column")
            .contains("\"customer\".\"first_name\"");

        var staffSelect = findStage2Select("staff");
        assertThat(staffSelect)
            .as("Staff branch must NOT project first_name; the asymmetric fragment requested it "
                + "only on Customer, and R108 filters the selection set per participant")
            .doesNotContain("\"staff\".\"first_name\"");
    }

    @Test
    void asymmetricFragment_staffOnly_customerSelectOmitsFirstName() {
        // Inverse asymmetry: pin both directions so the filter is precise rather than
        // accidentally always-dropping the shared column.
        execute("""
            { customers(active: true) { address { occupants {
                __typename
                ... on Staff { firstName }
            } } } }
            """);

        var staffSelect = findStage2Select("staff");
        assertThat(staffSelect)
            .as("Staff branch keeps the requested firstName column")
            .contains("\"staff\".\"first_name\"");

        var customerSelect = findStage2Select("customer");
        assertThat(customerSelect)
            .as("Customer branch must NOT project first_name; the asymmetric fragment requested "
                + "it only on Staff")
            .doesNotContain("\"customer\".\"first_name\"");
    }

    @Test
    void symmetricFragment_bothBranchesKeepFirstName() {
        // Symmetric query: both fragments request firstName. R108's filter is precise — the
        // wrapper restricts by participant, so a SelectedField requested on Customer survives
        // for the Customer pass, and one requested on Staff survives for the Staff pass. A
        // regression that drops the column on both sides would fail this assertion.
        execute("""
            { customers(active: true) { address { occupants {
                __typename
                ... on Customer { firstName }
                ... on Staff { firstName }
            } } } }
            """);

        var customerSelect = findStage2Select("customer");
        assertThat(customerSelect)
            .as("Customer branch projects firstName when both fragments request it")
            .contains("\"customer\".\"first_name\"");

        var staffSelect = findStage2Select("staff");
        assertThat(staffSelect)
            .as("Staff branch projects firstName when both fragments request it")
            .contains("\"staff\".\"first_name\"");
    }

    /**
     * Locates the Stage-2 per-typename SELECT for a given participant. Each Stage-2 query
     * binds the parent VALUES table as {@code "<participant-lc>input"} (e.g. {@code customerinput}
     * for {@code Customer}, {@code staffinput} for {@code Staff}); filtering on that alias picks
     * the multi-table polymorphic emitter's per-typename helper specifically, ignoring the
     * Stage-1 narrow UNION ALL (which uses {@code parentinput}) and any unrelated SELECT against
     * the same underlying table elsewhere in the query plan.
     */
    private String findStage2Select(String participantLowercase) {
        String inputAlias = "\"" + participantLowercase + "input\"";
        return SQL_LOG.stream()
            .filter(s -> s.startsWith("select ") && s.contains(inputAlias))
            .findFirst()
            .orElseThrow(() -> new AssertionError(
                "no Stage-2 SELECT for participant '" + participantLowercase
                    + "' (alias " + inputAlias + ") found in SQL log: " + SQL_LOG));
    }
}
