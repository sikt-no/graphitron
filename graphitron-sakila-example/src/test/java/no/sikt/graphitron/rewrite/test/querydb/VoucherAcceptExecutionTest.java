package no.sikt.graphitron.rewrite.test.querydb;

import graphql.ExecutionResult;
import graphql.GraphQL;
import no.sikt.graphitron.generated.accepting.Graphitron;
import no.sikt.graphitron.generated.accepting.schema.ConnectionKey;
import no.sikt.graphitron.generated.accepting.schema.ConnectionSource;
import no.sikt.graphitron.generated.accepting.schema.ConnectionSources;
import no.sikt.graphitron.generated.accepting.schema.VouchedValueLimitExceededException;
import no.sikt.graphitron.generated.accepting.schema.VoucherCodec;
import no.sikt.graphitron.generated.accepting.schema.VoucherKeys;
import no.sikt.graphitron.generated.accepting.schema.VoucherRejectedException;
import no.sikt.graphitron.rewrite.test.SakilaDatabase;
import no.sikt.graphitron.rewrite.test.tier.ExecutionTier;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.groups.Tuple.tuple;

/**
 * The voucher handover end to end: the {@code issuing} package vouches for each result an application
 * references, and the {@code accepting} package verifies those vouchers and reads the vouched results,
 * the details their SELECT nests and the details a service below them reads, on the connection its
 * source leases for the key carrying the
 * owner each voucher vouches for. The accepting side reads as a role the row-level security policies
 * apply to, which admit a result and its details only to a connection whose session set
 * {@code app.vouched_owner} to the result's owner; the test's source sets it from the key's vouched-for
 * part, and empty for the key with no parts, so a read outside a vouched subtree is admitted nothing.
 */
@ExecutionTier
class VoucherAcceptExecutionTest {

    private static final String CLAIMS = "{\"sub\":\"test-user\"}";
    private static final String PROBE_USER = "voucher_accept_probe";
    private static final String PROBE_PASSWORD = "probe";
    private static final String SCOPE = "vgs-resultater";
    private static final String ENTITIES = """
        query ($representations: [_Any!]!) {
          _entities(representations: $representations) {
            ... on VgsResultat { id grade details { subject } }
          }
        }""";

    static DSLContext superDsl;
    static KeyPair pair;
    static VoucherKeys acceptingKeys;
    static GraphQL issuing;
    static GraphQL accepting;
    static DataSource issuingPool;
    static DataSource probePool;

    /** The keys the source leased for in one test, in lease order, and the keys it released. */
    static final List<ConnectionKey> LEASED = Collections.synchronizedList(new ArrayList<>());
    static final List<ConnectionKey> RELEASED = Collections.synchronizedList(new ArrayList<>());

    @BeforeAll
    static void start() throws Exception {
        String url = SakilaDatabase.url();
        String user = SakilaDatabase.user();
        String password = SakilaDatabase.password();
        superDsl = DSL.using(url, user, password);
        dropProbeRole();
        superDsl.execute("create role " + PROBE_USER + " login password '" + PROBE_PASSWORD + "'");
        superDsl.execute("grant select on vouched_result, vouched_result_detail to " + PROBE_USER);

        pair = KeyPairGenerator.getInstance(VoucherKeys.ALGORITHM).generateKeyPair();
        var issuingKeys = no.sikt.graphitron.generated.issuing.schema.VoucherKeys.ed25519(
            "k1", pair.getPrivate(), Map.of("k1", pair.getPublic()));
        issuingPool = dataSource(url, user, password);
        issuing = no.sikt.graphitron.generated.issuing.Graphitron
            .runtime(SQLDialect.POSTGRES, issuingKeys)
            .newGraphQL(no.sikt.graphitron.generated.issuing.Graphitron.buildSchema(b -> {}))
            .build();
        acceptingKeys = VoucherKeys.ed25519("k1", pair.getPrivate(), Map.of("k1", pair.getPublic()));
        probePool = dataSource(url, PROBE_USER, PROBE_PASSWORD);
        accepting = Graphitron.runtime(SQLDialect.POSTGRES, acceptingKeys)
            .newGraphQL(Graphitron.buildSchema(b -> {}))
            .build();
    }

    @AfterAll
    static void stop() {
        if (superDsl != null) {
            dropProbeRole();
        }
    }

    @BeforeEach
    void forget() {
        LEASED.clear();
        RELEASED.clear();
    }

    private static void dropProbeRole() {
        superDsl.execute("""
            do $$ begin
              if exists (select from pg_roles where rolname = '%s') then
                drop owned by %s;
              end if;
            end $$""".formatted(PROBE_USER, PROBE_USER));
        superDsl.execute("drop role if exists " + PROBE_USER);
    }

