package no.sikt.graphitron.rewrite.generated;

import graphql.ExecutionResult;
import graphql.GraphQL;
import no.sikt.graphitron.emit.VoucherRuntime;
import no.sikt.graphitron.rewrite.test.db.TestDatabase;
import no.sikt.graphitron.rewrite.test.tier.ExecutionTier;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A key field supplied by the voucher, end to end against the shared database, on the code two fact
 * scenarios generate. {@code voucher-accept-per-entity} vouches for its own key, a case's id, so each
 * representation carries a voucher alone, with an empty lookup section, and presents a vouched-for
 * value of its own; {@code voucher-accept-mixed-key} vouches for part of its key, the organisation, and
 * for the person, so its key's other field, the case, comes from the lookup section. The refusal of a
 * voucher naming a key field in both sections needs no row, so it is {@code VoucherKeyFieldDispatchTest}'s.
 *
 * <p>Every read runs as a role of its own, which the fixture's row-level security applies to (the
 * tables' owner bypasses it): the connection source sets the role and the set of vouched-for values on
 * the connection, so a row the set does not name is unreadable and a test of what is read cannot pass
 * by the owner reading everything.
 */
@ExecutionTier
class VoucherKeyFieldExecutionTest {

    private static final String PROBE_ROLE = "voucher_key_field_probe";
    private static final String CASES = """
        query ($representations: [_Any!]!) {
          _entities(representations: $representations) { ... on CaseEventLog { events { what } } }
        }""";
    private static final String ENROLMENTS = """
        query ($representations: [_Any!]!) {
          _entities(representations: $representations) { ... on Enrolment { status } }
        }""";

    private static KeyPair pair;
    private static no.sikt.graphitron.generated.facts.voucher_accept_per_entity.schema.VoucherKeys caseKeys;
    private static no.sikt.graphitron.generated.facts.voucher_accept_mixed_key.schema.VoucherKeys enrolmentKeys;

    /** The keys a source leased for in one test, in lease order. */
    private static final List<Object> LEASED = Collections.synchronizedList(new ArrayList<>());

    @BeforeAll
    static void start() throws Exception {
        dropProbeRole();
        TestDatabase.dsl().execute("create role " + PROBE_ROLE + " nologin");
        TestDatabase.dsl().execute("grant select on vouched_case, vouched_case_event, vouched_enrolment to "
            + PROBE_ROLE);
        pair = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        caseKeys = no.sikt.graphitron.generated.facts.voucher_accept_per_entity.schema.VoucherKeys
            .ed25519("k1", pair.getPrivate(), Map.of("k1", pair.getPublic()));
        enrolmentKeys = no.sikt.graphitron.generated.facts.voucher_accept_mixed_key.schema.VoucherKeys
            .ed25519("k1", pair.getPrivate(), Map.of("k1", pair.getPublic()));
    }

    @AfterAll
    static void stop() {
        dropProbeRole();
    }

    @BeforeEach
    void forget() {
        LEASED.clear();
    }

    private static void dropProbeRole() {
        TestDatabase.dsl().execute("""
            do $$ begin
              if exists (select from pg_roles where rolname = '%s') then
                drop owned by %s;
              end if;
            end $$""".formatted(PROBE_ROLE, PROBE_ROLE));
        TestDatabase.dsl().execute("drop role if exists " + PROBE_ROLE);
    }

    @SuppressWarnings("unchecked")
    private static List<Object> entities(ExecutionResult result) {
        return (List<Object>) ((Map<String, Object>) result.getData()).get("_entities");
    }

    // --- a voucher per entity, vouching for the key itself -----------------------------------------

    private static GraphQL caseEngine(Integer limit) {
        var runtime = no.sikt.graphitron.generated.facts.voucher_accept_per_entity.Graphitron
            .runtime(SQLDialect.POSTGRES, caseKeys);
        if (limit != null) {
            runtime = runtime.withVouchedValueLimit(env -> limit);
        }
        return runtime.newGraphQL(no.sikt.graphitron.generated.facts.voucher_accept_per_entity.Graphitron
            .buildSchema(b -> {})).build();
    }

