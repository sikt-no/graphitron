package no.sikt.graphitron.rewrite.test.querydb;

import graphql.GraphQL;
import no.sikt.graphitron.generated.Graphitron;
import no.sikt.graphitron.generated.util.NodeIdEncoder;
import no.sikt.graphitron.rewrite.test.tier.ExecutionTier;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Execution-tier semantics for a translated FK-target {@code @nodeId} filter: the rows that come
 * back are the right rows.
 *
 * <p>The shape is a child whose foreign key targets a column of the parent that is <em>not</em> the
 * parent's node key. {@code xlat_child.parent_alt_key} references {@code xlat_parent(alt_key)} while
 * {@code XlatParent}'s node key is {@code pk_id}, so decoding an incoming id yields a {@code pk_id}
 * value that {@code xlat_child} holds in no column. SQL has to translate: reach {@code xlat_parent}
 * through the FK, then compare its {@code pk_id}. The emitter does that with a correlated
 * {@code EXISTS}, which is also the semantically right shape here (no row multiplication when the
 * path is non-unique, and a NULL FK column simply fails the correlation).
 *
 * <p>What this tier uniquely pins is that translation, not the SQL shape. The lowering to a
 * {@code BodyParam.RemoteColumnPredicate} is pinned more cheaply at the pipeline tier
 * ({@code NodeIdPipelineTest}'s translated-FK cases, and
 * {@code ReferenceFilterRemoteColumnPipelineTest} for the same machinery under a plain
 * {@code @reference}); the SQL-token assertions below are a thin structural cross-check that the
 * predicate really went through a subquery on the parent rather than degenerating into a local
 * comparison that happened to return the same rows on this seed data.
 *
 * <p>Seed data ({@code init.sql}): parent {@code P1} (alt key {@code AK-1}) has children
 * {@code C1}, {@code C2}; {@code P2} ({@code AK-2}) has {@code C3}; {@code P3} has none. The
 * composite pair mirrors it: {@code (A1,B1)} has {@code CC1}, {@code CC2}; {@code (A2,B2)} has
 * {@code CC3}.
 *
 * <p>The class also carries the two shapes that reach the same binding without any translation: a
 * junction chain through {@code film_actor} and a single hop traversed against its foreign key's
 * direction. Those are where the reach is non-unique, which the {@code xlat} pairs are not, so they
 * are what turns the no-row-multiplication claim into a count. Their cases sit at the bottom with
 * their own seed-data note.
 */
@ExecutionTier
class TranslatedFkTargetFilterExecutionTest {

    static PostgreSQLContainer postgres;
    static DSLContext dsl;
    static GraphQL graphql;
    static final List<String> SQL_LOG = new java.util.concurrent.CopyOnWriteArrayList<>();

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
                    if (sql != null) SQL_LOG.add(sql.toLowerCase(Locale.ROOT));
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

    @SuppressWarnings("unchecked")
    private Map<String, Object> execute(String query) {
        var input = Graphitron.newExecutionInput(dsl, "{}", "test-user").query(query).build();
        var result = graphql.execute(input);
        assertThat(result.getErrors()).isEmpty();
        return result.getData();
    }

    @SuppressWarnings("unchecked")
    private List<String> childIds(Map<String, Object> data, String field) {
        return ((List<Map<String, Object>>) data.get(field)).stream()
            .map(r -> (String) r.get("childId"))
            .toList();
    }

    @Test
    void listArgument_filtersChildrenByParentNodeIds() {
        // Two parents, three of the four possible children: the result must be exactly P1's and
        // P2's children, which is also the whole table here — so the narrowing cases below carry
        // the real weight and this case pins that translation returns rows at all.
        Map<String, Object> data = execute("""
            { xlatChildrenByParentIds(parentIds: ["%s", "%s"]) { childId note } }
            """.formatted(
                NodeIdEncoder.encode("XlatParent", "P1"),
                NodeIdEncoder.encode("XlatParent", "P2")));

        assertThat(childIds(data, "xlatChildrenByParentIds"))
            .containsExactlyInAnyOrder("C1", "C2", "C3");
        // Structural cross-check: the predicate went through a subquery on the parent table and
        // compared its node key, rather than comparing anything on xlat_child directly.
        assertThat(SQL_LOG)
            .as("the filter lowers to a correlated EXISTS over xlat_parent comparing pk_id")
            .anyMatch(s -> s.contains("exists") && s.contains("xlat_parent") && s.contains("pk_id"));
    }

    @Test
    void listArgument_narrowsToOneParentsChildren() {
        Map<String, Object> data = execute("""
            { xlatChildrenByParentIds(parentIds: ["%s"]) { childId } }
            """.formatted(NodeIdEncoder.encode("XlatParent", "P1")));

        assertThat(childIds(data, "xlatChildrenByParentIds"))
            .as("only P1's children; C3 belongs to P2 and must be excluded")
            .containsExactlyInAnyOrder("C1", "C2");
    }

