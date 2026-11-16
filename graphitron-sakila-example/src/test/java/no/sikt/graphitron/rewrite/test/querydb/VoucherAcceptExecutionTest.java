package no.sikt.graphitron.rewrite.test.querydb;

import graphql.ExecutionResult;
import graphql.GraphQL;
import no.sikt.graphitron.generated.accepting.schema.ConnectionKey;
import no.sikt.graphitron.generated.accepting.schema.SessionState;
import no.sikt.graphitron.generated.accepting.schema.VoucherKeys;
import no.sikt.graphitron.generated.accepting.schema.VoucherRejectedException;
import no.sikt.graphitron.rewrite.test.SakilaDatabase;
import no.sikt.graphitron.rewrite.test.services.SakilaSessionIdentity;
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
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The voucher handover end to end, slice 8a: the {@code issuing} package vouches for each result an
 * application references, and the {@code accepting} package verifies those vouchers and reads the vouched
 * results, and the details their SELECT nests, on a connection mounted for the owner each voucher carries.
 * The accepting side reads as a role the row-level security policies apply to, which admit a result and its
 * details only to a connection whose mount set {@code app.vouched_owner} to the result's owner; the
 * request's own connection sets nothing, so a read outside a vouched subtree is admitted nothing.
 */
@ExecutionTier
class VoucherAcceptExecutionTest {

    private static final String CLAIMS = "{\"sub\":\"test-user\"}";
    private static final String PROBE_USER = "voucher_accept_probe";
    private static final String PROBE_PASSWORD = "probe";
    private static final String ENTITIES = """
        query ($representations: [_Any!]!) {
          _entities(representations: $representations) {
            ... on VgsResultat { id grade details { subject } }
          }
        }""";

    static DSLContext superDsl;
    static KeyPair pair;
    static GraphQL issuing;
    static GraphQL accepting;

    /** The keys the mounts of one test saw, in mount order, and how many connections were unmounted. */
    static final List<ConnectionKey> MOUNTED = Collections.synchronizedList(new ArrayList<>());
    static final List<ConnectionKey> UNMOUNTED = Collections.synchronizedList(new ArrayList<>());

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
        issuing = no.sikt.graphitron.generated.issuing.Graphitron
            .runtime(dataSource(url, user, password), SQLDialect.POSTGRES, issuingKeys)
            .newGraphQL(no.sikt.graphitron.generated.issuing.Graphitron.buildSchema(b -> {}))
            .build();
        var acceptingKeys = VoucherKeys.ed25519("k1", pair.getPrivate(), Map.of("k1", pair.getPublic()));
        accepting = no.sikt.graphitron.generated.accepting.Graphitron
            .runtime(dataSource(url, PROBE_USER, PROBE_PASSWORD), SQLDialect.POSTGRES, acceptingKeys)
            .newGraphQL(no.sikt.graphitron.generated.accepting.Graphitron.buildSchema(b -> {}))
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
        MOUNTED.clear();
        UNMOUNTED.clear();
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
     * The accepting side's session: the mount reads the vouched-for owner off the key it mounts for and
     * sets it for the policies, as a consumer's mount does; the request's own connection, whose key has no
     * vouched-for part, sets it empty.
     */
    private static SessionState acceptingSession(String caller) {
        return SessionState.of(caller,
            session -> {
                MOUNTED.add(session.key());
                String owner = session.key().vouchedFor().map(ConnectionKey.VouchedFor::eierId).orElse("");
                DSL.using(session.configuration()).execute("select set_config('app.vouched_owner', ?, false)", owner);
            },
            session -> {
                UNMOUNTED.add(session.key());
                DSL.using(session.configuration()).execute("select set_config('app.vouched_owner', '', false)");
            });
    }

    /** The vouchers the issuing side signs for one application's results, for {@code caller}. */
    @SuppressWarnings("unchecked")
    private static List<String> vouchers(String caller, int applicationId) {
        var session = no.sikt.graphitron.generated.issuing.schema.SessionState.of(caller,
            s -> SakilaSessionIdentity.mount(s.configuration(), CLAIMS),
            (s, handle) -> SakilaSessionIdentity.unmount(s.configuration(), handle));
        var result = issuing.execute(no.sikt.graphitron.generated.issuing.Graphitron.newOwnedExecutionInput(session)
            .query("{ applications { applicationId results { voucher } } }").build());
        assertThat(result.getErrors()).isEmpty();
        var applications = (List<Map<String, Object>>) ((Map<String, Object>) result.getData()).get("applications");
        return applications.stream()
            .filter(a -> ((Integer) a.get("applicationId")) == applicationId)
            .flatMap(a -> ((List<Map<String, Object>>) a.get("results")).stream())
            .map(r -> (String) r.get("voucher"))
            .toList();
    }

