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