    @Test
    void listArgument_parentWithNoChildren_returnsEmpty() {
        Map<String, Object> data = execute("""
            { xlatChildrenByParentIds(parentIds: ["%s"]) { childId } }
            """.formatted(NodeIdEncoder.encode("XlatParent", "P3")));

        assertThat(childIds(data, "xlatChildrenByParentIds")).isEmpty();
    }

    @Test
    void listArgument_emptyList_contributesNoConjunct() {
        // An empty IN () would render constant-false and zero the query; the empty-list guard must
        // omit the conjunct instead, leaving every child row.
        Map<String, Object> data = execute("{ xlatChildrenByParentIds(parentIds: []) { childId } }");

        assertThat(childIds(data, "xlatChildrenByParentIds"))
            .as("an empty id list narrows by nothing")
            .containsExactlyInAnyOrder("C1", "C2", "C3");
    }

    @Test
    void listArgument_omittedArgument_contributesNoConjunct() {
        Map<String, Object> data = execute("{ xlatChildrenByParentIds { childId } }");

        assertThat(childIds(data, "xlatChildrenByParentIds"))
            .containsExactlyInAnyOrder("C1", "C2", "C3");
    }

    @Test
    void scalarArgument_filtersByOneParentNodeId() {
        // The scalar rail: an Eq inner rather than an In, inside the same EXISTS.
        Map<String, Object> data = execute("""
            { xlatChildByParentId(parentId: "%s") { childId } }
            """.formatted(NodeIdEncoder.encode("XlatParent", "P2")));

        assertThat(childIds(data, "xlatChildByParentId")).containsExactly("C3");
        assertThat(SQL_LOG)
            .anyMatch(s -> s.contains("exists") && s.contains("xlat_parent"));
    }

    @Test
    void inputFieldForm_filtersByParentNodeIds() {
        // Same filter delivered through an input object, so the carrier comes from the input-field
        // walk rather than the argument walk. Both must translate identically.
        Map<String, Object> data = execute("""
            { xlatChildrenByFilter(filter: { parentIds: ["%s"] }) { childId } }
            """.formatted(NodeIdEncoder.encode("XlatParent", "P1")));

        assertThat(childIds(data, "xlatChildrenByFilter"))
            .containsExactlyInAnyOrder("C1", "C2");
        assertThat(SQL_LOG)
            .anyMatch(s -> s.contains("exists") && s.contains("xlat_parent") && s.contains("pk_id"));
    }

    @Test
    void inputFieldForm_omittedLeaf_contributesNoConjunct() {
        Map<String, Object> data = execute("{ xlatChildrenByFilter(filter: {}) { childId } }");

        assertThat(childIds(data, "xlatChildrenByFilter"))
            .containsExactlyInAnyOrder("C1", "C2", "C3");
    }

    @Test
    void compositeKey_filtersChildrenByCompositeParentNodeIds() {
        // Two key slots: the decode yields a Row2 and the correlation ANDs both FK columns. The
        // parent's node key is (pk_a, pk_b) while the FK targets (alt_a, alt_b), so both slots
        // translate.
        Map<String, Object> data = execute("""
            { xlatCompChildrenByParentIds(parentIds: ["%s"]) { childId } }
            """.formatted(NodeIdEncoder.encode("XlatCompParent", "A1", "B1")));

        assertThat(childIds(data, "xlatCompChildrenByParentIds"))
            .as("only (A1,B1)'s children; CC3 belongs to (A2,B2)")
            .containsExactlyInAnyOrder("CC1", "CC2");
        assertThat(SQL_LOG)
            .as("both composite node-key columns are compared inside the EXISTS")
            .anyMatch(s -> s.contains("exists") && s.contains("pk_a") && s.contains("pk_b"));
    }

    @Test
    void compositeKey_bothParents_returnsEveryChild() {
        Map<String, Object> data = execute("""
            { xlatCompChildrenByParentIds(parentIds: ["%s", "%s"]) { childId } }
            """.formatted(
                NodeIdEncoder.encode("XlatCompParent", "A1", "B1"),
                NodeIdEncoder.encode("XlatCompParent", "A2", "B2")));

        assertThat(childIds(data, "xlatCompChildrenByParentIds"))
            .containsExactlyInAnyOrder("CC1", "CC2", "CC3");
    }