    private static DataSource dataSource(String url, String user, String password) {
        return (DataSource) Proxy.newProxyInstance(VoucherAcceptExecutionTest.class.getClassLoader(),
            new Class<?>[] {DataSource.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getConnection" -> SakilaDatabase.connect(url, user, password);
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                case "toString" -> "voucherAcceptDataSource(" + user + ")";
                default -> throw new UnsupportedOperationException(method.getName());
            });
    }

    /**
     * The accepting side's source, as a consumer writes one: the session setting the policies read is
     * set from the key's vouched-for part, and empty for the key with no parts, the request's own.
     */
    private static ConnectionSource acceptingSource() {
        return ConnectionSources.mounting(
            key -> probePool,
            (connection, key) -> {
                LEASED.add(key);
                DSL.using(connection, SQLDialect.POSTGRES)
                    .execute("select set_config('app.vouched_owner', ?, false)", key.eierId().orElse(""));
            },
            (connection, key) -> RELEASED.add(key));
    }

    /** The vouchers the issuing side signs for one application's results. */
    @SuppressWarnings("unchecked")
    private static List<String> vouchers(int applicationId) {
        var result = issuing.execute(no.sikt.graphitron.generated.issuing.Graphitron
            .newOwnedExecutionInput(Sources.issuing(CLAIMS, issuingPool))
            .query("{ applications { applicationId results { voucher } } }").build());
        assertThat(result.getErrors()).isEmpty();
        var applications = (List<Map<String, Object>>) ((Map<String, Object>) result.getData()).get("applications");
        return applications.stream()
            .filter(a -> ((Integer) a.get("applicationId")) == applicationId)
            .flatMap(a -> ((List<Map<String, Object>>) a.get("results")).stream())
            .map(r -> (String) r.get("voucher"))
            .toList();
    }

    /** A voucher the accepting side's own codec signs with the shared key, for the checks a router cannot reach. */
    private static String forge(String typeName, String scope, Instant expires, Map<String, ?> vouchedFor,
                                Map<String, ?> lookup) {
        return VoucherCodec.issuer(acceptingKeys, UUID.randomUUID(), scope, expires)
            .sign(typeName, vouchedFor, lookup);
    }

    private static Map<String, Object> voucherRep(String voucher) {
        return Map.of("__typename", "VgsResultat", "voucher", voucher);
    }

    private static List<Map<String, Object>> representations(List<String> vouchers) {
        return vouchers.stream().map(VoucherAcceptExecutionTest::voucherRep).toList();
    }

