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

import static org.assertj.core.api.Assertions.as;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.InstanceOfAssertFactories.list;
import static org.assertj.core.api.InstanceOfAssertFactories.map;

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
 * <p>The child, lookup, nested and context-bound coordinates were pinned <em>before</em> the
 * call-site convergence moved their folds into glue calls, so these strings staying green is
 * "call sites moved, SQL did not" as a result rather than a claim. Note the filtered-child
 * EXISTS alias rides the child's own runtime alias ({@code ..._c0_fkt0_0}), the same convention
 * the glue uses everywhere.
 *
 * <p>Conjunct order is data the producer preserves verbatim (generated predicate first, authored
 * conditions after, per the classification's filter order), and the lifted-outer pin makes it
 * visible: {@code first_name}, {@code activebool}, then the authored EXISTS.
 *
 * <p>One later re-pin these strings absorbed: when projection became fully selection-gated (the
 * end of over-projection), the <em>select lists</em> lost the correlation-key columns of
 * unselected children, while every WHERE clause stayed byte-identical, which is exactly the
 * split these pins exist to make reviewable. The strings otherwise move only when their fixture
 * types or seed data change, and such a re-pin must preserve what the shape demonstrates.
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
                "select \"public\".\"customer\".\"last_name\" from \"public\".\"customer\" "
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
                "select \"public\".\"customer\".\"last_name\" from \"public\".\"customer\" "
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
                "select \"public\".\"store\".\"store_id\", \"storebyidinput\".\"idx\" as \"__idx__\" "
                    + "from \"public\".\"store\" "
                    + "join (values (0, ?), (1, ?)) as \"storebyidinput\" (\"idx\", \"store_id\") using (\"store_id\")",
                "select \"customersbyaddressdistrictsplit_c0\".\"last_name\", "
                    + "\"parentinput\".\"idx\" as \"__idx__\" "
                    + "from (values (0, ?), (1, ?)) as \"parentinput\" (\"idx\", \"store_id\") "
                    + "join \"public\".\"customer\" as \"customersbyaddressdistrictsplit_c0\" "
                    + "on \"customersbyaddressdistrictsplit_c0\".\"store_id\" = \"parentinput\".\"store_id\" "
                    + "where exists (select 1 as \"one\" from \"public\".\"address\" as \"customersbyaddressdistrictsplit_c0_fkt0_0\" "
                    + "where (\"customersbyaddressdistrictsplit_c0_fkt0_0\".\"address_id\" = \"customersbyaddressdistrictsplit_c0\".\"address_id\" "
                    + "and \"customersbyaddressdistrictsplit_c0_fkt0_0\".\"district\" = ?))");
    }

    @Test
    void lookupCoordinate_authoredNonKeyFilterBesideTheValuesJoin() {
        execute("{ languagesByKeyFiltered(language_id: [1, 2], name: \"En\") { name } }");
        assertThat(SQL_LOG)
            .as("lookup coordinate with an authored non-key filter: the lookup keys ride the "
                + "VALUES join, the authored prefix-match composes in the WHERE beside it; "
                + "authored on lookup is an ordinary condition row, never a rejection")
            .containsExactly(
                "select \"public\".\"language\".\"name\", \"languagesbykeyfilteredinput\".\"idx\" as \"__idx__\" "
                    + "from \"public\".\"language\" "
                    + "join (values (0, ?), (1, ?)) as \"languagesbykeyfilteredinput\" (\"idx\", \"language_id\") using (\"language_id\") "
                    + "where \"public\".\"language\".\"name\" like (replace(replace(replace(?, '!', '!!'), '%', '!%'), '_', '!_') || '%') escape '!'");
    }

    @Test
    void lookupCoordinate_generatedNonKeyFilterBesideTheValuesJoin() {
        // Keys [hit, hit-but-filtered, miss]: language 1 is English, language 2 is Italian and
        // matches its key but fails the predicate, language 99 does not exist.
        var data = execute("{ languagesByKeyGenerated(language_id: [1, 2, 99], name: \"English\") { languageId } }");
        assertThat(SQL_LOG)
            .as("the generated twin of the authored fixture above: the implicit column equality "
                + "the generator builds for a non-key argument lands in the same WHERE slot the "
                + "authored prefix-match occupies, beside the same VALUES join")
            .containsExactly(
                "select \"public\".\"language\".\"language_id\", \"languagesbykeygeneratedinput\".\"idx\" as \"__idx__\" "
                    + "from \"public\".\"language\" "
                    + "join (values (0, ?), (1, ?), (2, ?)) as \"languagesbykeygeneratedinput\" (\"idx\", \"language_id\") using (\"language_id\") "
                    + "where \"public\".\"language\".\"name\" = ?");

        // The one behavioural consequence this item introduces, named rather than left implicit:
        // a non-key predicate can now remove the row a key matched, and the caller cannot tell
        // that from a key that matched nothing. Both hold null at their own position, so the
        // filter changes which positions are populated and never which positions exist. Three
        // keys in, three slots out, with the filtered key at index 1 indistinguishable from the
        // unmatched key at index 2.
        assertThat(data).extractingByKey("languagesByKeyGenerated", as(list(Map.class)))
            .extracting(m -> m == null ? null : m.get("languageId"))
            .containsExactly(1, null, null);
    }

    @Test
    void inlineChildLookupCoordinate_generatedFilterInsideTheMultisetsInnerSelect() {
        // Same [hit, hit-but-filtered, miss] keys against film 1's cast (actors 1 and 2):
        // actor 1 is PENELOPE, actor 2 is NICK and is filtered out, actor 99 does not exist.
        var data = execute(
            "{ filmById(film_id: [\"1\"]) { actorsGenerated(actor_id: [1, 2, 99], first_name: \"PENELOPE\") { actorId } } }");
        assertThat(SQL_LOG)
            .as("inline child lookup: the glue call renders into the multiset's inner WHERE, "
                + "beside the FK correlation and the join to the input rows. Asserted as the "
                + "presence of the predicate rather than as whole-statement text, because this "
                + "arm mints runtime-prefixed aliases that churn on unrelated changes")
            .anySatisfy(sql -> assertThat(sql)
                .contains("\"actorsgeneratedinput\"")
                .contains("\"first_name\" = ?"));
        assertThat(data).extractingByKey("filmById", as(list(Map.class)))
            .singleElement(as(map(String.class, Object.class)))
            .extracting(f -> f.get("actorsGenerated"), as(list(Map.class)))
            .extracting(a -> a.get("actorId"))
            .containsExactly(1);
    }

    @Test
    void batchedChildLookupCoordinate_generatedFilterInTheLoadersWhere() {
        // The same three keys, through the @splitQuery loader rather than the inline multiset.
        var data = execute(
            "{ filmById(film_id: [\"1\"]) { actorsBySplitLookupGenerated(actor_id: [1, 2, 99], first_name: \"PENELOPE\") { actorId } } }");
        assertThat(SQL_LOG)
            .as("batched child lookup: the loader folds the same glue call into its "
                + "DSL.noCondition() WHERE against the child alias, beside the parent-input join "
                + "and the lookup keyset")
            .anySatisfy(sql -> assertThat(sql)
                .contains("\"actorsbysplitlookupgeneratedinput\"")
                .contains("\"first_name\" = ?"));
        assertThat(data).extractingByKey("filmById", as(list(Map.class)))
            .singleElement(as(map(String.class, Object.class)))
            .extracting(f -> f.get("actorsBySplitLookupGenerated"), as(list(Map.class)))
            .extracting(a -> a.get("actorId"))
            .containsExactly(1);
    }

    @Test
    void contextBoundChildCoordinate_batchedStatementCarriesTheContextValue() {
        execute("{ storeById(store_id: [1]) { customersSeenByUser { firstName } } }");
        assertThat(SQL_LOG)
            .as("@condition(contextArguments:) on a batched child: the userId value arrives "
                + "from the request context (the env, not the argument map) and binds into the "
                + "batched statement's WHERE")
            .containsExactlyInAnyOrder(
                "select \"public\".\"store\".\"store_id\", \"storebyidinput\".\"idx\" as \"__idx__\" "
                    + "from \"public\".\"store\" "
                    + "join (values (0, ?)) as \"storebyidinput\" (\"idx\", \"store_id\") using (\"store_id\")",
                "select \"customersseenbyuser_c0\".\"first_name\", "
                    + "\"parentinput\".\"idx\" as \"__idx__\" "
                    + "from (values (0, ?)) as \"parentinput\" (\"idx\", \"store_id\") "
                    + "join \"public\".\"customer\" as \"customersseenbyuser_c0\" "
                    + "on \"customersseenbyuser_c0\".\"store_id\" = \"parentinput\".\"store_id\" "
                    + "where lower(\"customersseenbyuser_c0\".\"first_name\") = lower(?)");
    }

    @Test
    void nestedCoordinate_authoredConditionInsideTheNestingTypesMultiset() {
        execute("{ filmById(film_id: [\"1\"]) { inlineBundle { languageFiltered(name: \"En\") { name } } } }");
        assertThat(SQL_LOG)
            .as("authored condition on a field nested inside a plain nesting type: the "
                + "correlated multiset carries the prefix-match against the nested field's own "
                + "aliased table, composed with the FK correlation")
            .containsExactly(
                "select (select coalesce(jsonb_agg(jsonb_build_array(t.\"v0\")), jsonb_build_array()) "
                    + "from (select \"film_l0\".\"name\" as \"v0\" "
                    + "from \"public\".\"language\" as \"film_l0\" "
                    + "where (\"film_l0\".\"language_id\" = \"public\".\"film\".\"language_id\" "
                    + "and \"film_l0\".\"name\" like (replace(replace(replace(?, '!', '!!'), '%', '!%'), '_', '!_') || '%') escape '!') "
                    + "fetch next ? rows only) as t) as \"__rk_languagefiltered\", "
                    + "\"public\".\"film\".\"title\", \"filmbyidinput\".\"idx\" as \"__idx__\" "
                    + "from \"public\".\"film\" "
                    + "join (values (0, ?)) as \"filmbyidinput\" (\"idx\", \"film_id\") using (\"film_id\")");
    }

    private Map<String, Object> execute(String query) {
        var input = Graphitron.newExecutionInput(dsl, "test-user").query(query).build();
        var result = graphql.execute(input);
        assertThat(result.getErrors()).isEmpty();
        return result.getData();
    }
}
