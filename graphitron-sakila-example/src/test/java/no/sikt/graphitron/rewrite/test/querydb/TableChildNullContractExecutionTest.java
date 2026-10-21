package no.sikt.graphitron.rewrite.test.querydb;

import graphql.ExecutionResult;
import graphql.GraphQL;
import no.sikt.graphitron.generated.Graphitron;
import no.sikt.graphitron.rewrite.test.tier.ExecutionTier;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The null contract of a nullable single-object child against a real PostgreSQL database: a child
 * with a table of its own, inline or {@code @splitQuery}, resolves to {@code null} when no row
 * matches or every column of its row is null; a nested object of references resolves to
 * {@code null} when no referenced row has a value, and one holding an argument-free list when its
 * columns are null and the list is empty, {@code @splitQuery} or not. Each is decided over every
 * field of the type, whatever the query selects. Fixture: {@code PresenceHost} over
 * {@code presence_host}, whose five hosts {@code init.sql} lays out: host 1 has a full detail row
 * and an item, host 2 a detail row of nulls and no items, host 3 no detail row and no items, host
 * 4 a detail row with only {@code city}, host 5 a detail row of nulls and an item.
 */
@ExecutionTier
@SuppressWarnings("unchecked")
class TableChildNullContractExecutionTest {

    static PostgreSQLContainer postgres;
    static DSLContext dsl;
    static GraphQL graphql;
    static final AtomicInteger STATEMENT_COUNT = new AtomicInteger();

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
                    STATEMENT_COUNT.incrementAndGet();
                }
            }));
        graphql = Graphitron.newGraphQL().build();
    }

    @AfterAll
    static void stopDatabase() {
        if (postgres != null) postgres.stop();
    }

    private ExecutionResult execute(String query) {
        var input = Graphitron.newExecutionInput(dsl, "{}", "test-user").query(query).build();
        return graphql.execute(input);
    }

    /** The hosts keyed by {@code hostId}, each carrying {@code selection}. */
    private Map<Integer, Map<String, Object>> hosts(String selection) {
        var result = execute("{ presenceHosts { hostId " + selection + " } }");
        assertThat(result.getErrors()).isEmpty();
        var byId = new HashMap<Integer, Map<String, Object>>();
        for (var host : (List<Map<String, Object>>) ((Map<String, Object>) result.getData()).get("presenceHosts")) {
            byId.put((Integer) host.get("hostId"), host);
        }
        return byId;
    }

    private static Map<String, Object> child(Map<String, Object> parent, String key) {
        return (Map<String, Object>) parent.get(key);
    }

    // ===== A child with a table of its own =====

    @Test
    void inlineChild_overARowOfNulls_resolvesToNull() {
        var hosts = hosts("detail { street city }");
        assertThat(hosts.get(2)).containsEntry("detail", null);
        assertThat(hosts.get(5)).containsEntry("detail", null);
        assertThat(child(hosts.get(1), "detail")).containsEntry("street", "Main Street 1").containsEntry("city", "Oslo");
    }

    @Test
    void inlineChild_withOneColumnSet_resolvesToAnObject() {
        assertThat(child(hosts("detail { street city }").get(4), "detail"))
            .containsEntry("street", null).containsEntry("city", "Bergen");
    }

    @Test
    void inlineChild_withNoRow_resolvesToNull() {
        assertThat(hosts("detail { street }").get(3)).containsEntry("detail", null);
    }

    @Test
    void splitQueryChild_answersAsItsInlineForm() {
        var hosts = hosts("detailSplit { street city }");
        assertThat(hosts.get(2)).containsEntry("detailSplit", null);
        assertThat(hosts.get(3)).containsEntry("detailSplit", null);
        assertThat(hosts.get(5)).containsEntry("detailSplit", null);
        assertThat(child(hosts.get(4), "detailSplit")).containsEntry("city", "Bergen");
        assertThat(child(hosts.get(1), "detailSplit")).containsEntry("street", "Main Street 1");
    }

    @Test
    void typenameOnly_agreesWithTheFullSelection_forBothForms() {
        // A {__typename} selection projects no column of the child, yet the null contract reads
        // every column of the type, so the answer does not move with the selection.
        var typenameOnly = hosts("detail { __typename } detailSplit { __typename }");
        var full = hosts("detail { street city } detailSplit { street city }");
        for (int id = 1; id <= 5; id++) {
            assertThat(typenameOnly.get(id).get("detail") == null).as("detail of host %d", id)
                .isEqualTo(full.get(id).get("detail") == null);
            assertThat(typenameOnly.get(id).get("detailSplit") == null).as("detailSplit of host %d", id)
                .isEqualTo(full.get(id).get("detailSplit") == null);
        }
        assertThat(typenameOnly.get(2)).containsEntry("detail", null).containsEntry("detailSplit", null);
        assertThat(child(typenameOnly.get(4), "detail")).containsEntry("__typename", "PresenceDetail");
    }

    @Test
    void childWithANodeId_neverCollapsesWhileItsRowExists() {
        var hosts = hosts("detailNode { id street }");
        assertThat(child(hosts.get(2), "detailNode")).containsEntry("street", null)
            .extractingByKey("id").isNotNull();
        assertThat(hosts.get(3)).containsEntry("detailNode", null);
    }

    @Test
    void nonNullChild_overARowOfNulls_isAnObject() {
        var result = execute("{ presenceHosts(hostIds: [2]) { hostId detailRequired { street city } } }");
        assertThat(result.getErrors()).isEmpty();
        var host = ((List<Map<String, Object>>) ((Map<String, Object>) result.getData()).get("presenceHosts")).get(0);
        assertThat(child(host, "detailRequired")).containsEntry("street", null).containsEntry("city", null);
    }

    @Test
    void splitQueryCollapse_addsNoStatement() {
        STATEMENT_COUNT.set(0);
        var hosts = hosts("detailSplit { street }");
        assertThat(STATEMENT_COUNT.get()).as("the host list and one batched detail statement").isEqualTo(2);
        assertThat(hosts.get(2)).containsEntry("detailSplit", null);
    }

    // ===== Nested objects of references and lists =====

    @Test
    void nestedObjectOfReferences_whoseTargetRowIsMissing_resolvesToNull() {
        // The studiesemesterPeriode shape: the join column (the host's own key) is set on every
        // host, so only a probe of the referenced row can tell host 3 apart.
        var hosts = hosts("detailRefs { street city }");
        assertThat(hosts.get(3)).containsEntry("detailRefs", null);
        assertThat(hosts.get(2)).containsEntry("detailRefs", null);
        assertThat(child(hosts.get(4), "detailRefs")).containsEntry("city", "Bergen");
        // Selecting one reference agrees with selecting both.
        assertThat(hosts("detailRefs { street }").get(4).get("detailRefs")).isNotNull();
    }

    @Test
    void nestedObjectHoldingAList_resolvesToNullWhenItsColumnsAreNullAndTheListEmpty() {
        // The phd shape.
        var hosts = hosts("holdings { label items { name } }");
        assertThat(hosts.get(3)).containsEntry("holdings", null);
        assertThat(child(hosts.get(5), "holdings")).containsEntry("label", null)
            .extractingByKey("items").asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST).hasSize(1);
        assertThat(child(hosts.get(1), "holdings")).containsEntry("label", "full");
        // The list counts whether or not it is selected.
        assertThat(hosts("holdings { label }").get(5).get("holdings")).isNotNull();
        assertThat(hosts("holdings { label }").get(3)).containsEntry("holdings", null);
    }

    @Test
    void nestedObjectHoldingASplitQueryList_resolvesAsItsInlineFormDoes() {
        // Graphitron 9 kept this object whenever its batch key was set; here the empty split list
        // counts as nothing, exactly as the inline list does.
        var inline = hosts("holdings { label }");
        var split = hosts("holdingsSplit { label items { name } }");
        for (int id = 1; id <= 5; id++) {
            assertThat(split.get(id).get("holdingsSplit") == null).as("holdingsSplit of host %d", id)
                .isEqualTo(inline.get(id).get("holdings") == null);
        }
        assertThat(split.get(3)).containsEntry("holdingsSplit", null);
        assertThat(split.get(5).get("holdingsSplit")).isNotNull();
    }
}