    // ===== The junction chain and the single reverse hop =====
    //
    // Same remote binding, reached because a key column lands on no column of the row's own table
    // rather than because a foreign key points at the wrong unique key. These two shapes are where
    // the reach is non-unique, so they are where "the EXISTS multiplies nothing" stops being an
    // argument about SQL and becomes a row count only PostgreSQL can settle.
    //
    // Seed data (init.sql): film_actor casts film 1 as (PENELOPE, NICK), film 2 as (PENELOPE, ED),
    // film 3 as (PENELOPE), film 4 as (NICK), film 5 as (ED). Actor 4 (JOAN) is cast in nothing,
    // which is the row a semi-join applied without a value drops. Customers 1 and 4 share address 1,
    // customers 2 and 5 share address 2, customer 3 has address 3, and address 4 has no occupant.

    @SuppressWarnings("unchecked")
    private List<Integer> ints(Map<String, Object> data, String field, String key) {
        return ((List<Map<String, Object>>) data.get(field)).stream()
            .map(r -> (Integer) r.get(key))
            .toList();
    }

    @Test
    void junctionChain_actorCastInTwoRequestedFilms_comesBackOnce() {
        // The claim the tier exists for. PENELOPE (actor 1) is cast in both requested films, so a
        // predicate that joined the junction instead of asking whether a junction row exists would
        // return her twice. containsExactlyInAnyOrder is multiset equality, so a duplicate fails it.
        Map<String, Object> data = execute("""
            { actorsByFilmIds(filmIds: ["%s", "%s"]) { actorId firstName } }
            """.formatted(
                NodeIdEncoder.encode("Film", 1),
                NodeIdEncoder.encode("Film", 2)));

        assertThat(ints(data, "actorsByFilmIds", "actorId"))
            .as("actor 1 is cast in both films and must appear exactly once")
            .containsExactlyInAnyOrder(1, 2, 3);
        assertThat(SQL_LOG)
            .as("the chain lowers to a correlated EXISTS reaching film through film_actor")
            .anyMatch(s -> s.contains("exists") && s.contains("film_actor") && s.contains("film_id"));
    }

    @Test
    void junctionChain_narrowsToOneFilmsCast() {
        Map<String, Object> data = execute("""
            { actorsByFilmIds(filmIds: ["%s"]) { actorId } }
            """.formatted(NodeIdEncoder.encode("Film", 4)));

        assertThat(ints(data, "actorsByFilmIds", "actorId"))
            .as("only film 4's cast; the other two actors are cast in other films")
            .containsExactly(2);
    }

    @Test
    void junctionChain_inputFieldForm_returnsTheSameRows() {
        Map<String, Object> data = execute("""
            { actorsByFilmFilter(filter: { filmIds: ["%s", "%s"] }) { actorId } }
            """.formatted(
                NodeIdEncoder.encode("Film", 1),
                NodeIdEncoder.encode("Film", 2)));

        assertThat(ints(data, "actorsByFilmFilter", "actorId"))
            .as("actor 4 is cast in nothing, so a supplied filter excludes it")
            .containsExactlyInAnyOrder(1, 2, 3);
    }

    @Test
    void junctionChain_emptyList_contributesNoConjunct() {
        Map<String, Object> data = execute("{ actorsByFilmIds(filmIds: []) { actorId } }");

        assertThat(ints(data, "actorsByFilmIds", "actorId"))
            .as("an empty id list narrows by nothing")
            .containsExactlyInAnyOrder(1, 2, 3, 4);
    }

    // ===== The absent filter value =====
    //
    // The reported field defect, at the coordinate it was reported against. A @reference path on an
    // optional filter field lowers to a correlated EXISTS, which is a semi-join: applied while the
    // field carries no value, the query stops meaning "the whole collection" and starts meaning
    // "the part of the collection that has the relation at all", with no error and no warning.
    // Actor 4 is cast in no film, so it is exactly the row that disappears; each case below asserts
    // it comes back. Three spellings of "no value" because a client can send any of them and they
    // mean the same thing: the argument omitted, an empty input object, and an explicit null leaf.

    @Test
    void junctionChain_inputFieldForm_omittedArgument_contributesNoConjunct() {
        Map<String, Object> data = execute("{ actorsByFilmFilter { actorId } }");

        assertThat(ints(data, "actorsByFilmFilter", "actorId"))
            .as("an omitted filter returns the whole collection, the uncast actor included")
            .containsExactlyInAnyOrder(1, 2, 3, 4);
        assertThat(SQL_LOG)
            .as("no value, no conjunct: the semi-join is not in the statement at all")
            .noneMatch(s -> s.contains("exists"));
    }

    @Test
    void junctionChain_inputFieldForm_emptyFilterObject_contributesNoConjunct() {
        Map<String, Object> data = execute("{ actorsByFilmFilter(filter: {}) { actorId } }");

        assertThat(ints(data, "actorsByFilmFilter", "actorId"))
            .containsExactlyInAnyOrder(1, 2, 3, 4);
    }

