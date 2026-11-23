package no.sikt.graphitron.rewrite.test.querydb;

import no.sikt.graphitron.rewrite.test.SakilaDatabase;
import graphql.ExecutionResult;
import graphql.GraphQL;
import no.sikt.graphitron.generated.Graphitron;
import no.sikt.graphitron.rewrite.test.tier.ExecutionTier;
import org.jooq.DSLContext;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The null contract of a root field returning one table object, against a real PostgreSQL
 * database: it resolves to {@code null} exactly when its read finds no row, anchored or
 * {@code @lookupKey}, and a row whose columns are all null is an object of nulls, in the one
 * statement the read issues. Fixture: {@code presence_detail} and
 * {@code presence_host} as {@code init.sql} lays them out; detail 11 has both columns set, 12 none,
 * 14 only {@code city}, and there is no detail 13. Host 1 has a full detail row, host 2 a detail
 * row of nulls, host 3 none, host 4 one with {@code city} alone.
 */
@ExecutionTier
@SuppressWarnings("unchecked")
class RootNullContractExecutionTest {

    static DSLContext dsl;
    static GraphQL graphql;
    static final AtomicInteger STATEMENT_COUNT = new AtomicInteger();

    @BeforeAll
    static void startDatabase() {
        dsl = SakilaDatabase.dsl();

        dsl.configuration().set(new org.jooq.impl.DefaultExecuteListenerProvider(
            new org.jooq.ExecuteListener() {
                @Override
                public void executeStart(org.jooq.ExecuteContext ctx) {
                    STATEMENT_COUNT.incrementAndGet();
                }
            }));
        graphql = Graphitron.newGraphQL().build();
    }


    /** The value of the query's one root field, asserting the query ran without errors. */
    private Object root(String field, String arguments, String selection) {
        var input = Graphitron.newExecutionInput(dsl, "{}", "test-user")
            .query("{ " + field + "(" + arguments + ") { " + selection + " } }").build();
        ExecutionResult result = graphql.execute(input);
        assertThat(result.getErrors()).isEmpty();
        return ((Map<String, Object>) result.getData()).get(field);
    }

    @Test
    void anchoredAndLookupKey_rowOfNulls_isAnObjectOfNulls() {
        for (var field : new String[] {"presenceDetail", "presenceDetailByKey"}) {
            assertThat(root(field, "detailId: 12", "street city")).as(field).isEqualTo(nulls("street", "city"));
        }
    }

    @Test
    void anchoredAndLookupKey_rowWithOneValue_isAnObject() {
        for (var field : new String[] {"presenceDetail", "presenceDetailByKey"}) {
            // Only city is set on detail 14; selecting street alone still sees the object.
            assertThat(root(field, "detailId: 14", "street")).as(field)
                .isEqualTo(nulls("street"));
            assertThat(root(field, "detailId: 11", "street")).as(field)
                .isEqualTo(Map.of("street", "Main Street 1"));
        }
    }

    @Test
    void anchoredAndLookupKey_missingRow_isNull() {
        for (var field : new String[] {"presenceDetail", "presenceDetailByKey"}) {
            assertThat(root(field, "detailId: 13", "street")).as(field).isNull();
        }
    }

    @Test
    void typenameAlone_agreesWithTheFullSelection() {
        for (var field : new String[] {"presenceDetail", "presenceDetailByKey"}) {
            assertThat(root(field, "detailId: 12", "__typename")).as(field)
                .isEqualTo(Map.of("__typename", "PresenceDetail"));
            assertThat(root(field, "detailId: 14", "__typename")).as(field)
                .isEqualTo(Map.of("__typename", "PresenceDetail"));
        }
    }

    @Test
    void nonNullRoot_rowOfNulls_isAnObject() {
        assertThat(root("presenceDetailRequired", "detailId: 12", "street city"))
            .isEqualTo(nulls("street", "city"));
    }

    @Test
    void nodeIdType_neverCollapsesWhileItsRowExists() {
        assertThat(root("presenceDetailNode", "detailId: 12", "street")).isEqualTo(nulls("street"));
    }

    @Test
    void rootOfReferences_isAnObjectWhileItsOwnRowExists() {
        // Host 3 has no detail row; host 2's holds only nulls. Either way the host's own row is
        // found, so the host is an object, and its detail answers by the child's own rule.
        assertThat(root("presenceHostRefs", "hostId: 3", "__typename"))
            .isEqualTo(Map.of("__typename", "PresenceHostRefs"));
        assertThat(root("presenceHostRefs", "hostId: 3", "detail { city }"))
            .isEqualTo(nulls("detail"));
        assertThat(root("presenceHostRefs", "hostId: 4", "__typename"))
            .isEqualTo(Map.of("__typename", "PresenceHostRefs"));
        assertThat(root("presenceHostRefs", "hostId: 1", "detail { city }"))
            .isEqualTo(Map.of("detail", Map.of("city", "Oslo")));
    }

    @Test
    void theCheckRidesTheReadsOneStatement() {
        STATEMENT_COUNT.set(0);
        root("presenceDetail", "detailId: 12", "street");
        assertThat(STATEMENT_COUNT.get()).isEqualTo(1);
        STATEMENT_COUNT.set(0);
        root("presenceDetailByKey", "detailId: 14", "street");
        assertThat(STATEMENT_COUNT.get()).isEqualTo(1);
        STATEMENT_COUNT.set(0);
        root("presenceHostRefs", "hostId: 2", "__typename");
        assertThat(STATEMENT_COUNT.get()).isEqualTo(1);
    }

    private static Map<String, Object> nulls(String... keys) {
        var map = new java.util.LinkedHashMap<String, Object>();
        for (var key : keys) {
            map.put(key, null);
        }
        return map;
    }
}