    /**
     * The per-entity source, as a consumer writes one: the role, and the set of case ids the key carries,
     * an empty set for the request's own key.
     */
    private static no.sikt.graphitron.generated.facts.voucher_accept_per_entity.schema.ConnectionSource caseSource() {
        return no.sikt.graphitron.generated.facts.voucher_accept_per_entity.schema.ConnectionSources.mounting(
            key -> TestDatabase.dataSource(),
            (connection, key) -> {
                LEASED.add(key);
                var dsl = DSL.using(connection, SQLDialect.POSTGRES);
                dsl.execute("set role " + PROBE_ROLE);
                dsl.execute("select set_config('app.vouched_cases', ?::text, false)",
                    (Object) key.vouchedFor().stream()
                        .map(no.sikt.graphitron.generated.facts.voucher_accept_per_entity.schema.ConnectionKey.VouchedFor::caseId)
                        .toArray(Integer[]::new));
            },
            (connection, key) -> {});
    }

    private static String caseVoucher(Map<String, ?> vouchedFor, Map<String, ?> lookup) {
        return no.sikt.graphitron.generated.facts.voucher_accept_per_entity.schema.VoucherCodec
            .issuer(caseKeys, UUID.randomUUID(), "case-events", Instant.now().plus(Duration.ofMinutes(5)))
            .sign("CaseEventLog", vouchedFor, lookup);
    }

    private static Map<String, Object> caseRep(int caseId) {
        return Map.of("__typename", "CaseEventLog",
            "internalGraphitronVoucher", caseVoucher(Map.of("caseId", caseId), Map.of()));
    }

    private static ExecutionResult presentCases(GraphQL engine, List<Map<String, Object>> representations) {
        return engine.execute(no.sikt.graphitron.generated.facts.voucher_accept_per_entity.Graphitron
            .newOwnedExecutionInput(caseSource())
            .query(CASES)
            .variables(Map.of("representations", representations))
            .build());
    }

    @SuppressWarnings("unchecked")
    private static List<String> events(Object entity) {
        return ((List<Map<String, Object>>) ((Map<String, Object>) entity).get("events")).stream()
            .map(e -> (String) e.get("what")).sorted().toList();
    }

    @Test
    void aVoucherPerRowVouchingForItsOwnKeyResolvesEveryRowOnOneLease_eachAnsweringItsOwnRow() {
        var result = presentCases(caseEngine(null), List.of(caseRep(2), caseRep(1), caseRep(3)));
        assertThat(result.getErrors()).isEmpty();
        assertThat(entities(result)).extracting(VoucherKeyFieldExecutionTest::events).containsExactly(
            List.of("appeal opened"),
            List.of("admission decided", "admission opened"),
            List.of("transfer opened"));
        assertThat(LEASED).as("one lease, acquired once, for every case").singleElement()
            .isEqualTo(no.sikt.graphitron.generated.facts.voucher_accept_per_entity.schema.ConnectionKey
                .forVouchedFor(new java.util.LinkedHashSet<>(List.of(
                    new no.sikt.graphitron.generated.facts.voucher_accept_per_entity.schema.ConnectionKey.VouchedFor(2),
                    new no.sikt.graphitron.generated.facts.voucher_accept_per_entity.schema.ConnectionKey.VouchedFor(1),
                    new no.sikt.graphitron.generated.facts.voucher_accept_per_entity.schema.ConnectionKey.VouchedFor(3)))));
    }

    @Test
    @SuppressWarnings("unchecked")
    void aReadOnTheRequestsOwnConnectionIsAdmittedNoCase_soTheRoleIsUnderThePolicy() {
        var result = caseEngine(null).execute(no.sikt.graphitron.generated.facts.voucher_accept_per_entity.Graphitron
            .newOwnedExecutionInput(caseSource())
            .query("{ caseEventLogs { events { what } } }").build());
        assertThat(result.getErrors()).isEmpty();
        assertThat((List<?>) ((Map<String, Object>) result.getData()).get("caseEventLogs")).isEmpty();
        assertThat(LEASED).singleElement().isEqualTo(
            no.sikt.graphitron.generated.facts.voucher_accept_per_entity.schema.ConnectionKey.defaultSource());
    }

    @Test
    void aCaseTheSetDoesNotNameIsUnreadableOnTheLeaseItWasMountedFor() throws Exception {
        var mounted = no.sikt.graphitron.generated.facts.voucher_accept_per_entity.schema.ConnectionKey
            .forVouchedFor(java.util.Set.of(
                new no.sikt.graphitron.generated.facts.voucher_accept_per_entity.schema.ConnectionKey.VouchedFor(1)));
        var lease = caseSource().acquire(mounted);
        try {
            var seen = DSL.using(lease.connection(), SQLDialect.POSTGRES)
                .fetch("select case_id from vouched_case order by case_id").getValues(0, Integer.class);
            assertThat(seen).containsExactly(1);
        } finally {
            lease.release();
        }
    }

