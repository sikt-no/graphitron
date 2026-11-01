package no.sikt.graphitron.rewrite.test.querydb;

import graphql.ExecutionResult;
import graphql.GraphQL;
import no.sikt.graphitron.generated.multitenant.Graphitron;
import no.sikt.graphitron.generated.multitenant.schema.GraphitronRuntime;
import no.sikt.graphitron.generated.multitenant.schema.SessionMountException;
import no.sikt.graphitron.rewrite.test.SakilaDatabase;
import no.sikt.graphitron.rewrite.test.tier.ExecutionTier;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Execution-tier proof, over real database-per-tenant, that a mutation field which touches more
 * than one connection is one transaction on each of them, and that a field is rolled back on every
 * connection when any of them is not clean. {@code createEndorsement} writes a row on the default
 * source (the table is global); its payload's {@code film} child is routed to the tenant that holds
 * the endorsed film, so the one field runs a transaction on the default source and another on the
 * tenant.
 *
 * <p>The unit-tier carrier tests pin the same contracts over fake connections, with a worker that
 * stalls mid-open and with a rollback-only mark on a second connection; what only real databases
 * show is read here, after {@code execute}: which rows survived.
 */
@ExecutionTier
class MutationScopeMultiTenantExecutionTest {

    private static final String HEALTHY_T1 = "scope_mt_t1";
    private static final String HEALTHY_T2 = "scope_mt_t2";
    private static final String BROKEN_T2 = "scope_mt_t2_broken";
    private static final String REJECTING_T2 = "scope_mt_t2_reject";
    private static final String FAN_T1 = "scope_mt_fan1";
    private static final String FAN_T2 = "scope_mt_fan2";
    /** The default source's language row the fan-out tests rename, and every tenant's films point at. */
    private static final int LANGUAGE_ID = 9101;

    /** The principal tenant 2's mount in {@link #REJECTING_T2} refuses, and tenant 1's accepts. */
    private static final String REJECTED_ON_TWO = "reject-on-two";

    static DSLContext dsl;                 // default database, out-of-band setup and observation
    static String jdbcUrl, jdbcUser, jdbcPassword;

    /** Every transaction call on every connection the runtimes hand out, as {@code label:call}. */
    static final List<String> EVENTS = Collections.synchronizedList(new ArrayList<>());

    private final List<String> notes = new ArrayList<>();

    @BeforeAll
    static void startDatabases() {
        jdbcUrl = SakilaDatabase.url();
        jdbcUser = SakilaDatabase.user();
        jdbcPassword = SakilaDatabase.password();
        dsl = DSL.using(jdbcUrl, jdbcUser, jdbcPassword);

        for (String db : List.of(HEALTHY_T1, HEALTHY_T2, BROKEN_T2, REJECTING_T2)) {
            SakilaDatabase.createTenantDatabase(db);
            try (var tenant = DSL.using(SakilaDatabase.tenantUrl(db), jdbcUser, jdbcPassword)) {
                if (db.equals(BROKEN_T2)) {
                    // Reading the film fails: the error lands under the field's path on tenant 2.
                    tenant.execute("create view film as select 2 as film_id, (1 / 0)::text as title");
                } else {
                    tenant.execute("create table film (film_id int primary key, title text not null)");
                }
                TenantSessionFixture.installSessionObjects(tenant);
                if (db.equals(REJECTING_T2)) {
                    tenant.execute("""
                        create or replace function session_connect(p_claims session_claims) returns session_handle
                        language plpgsql volatile
                        as $$
                        declare
                            h session_handle;
                        begin
                            if (p_claims).sub = '%s' then
                                raise exception 'unentitled on this tenant: %%', (p_claims).sub;
                            end if;
                            h.principal  := (p_claims).sub;
                            h.session_no := nextval('session_handle_seq');
                            return h;
                        end;
                        $$""".formatted(REJECTED_ON_TWO));
                }
            }
        }
        try (var t1 = DSL.using(SakilaDatabase.tenantUrl(HEALTHY_T1), jdbcUser, jdbcPassword)) {
            t1.execute("insert into film values (1, 'Tenant One Film')");
        }
        for (String db : List.of(FAN_T1, FAN_T2)) {
            SakilaDatabase.createTenantDatabase(db);
            try (var tenant = DSL.using(SakilaDatabase.tenantUrl(db), jdbcUser, jdbcPassword)) {
                tenant.execute("create table film (film_id int primary key, title text not null,"
                    + " language_id int not null)");
                TenantSessionFixture.installSessionObjects(tenant);
                tenant.execute("insert into film values (?, ?, ?)", db.equals(FAN_T1) ? 1 : 2,
                    db.equals(FAN_T1) ? "Tenant One Film" : "Tenant Two Film", LANGUAGE_ID);
            }
        }
        dsl.execute("delete from language where language_id = ?", LANGUAGE_ID);
        dsl.execute("insert into language (language_id, name) values (?, 'before-rename')", LANGUAGE_ID);
        for (String db : List.of(HEALTHY_T2, REJECTING_T2)) {
            try (var t2 = DSL.using(SakilaDatabase.tenantUrl(db), jdbcUser, jdbcPassword)) {
                t2.execute("insert into film values (2, 'Tenant Two Film')");
            }
        }
    }

