package no.sikt.graphitron.rewrite.test.querydb;

import graphql.GraphQL;
import no.sikt.graphitron.generated.Graphitron;
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
 * The batched child family's SQL equivalence baseline, the same idiom as
 * {@link RootLauncherSqlBaselineTest}: whole rendered statements, exact strings and statement
 * counts, one representative query per batched child shape, authored against the pre-cutover
 * {@code SplitRowsMethodEmitter} emission and frozen before the child family folds into the
 * launcher relation. The fold's promise is the launcher item's: shape may move (the rows-method
 * body moves from an opaque emitter composition onto command rows and a renderer), SQL may not.
 * Editing an expected string during the child-family fold is a defect being papered over.
 *
 * <p>One deliberate widening has since landed on top of that baseline, and it is the exception
 * that states the rule: the list-shaped batched arms (plain and {@code @lookupKey}) gained the
 * batch-wide {@code ORDER BY} their coordinate's ordering always resolved and the emission used
 * to discard, so a {@code @splitQuery} child list obeys the same {@code @defaultOrder} its
 * inline twin obeys. Those three expected strings were edited because the SQL was intended to
 * change; every other string here still holds the fold's promise, and a diff to one of them is
 * still the defect this file exists to catch.
 *
 * <p>The shapes: the list batched child ({@code SplitParent.tags}, the parent-input VALUES
 * derived table joined through the FK chain with the {@code __idx__} scatter key), the
 * single-cardinality batched child ({@code Customer.addressSplit}, one key row, the
 * single-scatter shape), the batched connection child ({@code Film.actorsConnection}, the
 * {@code ROW_NUMBER() OVER (PARTITION BY __idx__)} per-parent page envelope with its shared
 * count source), the batched lookup child on both source shapes
 * ({@code Film.actorsBySplitLookup} table-arm, {@code FilmDetails.actorsByLookup} record-arm;
 * the second {@code @lookupKey} VALUES derived table narrowing the batch), the batched
 * pivot child ({@code Film.titleTranslationsSplit}, the key-preserving left join with filtered
 * aggregates grouped by the idx scatter key), the service table lift
 * ({@code Film.castMembers}, the returned records' PKs re-projected by identity through the
 * {@code (idx, seq, pk...)} VALUES join, ordered by the service's flatten order), and the
 * polymorphic batched child in its three DataLoader-backed shapes ({@code Address.occupants}
 * list, {@code Address.occupantsConnection} with the ranked pages envelope,
 * {@code OccupantsBatchPayload.occupants} on an accessor-many record parent, the
 * {@code loader.loadMany} dispatch; each is the stage-1 narrow UNION ALL over the participant
 * tables plus per-typename stage-2 VALUES-join re-projections), and the batched discriminated
 * interface child ({@code Film.filmContents}, the participant-driven re-projection composed over
 * that same parent-input anchor: the {@code __discriminator__} routing alias, the deduped
 * per-branch {@code $project}, the known-value {@code IN} restriction, and the batch-wide
 * {@code ORDER BY} whose relative order each parent's scatter bucket inherits), plus its
 * paginated twin ({@code Film.filmContentsConnection}, the same re-projection riding the ranked
 * page envelope, which pins that the extracted windowing fragment serves both batched
 * connection binders). Seed cardinalities
 * keep the VALUES row counts stable: two {@code split_parent} rows, one customer key, one or two
 * film keys per pin, film 1's two seeded cast members, and the occupants fixtures' seeded
 * customer/staff address links.
 */
@ExecutionTier
class BatchedChildSqlBaselineTest {

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
    void listBatchedChild_parentInputValuesJoinWithIdxScatterKey() {
        execute("{ splitParents { label tags { tag } } }");
        assertThat(SQL_LOG)
            .as("list batched child: the parent statement, then one batch statement joining the "
                + "parent-input VALUES derived table through the FK chain, idx-keyed for scatter, "
                + "with the coordinate's own ORDER BY over the whole batch (the scatter appends "
                + "each row to its key's bucket in fetch order, so one global sort reproduces "
                + "the inline twin's per-parent ordering)")
            .containsExactly(
                "select \"public\".\"split_parent\".\"label\", \"public\".\"split_parent\".\"parent_code\" "
                    + "from \"public\".\"split_parent\" "
                    + "order by \"public\".\"split_parent\".\"parent_id\" asc",
                "select \"tags_s0\".\"tag\", \"parentinput\".\"idx\" as \"__idx__\" "
                    + "from (values (0, ?), (1, ?)) as \"parentinput\" (\"idx\", \"parent_code\") "
                    + "join \"public\".\"split_parent_tag\" as \"tags_s0\" "
                    + "on \"tags_s0\".\"parent_code\" = \"parentinput\".\"parent_code\" "
                    + "order by \"tags_s0\".\"tag_id\" asc");
    }

    @Test
    void discriminatedInterfaceBatchedChild_reprojectionOverTheParentInputAnchor() {
        execute("{ filmById(film_id: [\"1\", \"2\"]) { filmContents { __typename title } } }");
        assertThat(SQL_LOG)
            .as("batched discriminated interface child: the participant-driven re-projection "
                + "(the routing discriminator alias, each branch's $project, the known-value IN "
                + "restriction) composed over the plain batched child's parent-input VALUES "
                + "anchor, one statement for the whole batch of films")
            .containsExactly(
                "select \"public\".\"film\".\"film_id\", \"public\".\"film\".\"title\", "
                    + "\"filmbyidinput\".\"idx\" as \"__idx__\" "
                    + "from \"public\".\"film\" "
                    + "join (values (0, ?), (1, ?)) as \"filmbyidinput\" (\"idx\", \"film_id\") "
                    + "using (\"film_id\")",
                "select \"filmcontents_c0\".\"content_type\" as \"__discriminator__\", "
                    + "\"filmcontents_c0\".\"title\", \"parentinput\".\"idx\" as \"__idx__\" "
                    + "from (values (0, ?), (1, ?)) as \"parentinput\" (\"idx\", \"film_id\") "
                    + "join \"public\".\"content\" as \"filmcontents_c0\" "
                    + "on \"filmcontents_c0\".\"film_id\" = \"parentinput\".\"film_id\" "
                    + "where \"filmcontents_c0\".\"content_type\" in (cast(? as \"public\".\"content_kind\"), cast(? as \"public\".\"content_kind\")) "
                    + "order by \"filmcontents_c0\".\"content_id\" asc");
    }

    @Test
    void singleBatchedChild_oneKeyRowSingleScatter() {
        execute("{ customerByPk(customerId: 1) { addressSplit { district } } }");
        assertThat(SQL_LOG)
            .as("single-cardinality batched child: the parent statement, then one batch "
                + "statement with a single VALUES key row")
            .containsExactly(
                "select \"public\".\"customer\".\"address_id\" "
                    + "from \"public\".\"customer\" "
                    + "where \"public\".\"customer\".\"customer_id\" = ?",
                "select \"addresssplit_a0\".\"district\", \"parentinput\".\"idx\" as \"__idx__\" "
                    + "from (values (0, ?)) as \"parentinput\" (\"idx\", \"address_id\") "
                    + "join \"public\".\"address\" as \"addresssplit_a0\" "
                    + "on \"addresssplit_a0\".\"address_id\" = \"parentinput\".\"address_id\"");
    }

    @Test
    void discriminatedConnectionBatchedChild_windowedReprojectionPartitionedByIdx() {
        execute("{ filmById(film_id: [\"1\"]) { filmContentsConnection(first: 2) "
            + "{ edges { node { title } } } } }");
        assertThat(SQL_LOG)
            .as("batched discriminated interface child, paginated: the participant-driven "
                + "re-projection (routing alias, branch $project, known-value IN) rides the "
                + "ranked page envelope, ROW_NUMBER() partitioned by the idx scatter key over "
                + "the base PK order, the page bound on the outer filter; one statement serves "
                + "every parent's page, which also pins that the extracted windowing fragment "
                + "serves both batched connection binders")
            .containsExactly(
                "select \"public\".\"film\".\"film_id\", \"public\".\"film\".\"title\", "
                    + "\"filmbyidinput\".\"idx\" as \"__idx__\" "
                    + "from \"public\".\"film\" "
                    + "join (values (0, ?)) as \"filmbyidinput\" (\"idx\", \"film_id\") using (\"film_id\")",
                "select \"ranked\".\"__discriminator__\", \"ranked\".\"title\", \"ranked\".\"content_id\", "
                    + "\"ranked\".\"__idx__\", \"ranked\".\"__rn__\" "
                    + "from (select \"filmcontentsconnection_c0\".\"content_type\" as \"__discriminator__\", "
                    + "\"filmcontentsconnection_c0\".\"title\", "
                    + "\"filmcontentsconnection_c0\".\"content_id\", "
                    + "\"parentinput\".\"idx\" as \"__idx__\", "
                    + "row_number() over (partition by \"parentinput\".\"idx\" "
                    + "order by \"filmcontentsconnection_c0\".\"content_id\" asc) as \"__rn__\" "
                    + "from (values (0, ?)) as \"parentinput\" (\"idx\", \"film_id\") "
                    + "join \"public\".\"content\" as \"filmcontentsconnection_c0\" "
                    + "on \"filmcontentsconnection_c0\".\"film_id\" = \"parentinput\".\"film_id\" "
                    + "where \"filmcontentsconnection_c0\".\"content_type\" in (cast(? as \"public\".\"content_kind\"), cast(? as \"public\".\"content_kind\")) "
                    + "order by \"filmcontentsconnection_c0\".\"content_id\" asc) as \"ranked\" "
                    + "where \"ranked\".\"__rn__\" <= ?");
    }

    @Test
    void connectionBatchedChild_rowNumberPartitionedByIdx() {
        execute("{ filmById(film_id: [\"1\"]) { actorsConnection(first: 2) { nodes { firstName } } } }");
        assertThat(SQL_LOG)
            .as("batched connection child: the parent (lookup) statement, then the per-parent "
                + "page envelope, ROW_NUMBER() partitioned by the idx scatter key with the page "
                + "bound riding the outer WHERE")
            .containsExactly(
                "select \"public\".\"film\".\"film_id\", \"filmbyidinput\".\"idx\" as \"__idx__\" "
                    + "from \"public\".\"film\" "
                    + "join (values (0, ?)) as \"filmbyidinput\" (\"idx\", \"film_id\") using (\"film_id\")",
                "select \"ranked\".\"first_name\", \"ranked\".\"actor_id\", \"ranked\".\"__idx__\", \"ranked\".\"__rn__\" "
                    + "from (select \"actorsconnection_a1\".\"first_name\", \"actorsconnection_a1\".\"actor_id\", "
                    + "\"parentinput\".\"idx\" as \"__idx__\", "
                    + "row_number() over (partition by \"parentinput\".\"idx\" "
                    + "order by \"actorsconnection_a1\".\"actor_id\" asc) as \"__rn__\" "
                    + "from (values (0, ?)) as \"parentinput\" (\"idx\", \"film_id\") "
                    + "join \"public\".\"film_actor\" as \"actorsconnection_f0\" "
                    + "on \"actorsconnection_f0\".\"film_id\" = \"parentinput\".\"film_id\" "
                    + "join \"public\".\"actor\" as \"actorsconnection_a1\" "
                    + "on \"actorsconnection_f0\".\"actor_id\" = \"actorsconnection_a1\".\"actor_id\" "
                    + "order by \"actorsconnection_a1\".\"actor_id\" asc) as \"ranked\" "
                    + "where \"ranked\".\"__rn__\" <= ?");
    }

    @Test
    void lookupBatchedChild_tableArm_lookupInputValuesJoinNarrowsTheBatch() {
        execute("{ filmById(film_id: [\"1\", \"2\"]) { actorsBySplitLookup(actor_id: [1, 2]) { firstName } } }");
        assertThat(SQL_LOG)
            .as("table-arm batched lookup child: the parent (lookup) statement, then one batch "
                + "statement with both VALUES derived tables, the parent-input keyed for scatter "
                + "and the lookup-input narrowing on the @lookupKey columns, carrying the same "
                + "batch-wide ORDER BY the plain batched sibling carries (@lookupKey is a "
                + "narrowing, not an axis a declared sort turns on)")
            .containsExactly(
                "select \"public\".\"film\".\"film_id\", \"filmbyidinput\".\"idx\" as \"__idx__\" "
                    + "from \"public\".\"film\" "
                    + "join (values (0, ?), (1, ?)) as \"filmbyidinput\" (\"idx\", \"film_id\") using (\"film_id\")",
                "select \"actorsbysplitlookup_a1\".\"first_name\", \"parentinput\".\"idx\" as \"__idx__\" "
                    + "from (values (0, ?), (1, ?)) as \"parentinput\" (\"idx\", \"film_id\") "
                    + "join \"public\".\"film_actor\" as \"actorsbysplitlookup_f0\" "
                    + "on \"actorsbysplitlookup_f0\".\"film_id\" = \"parentinput\".\"film_id\" "
                    + "join \"public\".\"actor\" as \"actorsbysplitlookup_a1\" "
                    + "on \"actorsbysplitlookup_f0\".\"actor_id\" = \"actorsbysplitlookup_a1\".\"actor_id\" "
                    + "join (values (0, ?), (1, ?)) as \"actorsbysplitlookupinput\" (\"idx\", \"actor_id\") "
                    + "on \"actorsbysplitlookup_a1\".\"actor_id\" = \"actorsbysplitlookupinput\".\"actor_id\" "
                    + "order by \"actorsbysplitlookup_a1\".\"actor_id\" asc");
    }

    @Test
    void lookupBatchedChild_recordArm_sameBatchShapeKeyedOffTheBackingRecord() {
        execute("{ filmDetailsBatch(ids: [1, 2]) { filmId actorsByLookup(actor_id: [1, 2]) { firstName } } }");
        assertThat(SQL_LOG)
            .as("record-arm batched lookup child: the service root's own statement, then one "
                + "batch statement of the same two-VALUES shape, keyed off the backing record's "
                + "film_id rather than a jOOQ table row (the inline nesting through filmById "
                + "would fold the lookup into the parent's multiset instead)")
            .containsExactly(
                "select \"public\".\"film\".\"film_id\", \"public\".\"film\".\"title\", "
                    + "\"public\".\"film\".\"description\", \"public\".\"film\".\"release_year\", "
                    + "\"public\".\"film\".\"language_id\", \"public\".\"film\".\"original_language_id\", "
                    + "\"public\".\"film\".\"rental_duration\", \"public\".\"film\".\"rental_rate\", "
                    + "\"public\".\"film\".\"length\", \"public\".\"film\".\"replacement_cost\", "
                    + "\"public\".\"film\".\"rating\", \"public\".\"film\".\"text_rating\", "
                    + "\"public\".\"film\".\"last_update\" "
                    + "from \"public\".\"film\" "
                    + "where \"public\".\"film\".\"film_id\" in (?, ?)",
                "select \"actorsbylookup_a1\".\"first_name\", \"parentinput\".\"idx\" as \"__idx__\" "
                    + "from (values (0, ?), (1, ?)) as \"parentinput\" (\"idx\", \"film_id\") "
                    + "join \"public\".\"film_actor\" as \"actorsbylookup_f0\" "
                    + "on \"actorsbylookup_f0\".\"film_id\" = \"parentinput\".\"film_id\" "
                    + "join \"public\".\"actor\" as \"actorsbylookup_a1\" "
                    + "on \"actorsbylookup_f0\".\"actor_id\" = \"actorsbylookup_a1\".\"actor_id\" "
                    + "join (values (0, ?), (1, ?)) as \"actorsbylookupinput\" (\"idx\", \"actor_id\") "
                    + "on \"actorsbylookup_a1\".\"actor_id\" = \"actorsbylookupinput\".\"actor_id\" "
                    + "order by \"actorsbylookup_a1\".\"actor_id\" asc");
    }

    @Test
    void pivotBatchedChild_keyPreservingLeftJoinGroupedByIdx() {
        execute("{ filmById(film_id: [\"1\", \"2\"]) { titleTranslationsSplit { nn nb } } }");
        assertThat(SQL_LOG)
            .as("batched pivot child: the parent (lookup) statement, then one batch statement "
                + "left-joining the attribute table from the parent-input VALUES table "
                + "(key-preserving: a row-less parent keeps its group) with the filtered "
                + "aggregates grouped by the idx scatter key")
            .containsExactly(
                "select \"public\".\"film\".\"film_id\", \"filmbyidinput\".\"idx\" as \"__idx__\" "
                    + "from \"public\".\"film\" "
                    + "join (values (0, ?), (1, ?)) as \"filmbyidinput\" (\"idx\", \"film_id\") using (\"film_id\")",
                "select max(\"titletranslationssplit_f0\".\"title_txt\") "
                    + "filter (where \"titletranslationssplit_f0\".\"lang_code\" = 'nno') as \"nn\", "
                    + "max(\"titletranslationssplit_f0\".\"title_txt\") "
                    + "filter (where \"titletranslationssplit_f0\".\"lang_code\" = 'nob') as \"nb\", "
                    + "\"parentinput\".\"idx\" as \"__idx__\" "
                    + "from (values (0, ?), (1, ?)) as \"parentinput\" (\"idx\", \"film_id\") "
                    + "left outer join \"public\".\"film_translation\" as \"titletranslationssplit_f0\" "
                    + "on \"titletranslationssplit_f0\".\"film_id\" = \"parentinput\".\"film_id\" "
                    + "group by \"parentinput\".\"idx\"");
    }

    @Test
    void serviceTableLift_identityJoinReprojectionOrderedByServiceFlattenOrder() {
        execute("{ filmById(film_id: [\"1\"]) { castMembers { actorId } } }");
        assertThat(SQL_LOG)
            .as("service table lift: the parent (lookup) statement, the service's own fixture "
                + "statement, then the lift's re-projection joining the returned records' PKs "
                + "by identity through the (idx, seq, pk...) VALUES table, ordered by the seq "
                + "column so each parent bucket keeps the service's flatten order")
            .containsExactly(
                "select \"public\".\"film\".\"film_id\", \"filmbyidinput\".\"idx\" as \"__idx__\" "
                    + "from \"public\".\"film\" "
                    + "join (values (0, ?)) as \"filmbyidinput\" (\"idx\", \"film_id\") using (\"film_id\")",
                "select \"public\".\"film_actor\".\"actor_id\", \"public\".\"film_actor\".\"film_id\", "
                    + "\"public\".\"film_actor\".\"last_update\" "
                    + "from \"public\".\"film_actor\" "
                    + "where \"public\".\"film_actor\".\"film_id\" in (?) "
                    + "order by \"public\".\"film_actor\".\"actor_id\"",
                "select \"castmembers\".\"actor_id\", \"projectioninput\".\"idx\" as \"__idx__\" "
                    + "from \"public\".\"film_actor\" as \"castmembers\" "
                    + "join (values (0, 0, ?, ?), (0, 1, ?, ?)) as \"projectioninput\" "
                    + "(\"idx\", \"seq\", \"actor_id\", \"film_id\") "
                    + "on (\"castmembers\".\"actor_id\" = \"projectioninput\".\"actor_id\" "
                    + "and \"castmembers\".\"film_id\" = \"projectioninput\".\"film_id\") "
                    + "order by \"projectioninput\".\"seq\"");
    }

    @Test
    void polymorphicListChild_narrowUnionThenPerTypenameValuesJoins() {
        execute("{ customerByPk(customerId: 1) { addressSplit { occupants { "
            + "__typename ... on Customer { customerId } ... on Staff { staffId } } } } }");
        assertThat(SQL_LOG)
            .as("polymorphic list batched child: the parent statement, the addressSplit batch "
                + "statement, then the stage-1 narrow UNION ALL over the participant tables "
                + "joined to the parent-input VALUES table, then one stage-2 per-typename "
                + "VALUES-join re-projection per typename present in stage 1 (the seeded "
                + "address has customer occupants only, so the staff stage-2 is skipped by "
                + "its empty-bindings gate)")
            .containsExactly(
                "select \"public\".\"customer\".\"address_id\", \"public\".\"customer\".\"customer_id\" "
                    + "from \"public\".\"customer\" "
                    + "where \"public\".\"customer\".\"customer_id\" = ?",
                "select \"addresssplit_a0\".\"address_id\", \"parentinput\".\"idx\" as \"__idx__\" "
                    + "from (values (0, ?)) as \"parentinput\" (\"idx\", \"address_id\") "
                    + "join \"public\".\"address\" as \"addresssplit_a0\" "
                    + "on \"addresssplit_a0\".\"address_id\" = \"parentinput\".\"address_id\"",
                "select 'customer' as \"__typename\", "
                    + "\"public\".\"customer\".\"customer_id\" as \"__pk0__\", "
                    + "\"parentinput\".\"idx\" as \"__idx__\" "
                    + "from \"public\".\"customer\" "
                    + "join (values (0, ?)) as \"parentinput\" (\"idx\", \"address_id\") "
                    + "on \"public\".\"customer\".\"address_id\" = \"parentinput\".\"address_id\" "
                    + "union all "
                    + "select 'staff' as \"__typename\", "
                    + "\"public\".\"staff\".\"staff_id\" as \"__pk0__\", "
                    + "\"parentinput\".\"idx\" as \"__idx__\" "
                    + "from \"public\".\"staff\" "
                    + "join (values (0, ?)) as \"parentinput\" (\"idx\", \"address_id\") "
                    + "on \"public\".\"staff\".\"address_id\" = \"parentinput\".\"address_id\"",
                "select \"public\".\"customer\".\"customer_id\", 'customer' as \"__typename\", "
                    + "\"customerinput\".\"idx\" "
                    + "from \"public\".\"customer\" "
                    + "join (values (?, ?), (?, ?)) as \"customerinput\" (\"idx\", \"customer_id\") "
                    + "on \"public\".\"customer\".\"customer_id\" = \"customerinput\".\"customer_id\" "
                    + "order by \"customerinput\".\"idx\"");
    }

    @Test
    void polymorphicConnectionChild_rankedUnionPagesPartitionedByIdx() {
        execute("{ customerByPk(customerId: 1) { addressSplit { occupantsConnection(first: 2) { "
            + "nodes { __typename ... on Customer { customerId } ... on Staff { staffId } } } } } }");
        assertThat(SQL_LOG)
            .as("polymorphic connection batched child: the parent statement, the addressSplit "
                + "batch statement, then the stage-1 union wrapped as the pages derived table "
                + "with ROW_NUMBER() partitioned by the idx scatter key and the page bound on "
                + "the outer WHERE, then the per-typename stage-2 re-projections carrying the "
                + "__sort__ key")
            .containsExactly(
                "select \"public\".\"customer\".\"address_id\", \"public\".\"customer\".\"customer_id\" "
                    + "from \"public\".\"customer\" "
                    + "where \"public\".\"customer\".\"customer_id\" = ?",
                "select \"addresssplit_a0\".\"address_id\", \"parentinput\".\"idx\" as \"__idx__\" "
                    + "from (values (0, ?)) as \"parentinput\" (\"idx\", \"address_id\") "
                    + "join \"public\".\"address\" as \"addresssplit_a0\" "
                    + "on \"addresssplit_a0\".\"address_id\" = \"parentinput\".\"address_id\"",
                "select \"ranked\".\"__typename\", \"ranked\".\"__pk0__\", \"ranked\".\"__sort__\", "
                    + "\"ranked\".\"__idx__\", \"ranked\".\"__rn__\" "
                    + "from (select \"__typename\", \"__pk0__\", \"__sort__\", \"__idx__\", "
                    + "row_number() over (partition by \"__idx__\" "
                    + "order by \"__sort__\" asc, \"__typename\" asc) as \"__rn__\" "
                    + "from (select 'customer' as \"__typename\", "
                    + "\"public\".\"customer\".\"customer_id\" as \"__pk0__\", "
                    + "\"public\".\"customer\".\"customer_id\" as \"__sort__\", "
                    + "\"parentinput\".\"idx\" as \"__idx__\" "
                    + "from \"public\".\"customer\" "
                    + "join (values (0, ?)) as \"parentinput\" (\"idx\", \"address_id\") "
                    + "on \"public\".\"customer\".\"address_id\" = \"parentinput\".\"address_id\" "
                    + "union all "
                    + "select 'staff' as \"__typename\", "
                    + "\"public\".\"staff\".\"staff_id\" as \"__pk0__\", "
                    + "\"public\".\"staff\".\"staff_id\" as \"__sort__\", "
                    + "\"parentinput\".\"idx\" as \"__idx__\" "
                    + "from \"public\".\"staff\" "
                    + "join (values (0, ?)) as \"parentinput\" (\"idx\", \"address_id\") "
                    + "on \"public\".\"staff\".\"address_id\" = \"parentinput\".\"address_id\") as \"pages\" "
                    + "order by \"__sort__\" asc, \"__typename\" asc) as \"ranked\" "
                    + "where \"ranked\".\"__rn__\" <= ?",
                "select \"public\".\"customer\".\"customer_id\", 'customer' as \"__typename\", "
                    + "\"public\".\"customer\".\"customer_id\" as \"__sort__\", "
                    + "\"customerinput\".\"idx\" "
                    + "from \"public\".\"customer\" "
                    + "join (values (?, ?), (?, ?)) as \"customerinput\" (\"idx\", \"customer_id\") "
                    + "on \"public\".\"customer\".\"customer_id\" = \"customerinput\".\"customer_id\" "
                    + "order by \"customerinput\".\"idx\"");
    }

    @Test
    void polymorphicLoadManyChild_accessorManyKeysSingleUnionBatch() {
        execute("{ occupantsBatch { occupants { "
            + "__typename ... on Customer { customerId } ... on Staff { staffId } } } }");
        assertThat(SQL_LOG)
            .as("polymorphic list batched child on an accessor-many record parent (the "
                + "loader.loadMany dispatch): the service produces the payload records "
                + "Java-side (no SQL of its own), then one stage-1 union batch over all "
                + "flattened accessor keys, then the per-typename stage-2 re-projections")
            .containsExactly(
                "select 'customer' as \"__typename\", "
                    + "\"public\".\"customer\".\"customer_id\" as \"__pk0__\", "
                    + "\"parentinput\".\"idx\" as \"__idx__\" "
                    + "from \"public\".\"customer\" "
                    + "join (values (0, ?), (1, ?), (2, ?)) as \"parentinput\" (\"idx\", \"address_id\") "
                    + "on \"public\".\"customer\".\"address_id\" = \"parentinput\".\"address_id\" "
                    + "union all "
                    + "select 'staff' as \"__typename\", "
                    + "\"public\".\"staff\".\"staff_id\" as \"__pk0__\", "
                    + "\"parentinput\".\"idx\" as \"__idx__\" "
                    + "from \"public\".\"staff\" "
                    + "join (values (0, ?), (1, ?), (2, ?)) as \"parentinput\" (\"idx\", \"address_id\") "
                    + "on \"public\".\"staff\".\"address_id\" = \"parentinput\".\"address_id\"",
                "select \"public\".\"customer\".\"customer_id\", 'customer' as \"__typename\", "
                    + "\"customerinput\".\"idx\" "
                    + "from \"public\".\"customer\" "
                    + "join (values (?, ?), (?, ?), (?, ?), (?, ?), (?, ?)) "
                    + "as \"customerinput\" (\"idx\", \"customer_id\") "
                    + "on \"public\".\"customer\".\"customer_id\" = \"customerinput\".\"customer_id\" "
                    + "order by \"customerinput\".\"idx\"",
                "select \"public\".\"staff\".\"staff_id\", 'staff' as \"__typename\", "
                    + "\"staffinput\".\"idx\" "
                    + "from \"public\".\"staff\" "
                    + "join (values (?, ?), (?, ?)) as \"staffinput\" (\"idx\", \"staff_id\") "
                    + "on \"public\".\"staff\".\"staff_id\" = \"staffinput\".\"staff_id\" "
                    + "order by \"staffinput\".\"idx\"");
    }

    private static void execute(String query) {
        var input = Graphitron.newExecutionInput(dsl, "{}", "test-user").query(query).build();
        var result = graphql.execute(input);
        assertThat(result.getErrors()).as("query must execute cleanly; SQL pins compare "
            + "statements, not error paths").isEmpty();
        Map<String, Object> data = result.getData();
        assertThat(data).isNotEmpty();
    }
}