    @Test
    void aValuePastTheCapFailsAtItsOwnIndex_namingNoValue_andTakesNoConnection() {
        var result = presentCases(caseEngine(2), List.of(caseRep(1), caseRep(2), caseRep(3)));
        assertThat(result.getErrors()).singleElement().satisfies(error -> {
            assertThat(error.getMessage()).isEqualTo(VoucherRuntime.LIMIT_MESSAGE);
            assertThat(error.getPath()).isEqualTo(List.of("_entities", 2));
        });
        assertThat(entities(result).get(2)).isNull();
        assertThat(entities(result).subList(0, 2)).extracting(VoucherKeyFieldExecutionTest::events).containsExactly(
            List.of("admission decided", "admission opened"), List.of("appeal opened"));
        assertThat(LEASED).singleElement().satisfies(key -> assertThat(
            ((no.sikt.graphitron.generated.facts.voucher_accept_per_entity.schema.ConnectionKey) key).vouchedFor())
            .hasSize(2));
    }

    // --- a mixed key: part vouched for, part looked up ---------------------------------------------

    private static GraphQL enrolmentEngine() {
        return no.sikt.graphitron.generated.facts.voucher_accept_mixed_key.Graphitron
            .runtime(SQLDialect.POSTGRES, enrolmentKeys)
            .newGraphQL(no.sikt.graphitron.generated.facts.voucher_accept_mixed_key.Graphitron.buildSchema(b -> {}))
            .build();
    }

    /**
     * The two-field source, as a consumer writes one: each voucher's (organisation, person) pair as one
     * object of a JSON array, so the policy tests a row's pair whole and never pairs one voucher's
     * organisation with another's person.
     */
    private static no.sikt.graphitron.generated.facts.voucher_accept_mixed_key.schema.ConnectionSource enrolmentSource() {
        return no.sikt.graphitron.generated.facts.voucher_accept_mixed_key.schema.ConnectionSources.mounting(
            key -> TestDatabase.dataSource(),
            (connection, key) -> {
                LEASED.add(key);
                var dsl = DSL.using(connection, SQLDialect.POSTGRES);
                dsl.execute("set role " + PROBE_ROLE);
                dsl.execute("select set_config('app.vouched_pairs', ?, false)",
                    key.vouchedFor().stream()
                        .map(v -> "{\"org\":" + v.orgId() + ",\"person\":" + v.personId() + "}")
                        .collect(Collectors.joining(",", "[", "]")));
            },
            (connection, key) -> {});
    }

    private static String enrolmentVoucher(Map<String, ?> vouchedFor, Map<String, ?> lookup) {
        return no.sikt.graphitron.generated.facts.voucher_accept_mixed_key.schema.VoucherCodec
            .issuer(enrolmentKeys, UUID.randomUUID(), "enrolments", Instant.now().plus(Duration.ofMinutes(5)))
            .sign("Enrolment", vouchedFor, lookup);
    }

    private static Map<String, Object> enrolmentRep(int org, int person, int caseId) {
        return Map.of("__typename", "Enrolment", "internalGraphitronVoucher",
            enrolmentVoucher(Map.of("orgId", org, "personId", person), Map.of("caseId", caseId)));
    }

    private static ExecutionResult presentEnrolments(List<Map<String, Object>> representations) {
        return enrolmentEngine().execute(no.sikt.graphitron.generated.facts.voucher_accept_mixed_key.Graphitron
            .newOwnedExecutionInput(enrolmentSource())
            .query(ENROLMENTS)
            .variables(Map.of("representations", representations))
            .build());
    }

    @Test
    @SuppressWarnings("unchecked")
    void aMixedKeyTakesItsVouchedForFieldFromTheVouchedForSectionAndItsOtherFromTheLookupSection() {
        var result = presentEnrolments(List.of(enrolmentRep(20, 100, 1), enrolmentRep(10, 100, 1),
            enrolmentRep(10, 200, 2)));
        assertThat(result.getErrors()).isEmpty();
        assertThat(entities(result)).extracting(e -> ((Map<String, Object>) e).get("status"))
            .containsExactly("declined", "admitted", "waiting");
        assertThat(LEASED).as("one lease for every pair").hasSize(1);
    }

    @Test
    void aPairNoVoucherGrantedIsUnreadable_thoughItsRowExists() {
        var result = presentEnrolments(List.of(enrolmentRep(10, 200, 1)));
        assertThat(result.getErrors()).isEmpty();
        assertThat(entities(result)).containsExactly((Object) null);
    }
}
