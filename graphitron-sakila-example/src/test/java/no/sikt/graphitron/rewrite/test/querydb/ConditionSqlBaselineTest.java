package no.sikt.graphitron.rewrite.test.querydb;

import graphql.GraphQL;
import no.sikt.graphitron.generated.Graphitron;
import no.sikt.graphitron.rewrite.test.tier.ExecutionTier;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The condition family's SQL equivalence baseline, extending the programme-level harness
 * ({@code ProjectionSqlBaselineTest}'s {@code SQL_LOG} idiom): whole rendered statements over one
 * representative faceted, one lifted-outer, one FK-target, and one filtered-child coordinate.
 * The condition reshape's promise is "shape may move, SQL may not": emitted Java went from
 * env-taking shims over a typed entity layer to single-layer map-taking glue, and these pins are
 * what makes "the reshape moved no SQL" a result rather than a claim. The one sanctioned delta
 * was decided before these strings were authored: EXISTS aliases are runtime-prefixed on the base
 * table's name ({@code customer_fkt0_0}), one convention across every host, so the pins never
 * move for aliasing again.
 *
 * <p>The filtered-child coordinate ({@code Store.customersByAddressDistrictSplit}) is composed
 * inline by the split rows method today; its pin is what holds the call-site convergence slice to
 * "call sites moved, SQL did not". Note its EXISTS alias rides the child's own runtime alias
 * ({@code ..._c0_fkt0_0}), the same convention the glue adopted.
 *
 * <p>Conjunct order is data the producer preserves verbatim (generated predicate first, authored
 * conditions after, per the classification's filter order), and the lifted-outer pin makes it
 * visible: {@code first_name}, {@code activebool}, then the authored EXISTS. The strings
 * otherwise move only when their fixture types or seed data change, and such a re-pin must
 * preserve what the shape demonstrates.
 */
@ExecutionTier
class ConditionSqlBaselineTest {

    static PostgreSQLContainer postgres;
    static DSLContext dsl;
    static GraphQL graphql;
    static final java.util.List<String> SQL_LOG = new java.util.concurrent.CopyOnWriteArrayList<>();

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
        dsl.configuration().set(new org.jooq.impl.DefaultExecuteListenerProvider(
            new org.jooq.ExecuteListener() {
                @Override
                public void executeStart(org.jooq.ExecuteContext ctx) {
                    var sql = ctx.sql();
                    if (sql != null) SQL_LOG.add(sql.toLowerCase(java.util.Locale.ROOT));
                }
            }));
        graphql = Graphitron.newGraphQL().build();
    }

    @AfterAll
    static void stopDatabase() {
        if (postgres != null) postgres.stop();
    }

    @BeforeEach
    void clearSqlLog() {
        SQL_LOG.clear();
    }

    @Test
    void facetedCoordinate_pageQueryAndFilterMinusSelfUnion() {
        execute("{ filmsFaceted(filter: { rating: [G] }) { facets { rating { value count } length { value count } } nodes { title } } }");
        assertThat(SQL_LOG)
            .as("faceted carrier: the page query applies the full filter; the facet aggregate is "
                + "one UNION ALL whose rating arm drops the rating facet's own predicate (the "
                + "filter-minus-self fragments) while the length arm keeps it")
            .containsExactlyInAnyOrder(
                "select \"public\".\"film\".\"length\", \"public\".\"film\".\"rating\", "
                    + "\"public\".\"film\".\"title\", \"public\".\"film\".\"film_id\" "
                    + "from \"public\".\"film\" "
                    + "where \"public\".\"film\".\"rating\" in (cast(? as \"public\".\"mpaa_rating\")) "
                    + "order by \"public\".\"film\".\"film_id\" asc fetch next ? rows only",
                "select 'rating' as \"facet\", cast(\"public\".\"film\".\"rating\" as varchar) as \"value\", count(*) as \"cnt\" "
                    + "from \"public\".\"film\" where \"public\".\"film\".\"rating\" is not null "
                    + "group by \"public\".\"film\".\"rating\" "
                    + "union all "
                    + "select 'length' as \"facet\", cast(\"public\".\"film\".\"length\" as varchar) as \"value\", count(*) as \"cnt\" "
                    + "from \"public\".\"film\" "
                    + "where (\"public\".\"film\".\"rating\" in (cast(? as \"public\".\"mpaa_rating\")) "
                    + "and \"public\".\"film\".\"length\" is not null) "
                    + "group by \"public\".\"film\".\"length\"");
    }

    @Test
    void liftedOuterCoordinate_generatedConjunctsThenAuthoredExists() {
        execute("{ customersByMultiFieldFilter(filter: { firstName: \"Mary\", activebool: true }) { lastName } }");
        assertThat(SQL_LOG)
            .as("lifted-outer coordinate: the implicit siblings read through the one lifted "
                + "filter map and render first, in filter order, then the authored FK-target "
                + "EXISTS; the EXISTS alias is runtime-prefixed on the base table")
            .containsExactly(
                "select \"public\".\"customer\".\"last_name\", \"public\".\"customer\".\"address_id\", "
                    + "\"public\".\"customer\".\"store_id\" from \"public\".\"customer\" "
                    + "where (\"public\".\"customer\".\"first_name\" = ? "
                    + "and \"public\".\"customer\".\"activebool\" = ? "
                    + "and exists (select 1 as \"one\" from \"public\".\"address\" as \"customer_fkt0_0\" "
                    + "where (\"customer_fkt0_0\".\"address_id\" = \"public\".\"customer\".\"address_id\" "
                    + "and \"customer_fkt0_0\".\"district\" = ?))) "
                    + "order by \"public\".\"customer\".\"customer_id\" asc");
    }

    @Test
    void fkTargetCoordinate_correlatedExistsOverTheFkHop() {
        execute("{ customersByAddressDistrict(filter: {}) { lastName } }");
        assertThat(SQL_LOG)
            .as("FK-target coordinate: the developer method receives the aliased FK-target table "
                + "inside a correlated EXISTS, correlation on the FK columns")
            .containsExactly(
                "select \"public\".\"customer\".\"last_name\", \"public\".\"customer\".\"address_id\", "
                    + "\"public\".\"customer\".\"store_id\" from \"public\".\"customer\" "
                    + "where exists (select 1 as \"one\" from \"public\".\"address\" as \"customer_fkt0_0\" "
                    + "where (\"customer_fkt0_0\".\"address_id\" = \"public\".\"customer\".\"address_id\" "
                    + "and \"customer_fkt0_0\".\"district\" = ?)) "
                    + "order by \"public\".\"customer\".\"customer_id\" asc");
    }

    @Test
    void filteredChildCoordinate_batchedStatementCarriesTheInlineFold() {
        execute("{ storeById(store_id: [1, 2]) { storeId customersByAddressDistrictSplit(filter: {}) { lastName } } }");
        assertThat(SQL_LOG)
            .as("filtered split child: the parent lookup runs its VALUES join, the child batch "
                + "carries the condition content composed inline today; this is the string the "
                + "call-site convergence slice must keep green unchanged")
            .containsExactlyInAnyOrder(
                "select \"public\".\"store\".\"store_id\", \"public\".\"store\".\"manager_staff_id\", "
                    + "\"public\".\"store\".\"address_id\" from \"public\".\"store\" "
                    + "join (values (0, ?), (1, ?)) as \"storebyidinput\" (\"idx\", \"store_id\") using (\"store_id\") "
                    + "order by \"storebyidinput\".\"idx\"",
                "select \"customersbyaddressdistrictsplit_c0\".\"last_name\", "
                    + "\"customersbyaddressdistrictsplit_c0\".\"address_id\", "
                    + "\"customersbyaddressdistrictsplit_c0\".\"store_id\", "
                    + "\"parentinput\".\"idx\" as \"__idx__\" "
                    + "from (values (0, ?), (1, ?)) as \"parentinput\" (\"idx\", \"store_id\") "
                    + "join \"public\".\"customer\" as \"customersbyaddressdistrictsplit_c0\" "
                    + "on \"customersbyaddressdistrictsplit_c0\".\"store_id\" = \"parentinput\".\"store_id\" "
                    + "where exists (select 1 as \"one\" from \"public\".\"address\" as \"customersbyaddressdistrictsplit_c0_fkt0_0\" "
                    + "where (\"customersbyaddressdistrictsplit_c0_fkt0_0\".\"address_id\" = \"customersbyaddressdistrictsplit_c0\".\"address_id\" "
                    + "and \"customersbyaddressdistrictsplit_c0_fkt0_0\".\"district\" = ?))");
    }

    private Map<String, Object> execute(String query) {
        var input = Graphitron.newExecutionInput(dsl, "test-user").query(query).build();
        var result = graphql.execute(input);
        assertThat(result.getErrors()).isEmpty();
        return result.getData();
    }
}