    private static ExecutionResult present(String query, List<Map<String, Object>> representations) {
        return accepting.execute(Graphitron.newOwnedExecutionInput(acceptingSource())
            .query(query)
            .variables(Map.of("representations", representations))
            .build());
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> entities(ExecutionResult result) {
        assertThat(result.getErrors()).isEmpty();
        return (List<Map<String, Object>>) ((Map<String, Object>) result.getData()).get("_entities");
    }

    @SuppressWarnings("unchecked")
    private static List<String> subjects(Map<String, Object> entity) {
        return ((List<Map<String, Object>>) entity.get("details")).stream()
            .map(d -> (String) d.get("subject")).sorted().toList();
    }

    @Test
    void aValidVoucherExposesTheEntityAndTheDetailsItsSelectNests() {
        var found = new ArrayList<>(entities(present(ENTITIES, representations(vouchers(1)))));
        found.sort(Comparator.comparing(e -> (String) e.get("id")));
        assertThat(found).extracting(e -> e.get("id"), e -> e.get("grade"))
            .containsExactly(tuple("1", 5), tuple("2", 4));
        assertThat(subjects(found.get(0))).containsExactly("math", "norwegian");
        assertThat(subjects(found.get(1))).containsExactly("english");
    }

    @Test
    void anOwnersVouchersShareOneLeaseForAKeyCarryingTheOwnerReleasedAtTheEnd() {
        entities(present(ENTITIES, representations(vouchers(1))));
        assertThat(LEASED).singleElement()
            .satisfies(key -> assertThat(key.eierId()).contains("olivia"));
        assertThat(RELEASED).containsExactlyElementsOf(LEASED);
    }

    @Test
    @SuppressWarnings("unchecked")
    void aChildServiceReadsOnTheConnectionTheVouchedEntityHandsDown() {
        var found = new ArrayList<>(entities(present(ENTITIES.replace("details {", "servicedDetails { subject } details {"),
            representations(vouchers(1)))));
        found.sort(Comparator.comparing(e -> (String) e.get("id")));
        assertThat(found).extracting(e -> ((List<Map<String, Object>>) e.get("servicedDetails")).stream()
                .map(d -> d.get("subject")).toList())
            .containsExactly(List.of("math", "norwegian"), List.of("english"));
        assertThat(LEASED).singleElement()
            .satisfies(key -> assertThat(key.eierId()).contains("olivia"));
        assertThat(RELEASED).containsExactlyElementsOf(LEASED);
    }

    @Test
    void aReadOutsideTheVouchedSubtreeIsAdmittedNothing() {
        var result = present(ENTITIES.replace("_entities(", "results { id } _entities("),
            representations(vouchers(1)));
        assertThat(result.getErrors()).isEmpty();
        @SuppressWarnings("unchecked")
        var data = (Map<String, Object>) result.getData();
        assertThat((List<?>) data.get("results")).isEmpty();
        assertThat((List<?>) data.get("_entities")).hasSize(2);
        assertThat(LEASED).extracting(ConnectionKey::hasVouchedFor).containsExactlyInAnyOrder(true, false);
    }

    @Test
    void aPlainKeyRepresentationResolvesOnTheRequestsOwnConnectionAndAddsNoAccess() {
        var found = entities(present(ENTITIES, List.of(Map.of("__typename", "VgsResultat", "id", "1"))));
        assertThat(found).containsExactly((Map<String, Object>) null);
        assertThat(LEASED).singleElement().isEqualTo(ConnectionKey.defaultSource());
    }

    @Test
    void vouchersForTwoOwnersInOneRequestRejectItWithTheLimitNotNull() {
        var mixed = new ArrayList<>(vouchers(1));
        mixed.addAll(vouchers(2));
        assertThatThrownBy(() -> present(ENTITIES, representations(mixed)))
            .isInstanceOf(VouchedValueLimitExceededException.class)
            .hasMessageContaining("more than one vouched-for value");
        assertThat(RELEASED).containsExactlyInAnyOrderElementsOf(LEASED);
    }

    @Test
    void aTamperedVoucherAmongPlainAndValidOnesRejectsTheRequestBeforeAnyConnectionIsTaken() {
        var good = vouchers(1);
        var tampered = good.get(0).substring(0, good.get(0).length() - 4) + "AAAA";
        var representations = new ArrayList<Map<String, Object>>();
        representations.add(Map.of("__typename", "VgsResultat", "id", "4"));
        representations.add(voucherRep(good.get(1)));
        representations.add(voucherRep(tampered));
        assertThatThrownBy(() -> present(ENTITIES, representations))
            .isInstanceOfSatisfying(VoucherRejectedException.class,
                e -> assertThat(e.reason()).isEqualTo(VoucherRejectedException.Reason.SIGNATURE))
            .hasMessageNotContaining(tampered);
        assertThat(LEASED).isEmpty();
    }

    @Test
    void everyFailedCheckRejectsTheRequestAndTakesNoConnection() {
        var now = Instant.now();
        var owner = Map.of("eierId", "olivia");
        var lookup = Map.of("id", "1");
        var cases = Map.of(
            VoucherRejectedException.Reason.EXPIRED,
            forge("VgsResultat", SCOPE, now.minus(Duration.ofMinutes(5)), owner, lookup),
            VoucherRejectedException.Reason.SCOPE,
            forge("VgsResultat", "another-scope", now.plus(Duration.ofMinutes(5)), owner, lookup),
            VoucherRejectedException.Reason.SIGNATURE,
            forge("Detail", SCOPE, now.plus(Duration.ofMinutes(5)), owner, lookup),
            VoucherRejectedException.Reason.VOUCHED_FOR,
            forge("VgsResultat", SCOPE, now.plus(Duration.ofMinutes(5)), Map.of("owner", "olivia"), lookup),
            VoucherRejectedException.Reason.KEY,
            forge("VgsResultat", SCOPE, now.plus(Duration.ofMinutes(5)), owner, Map.of("resultId", "1")),
            VoucherRejectedException.Reason.VALUE,
            forge("VgsResultat", SCOPE, now.plus(Duration.ofMinutes(5)), owner, Map.of("id", "not a number")),
            VoucherRejectedException.Reason.MALFORMED, "not a voucher");
        cases.forEach((reason, voucher) -> {
            LEASED.clear();
            assertThatThrownBy(() -> present(ENTITIES, List.of(voucherRep(voucher),
                    Map.of("__typename", "VgsResultat", "id", "4"))))
                .as(reason.name())
                .isInstanceOfSatisfying(VoucherRejectedException.class, e -> assertThat(e.reason()).isEqualTo(reason));
            assertThat(LEASED).as(reason.name()).isEmpty();
        });
    }
}