    @AfterAll
    static void stopDatabases() {
        if (dsl != null) {
            dsl.execute("delete from language where language_id = ?", LANGUAGE_ID);
            for (String db : List.of(HEALTHY_T1, HEALTHY_T2, BROKEN_T2, REJECTING_T2, FAN_T1, FAN_T2)) {
                SakilaDatabase.dropTenantDatabase(db);
            }
        }
    }

    @BeforeEach
    void clearEvents() {
        EVENTS.clear();
    }

    @AfterEach
    void restoreTheLanguage() {
        dsl.execute("update language set name = 'before-rename' where language_id = ?", LANGUAGE_ID);
    }

    @AfterEach
    void removeTheEndorsementsWritten() {
        for (String note : notes) {
            dsl.execute("delete from film_endorsement where note = ?", note);
        }
    }

    @Test
    void aFieldOnTwoConnections_commitsOnEachOnce_andKeepsTheDefaultSourcesWrite() {
        String note = note();

        ExecutionResult result = engine(HEALTHY_T2).execute(input(note, "test-user"));

        assertThat(result.getErrors()).isEmpty();
        assertThat(film(result)).containsEntry("title", "Tenant Two Film");
        assertThat(endorsements(note)).as("the write on the default source committed").isEqualTo(1);
        assertThat(EVENTS.stream().filter("default:commit"::equals).count()).isEqualTo(1);
        assertThat(EVENTS.stream().filter("tenant2:commit"::equals).count())
            .as("the field opened its own transaction on the tenant it read, and committed it").isEqualTo(1);
    }

    @Test
    void aFailedReadOnTheTenant_rollsTheWriteOnTheDefaultSourceBack() {
        String note = note();

        ExecutionResult result = engine(BROKEN_T2).execute(input(note, "test-user"));

        assertThat(result.getErrors()).as("the tenant's read failed under the field's path").isNotEmpty();
        assertThat(result.getErrors()).allSatisfy(e -> assertThat(e.getPath()).startsWith("createEndorsement"));
        assertThat(((Map<?, ?>) result.getData()).get("createEndorsement"))
            .as("no value describes a write that is gone").isNull();
        assertThat(endorsements(note))
            .as("the field failed, so the default source's write rolled back with it").isZero();
        assertThat(EVENTS).doesNotContain("default:commit", "tenant2:commit");
    }

    @Test
    void aMountRejectedOnTheSecondTenantOnly_rejectsTheRequest_andTheFirstConnectionsWriteIsGone() {
        String note = note();
        var rejecting = engine(REJECTING_T2);

        assertThatThrownBy(() -> rejecting.execute(input(note, REJECTED_ON_TWO)))
            .isInstanceOf(SessionMountException.class);

        assertThat(endorsements(note))
            .as("the request was rejected, so the write on the default source must not stand").isZero();
    }

    @Test
    void aPayloadThatFansOutOverTheTenants_commitsTheDefaultSourcesWrite_whenEveryTenantAnswers() {
        ExecutionResult result = fanOutEngine(0).execute(renameInput());

        assertThat(result.getErrors()).isEmpty();
        assertThat(languageName()).as("the write on the default source committed").isEqualTo("after-rename");
        assertThat(films(result)).extracting(f -> f.get("title"))
            .containsExactlyInAnyOrder("Tenant One Film", "Tenant Two Film");
    }

    @Test
    void aTenantThatMissesTheFanOutDeadline_failsTheField_andTheDefaultSourcesWriteRollsBack() throws InterruptedException {
        // Tenant 2 does not hand out a connection until well after the 300 ms fan-out deadline.
        ExecutionResult result = fanOutEngine(1500).execute(renameInput());

        assertThat(result.getErrors()).as("the tenant that timed out is reported under the field").isNotEmpty();
        assertThat(languageName())
            .as("the field failed, so the default source's write rolled back with it").isEqualTo("before-rename");
        assertThat(EVENTS).doesNotContain("default:commit");
        // Let the abandoned worker finish its late acquisition before the databases are dropped.
        Thread.sleep(2200);
    }