    private static List<Map<String, Object>> representations(List<String> vouchers) {
        return vouchers.stream()
            .map(v -> Map.<String, Object>of("__typename", "VgsResultat", "voucher", v))
            .toList();
    }

    private static ExecutionResult present(String caller, String query, List<Map<String, Object>> representations) {
        return accepting.execute(no.sikt.graphitron.generated.accepting.Graphitron
            .newOwnedExecutionInput(acceptingSession(caller))
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
        var found = entities(present("alice", ENTITIES, representations(vouchers("alice", 1))));
        var byId = new ArrayList<>(found);
        byId.sort(Comparator.comparing(e -> (String) e.get("id")));
        assertThat(byId).extracting(e -> e.get("id"), e -> e.get("grade"))
            .containsExactly(org.assertj.core.groups.Tuple.tuple("1", 5), org.assertj.core.groups.Tuple.tuple("2", 4));
        assertThat(subjects(byId.get(0))).containsExactly("math", "norwegian");
        assertThat(subjects(byId.get(1))).containsExactly("english");
    }

    @Test
    void anOwnersVouchersShareOneConnectionMountedForTheOwnerAndReleasedAtTheEnd() {
        entities(present("alice", ENTITIES, representations(vouchers("alice", 1))));
        assertThat(MOUNTED).singleElement().satisfies(key -> {
            assertThat(key.vouchedFor()).contains(new ConnectionKey.VouchedFor("olivia"));
            assertThat(key.tenant()).isEmpty();
        });
        assertThat(UNMOUNTED).containsExactlyElementsOf(MOUNTED);
    }

    @Test
    void aReadOutsideTheVouchedSubtreeIsAdmittedNothing() {
        var result = present("alice", ENTITIES.replace("_entities(", "results { id } _entities("),
            representations(vouchers("alice", 1)));
        assertThat(result.getErrors()).isEmpty();
        @SuppressWarnings("unchecked")
        var data = (Map<String, Object>) result.getData();
        assertThat((List<?>) data.get("results")).isEmpty();
        assertThat((List<?>) data.get("_entities")).hasSize(2);
    }

    @Test
    void aPlainKeyRepresentationResolvesUnderTheCallersOwnSecurityAndAddsNoAccess() {
        var found = entities(present("alice", ENTITIES,
            List.of(Map.of("__typename", "VgsResultat", "id", "1"))));
        assertThat(found).containsExactly((Map<String, Object>) null);
    }

    @Test
    void vouchersForTwoOwnersInOneRequestFailWithTheLimitNotNull() {
        var mixed = new ArrayList<>(vouchers("alice", 1));
        mixed.addAll(vouchers("alice", 2));
        var result = present("alice", ENTITIES, representations(mixed));
        assertThat(result.getErrors()).singleElement()
            .satisfies(e -> assertThat(e.getMessage()).contains("more than one vouched-for value"));
        assertThat(MOUNTED).isEmpty();
    }

    @Test
    void aTamperedVoucherRejectsTheRequestBeforeAnyConnectionIsTaken() {
        var good = vouchers("alice", 1);
        var tampered = good.get(0).substring(0, good.get(0).length() - 4) + "AAAA";
        var representations = new ArrayList<Map<String, Object>>();
        representations.add(Map.of("__typename", "VgsResultat", "id", "4"));
        representations.addAll(representations(List.of(good.get(1), tampered)));
        assertThatThrownBy(() -> present("alice", ENTITIES, representations))
            .isInstanceOf(VoucherRejectedException.class)
            .hasMessageNotContaining(tampered);
        assertThat(MOUNTED).isEmpty();
    }

    @Test
    void aVoucherIsAcceptedOnlyFromTheCallerItWasIssuedTo() {
        assertThatThrownBy(() -> present("bob", ENTITIES, representations(vouchers("alice", 1))))
            .isInstanceOfSatisfying(VoucherRejectedException.class,
                e -> assertThat(e.reason()).isEqualTo(VoucherRejectedException.Reason.CALLER));
        assertThat(MOUNTED).isEmpty();
    }
}