    @Test
    void junctionChain_inputFieldForm_explicitNullLeaf_contributesNoConjunct() {
        Map<String, Object> data = execute("{ actorsByFilmFilter(filter: { filmIds: null }) { actorId } }");

        assertThat(ints(data, "actorsByFilmFilter", "actorId"))
            .containsExactlyInAnyOrder(1, 2, 3, 4);
    }

    @Test
    void junctionChain_inputFieldForm_emptyLeafList_contributesNoConjunct() {
        Map<String, Object> data = execute("{ actorsByFilmFilter(filter: { filmIds: [] }) { actorId } }");

        assertThat(ints(data, "actorsByFilmFilter", "actorId"))
            .as("an empty list is no value, the same as the argument form's empty list")
            .containsExactlyInAnyOrder(1, 2, 3, 4);
    }

    @Test
    void junctionChain_connectionForm_omittedFilter_countsTheWholeCollection() {
        // The report measured the drift on totalCount, which is the count path composing the same
        // condition: a semi-join applied there under-reports the collection's size, and a consumer
        // paging through the result sees a number that does not match what is there.
        Map<String, Object> data = execute(
            "{ actorsByFilmFilterConnection { totalCount nodes { actorId } } }");

        @SuppressWarnings("unchecked")
        var connection = (Map<String, Object>) data.get("actorsByFilmFilterConnection");
        assertThat(connection.get("totalCount"))
            .as("every actor counts, including the one cast in no film")
            .isEqualTo(4);
        assertThat(ints(connection, "nodes", "actorId")).containsExactlyInAnyOrder(1, 2, 3, 4);
    }

    @Test
    void junctionChain_connectionForm_suppliedFilter_countsTheNarrowedCollection() {
        Map<String, Object> data = execute("""
            { actorsByFilmFilterConnection(filter: { filmIds: ["%s"] }) { totalCount nodes { actorId } } }
            """.formatted(NodeIdEncoder.encode("Film", 4)));

        @SuppressWarnings("unchecked")
        var connection = (Map<String, Object>) data.get("actorsByFilmFilterConnection");
        assertThat(connection.get("totalCount"))
            .as("with a value the filter narrows, and the count follows the page")
            .isEqualTo(1);
        assertThat(ints(connection, "nodes", "actorId")).containsExactly(2);
    }

    @Test
    void reverseHop_addressSharedByTwoCustomers_comesBackOnce() {
        // One hop, traversed against the foreign key's direction: address holds no customer_id, so
        // the decoded key binds customer.customer_id inside the EXISTS. Customers 1 and 4 share
        // address 1, which is the same non-uniqueness the junction has with one fewer hop.
        Map<String, Object> data = execute("""
            { addressesByCustomerIds(customerIds: ["%s", "%s"]) { addressId district } }
            """.formatted(
                NodeIdEncoder.encode("Customer", 1),
                NodeIdEncoder.encode("Customer", 4)));

        assertThat(ints(data, "addressesByCustomerIds", "addressId"))
            .as("both customers live at address 1, which must appear exactly once")
            .containsExactly(1);
        assertThat(SQL_LOG)
            .anyMatch(s -> s.contains("exists") && s.contains("customer"));
    }

    @Test
    void reverseHop_narrowsToOneCustomersAddress_andLeavesTheUnoccupiedOne() {
        // Address 4 has no occupant, so nothing satisfies the correlation for it. That absence is
        // the other half of the EXISTS claim: a parent with no matching row is dropped rather than
        // returned with nulls.
        Map<String, Object> data = execute("""
            { addressesByCustomerIds(customerIds: ["%s"]) { addressId } }
            """.formatted(NodeIdEncoder.encode("Customer", 3)));

        assertThat(ints(data, "addressesByCustomerIds", "addressId")).containsExactly(3);
    }

    @Test
    void malformedId_surfacesClientError() {
        // The authored filter decodes with throw-on-mismatch semantics: an unparseable id is a
        // client mistake, not a silently empty narrowing.
        var input = Graphitron.newExecutionInput(dsl, "{}", "test-user")
            .query("{ xlatChildrenByParentIds(parentIds: [\"not-a-node-id\"]) { childId } }")
            .build();
        var result = graphql.execute(input);
        assertThat(result.getErrors()).isNotEmpty();
    }

    @Test
    void wrongTypeId_surfacesClientError() {
        // A well-formed id of the wrong node type throws too, and names the expected type.
        var input = Graphitron.newExecutionInput(dsl, "{}", "test-user")
            .query("{ xlatChildrenByParentIds(parentIds: [\"%s\"]) { childId } }"
                .formatted(NodeIdEncoder.encode("Film", 1)))
            .build();
        var result = graphql.execute(input);
        assertThat(result.getErrors()).isNotEmpty();
        assertThat(result.getErrors().get(0).getMessage()).contains("XlatParent");
    }
}