    private static graphql.ExecutionInput renameInput() {
        return Graphitron.newOwnedExecutionInput(List.of(1, 2), SessionStates.multitenant("{\"sub\":\"test-user\"}"))
            .query("mutation { renameLanguage(in: { languageId: " + LANGUAGE_ID + ", name: \"after-rename\" }) {"
                + " name films { title } } }")
            .build();
    }

    private static String languageName() {
        return dsl.fetchValue("select name from language where language_id = ?", LANGUAGE_ID).toString().trim();
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> films(ExecutionResult result) {
        var payload = (Map<String, Object>) ((Map<String, Object>) result.getData()).get("renameLanguage");
        return (List<Map<String, Object>>) payload.get("films");
    }

    /** An owned engine fanning over {@code FAN_T1} and {@code FAN_T2}, the second slow to hand out a connection. */
    private static GraphQL fanOutEngine(long tenant2DelayMillis) {
        Map<Integer, DataSource> byTenant = Map.of(
            1, recording("tenant1", FAN_T1, 0),
            2, recording("tenant2", FAN_T2, tenant2DelayMillis));
        var runtime = new GraphitronRuntime(recording("default", null, 0), byTenant, SQLDialect.POSTGRES,
            4, java.time.Duration.ofMillis(300));
        return runtime.newGraphQL(Graphitron.buildSchema(b -> {})).build();
    }

    // ===== helpers =====

    private String note() {
        String note = "MUTATION-SCOPE-MT-" + UUID.randomUUID();
        notes.add(note);
        return note;
    }

    private int endorsements(String note) {
        return dsl.fetchCount(DSL.table("film_endorsement"), DSL.field("note").eq(note));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> film(ExecutionResult result) {
        var payload = (Map<String, Object>) ((Map<String, Object>) result.getData()).get("createEndorsement");
        return (Map<String, Object>) payload.get("film");
    }

    private static graphql.ExecutionInput input(String note, String principal) {
        return Graphitron.newOwnedExecutionInput(List.of(1, 2),
                SessionStates.multitenant("{\"sub\":\"" + principal + "\"}"))
            .query("mutation { createEndorsement(in: { endorsedFilm: 2, note: \"" + note + "\" }) {"
                + " note film { title } } }")
            .build();
    }

    /** An owned engine whose tenant 1 is the healthy database and whose tenant 2 is {@code tenant2Database}. */
    private static GraphQL engine(String tenant2Database) {
        Map<Integer, DataSource> byTenant = Map.of(
            1, recording("tenant1", HEALTHY_T1, 0),
            2, recording("tenant2", tenant2Database, 0));
        var runtime = new GraphitronRuntime(recording("default", null, 0), byTenant, SQLDialect.POSTGRES);
        return runtime.newGraphQL(Graphitron.buildSchema(b -> {})).build();
    }

    /** Real connections to {@code database} (the default database when {@code null}), each call recorded. */
    private static DataSource recording(String label, String database, long delayMillis) {
        return (DataSource) Proxy.newProxyInstance(
            MutationScopeMultiTenantExecutionTest.class.getClassLoader(),
            new Class<?>[]{DataSource.class},
            (proxy, method, args) -> {
                if (method.getName().equals("getConnection")) {
                    if (delayMillis > 0) {
                        Thread.sleep(delayMillis);
                    }
                    String url = database == null ? jdbcUrl : SakilaDatabase.tenantUrl(database);
                    return recordingConnection(label, SakilaDatabase.connect(url, jdbcUser, jdbcPassword));
                }
                return switch (method.getName()) {
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> "recordingDataSource:" + label;
                    default -> throw new UnsupportedOperationException(method.getName());
                };
            });
    }

    private static Connection recordingConnection(String label, Connection real) {
        return (Connection) Proxy.newProxyInstance(
            MutationScopeMultiTenantExecutionTest.class.getClassLoader(),
            new Class<?>[]{Connection.class},
            (proxy, method, args) -> {
                if (method.getName().equals("commit") || method.getName().equals("rollback")
                        || method.getName().equals("setSavepoint")) {
                    EVENTS.add(label + ":" + method.getName());
                }
                try {
                    return method.invoke(real, args);
                } catch (java.lang.reflect.InvocationTargetException e) {
                    throw e.getCause();
                }
            });
    }
}
