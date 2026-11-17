package no.sikt.graphitron.rewrite.generated;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import graphql.ExecutionResult;
import graphql.GraphQL;
import no.sikt.graphitron.generated.facts.voucher_accept_subgraph.Graphitron;
import no.sikt.graphitron.generated.facts.voucher_accept_subgraph.schema.ConnectionKey;
import no.sikt.graphitron.generated.facts.voucher_accept_subgraph.schema.ConnectionSource;
import no.sikt.graphitron.generated.facts.voucher_accept_subgraph.schema.KeyNotPermitted;
import no.sikt.graphitron.generated.facts.voucher_accept_subgraph.schema.VouchedValueLimitExceededException;
import no.sikt.graphitron.generated.facts.voucher_accept_subgraph.schema.VoucherCodec;
import no.sikt.graphitron.generated.facts.voucher_accept_subgraph.schema.VoucherKeys;
import no.sikt.graphitron.generated.facts.voucher_accept_subgraph.schema.VoucherRejectedException;
import no.sikt.graphitron.rewrite.test.tier.UnitTier;
import org.jooq.SQLDialect;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The accepting dispatch of the fact scenario {@code voucher-accept-subgraph}, driven through its
 * generated engine on plain representations with real keys and no database: the connection source
 * records the key it is asked for and refuses it, so a batch answers {@code null} without SQL and
 * what the test reads is which key the dispatch built. A voucher the codec decodes and checks, and
 * whose values the dispatcher coerces to their columns, is read on a key carrying its vouched-for
 * value; one that fails any check rejects the request before any key is asked for, among plain-key and
 * valid representations alike; a second vouched-for value rejects it with the limit.
 */
@UnitTier
class VoucherDispatchTest {

    private static final String SCOPE = "vgs-resultater";
    private static final String TYPE = "VgsResultat";
    private static final String ENTITIES = """
        query ($representations: [_Any!]!) {
          _entities(representations: $representations) { ... on VgsResultat { grade } }
        }""";

    private static KeyPair pair;
    private static VoucherKeys keys;
    private static GraphQL engine;

    private final List<ConnectionKey> asked = Collections.synchronizedList(new ArrayList<>());
    private ListAppender<ILoggingEvent> audit;

    @BeforeAll
    static void build() throws Exception {
        pair = KeyPairGenerator.getInstance(VoucherKeys.ALGORITHM).generateKeyPair();
        keys = VoucherKeys.ed25519("k1", pair.getPrivate(), Map.of("k1", pair.getPublic()));
        engine = Graphitron.runtime(SQLDialect.POSTGRES, keys).newGraphQL(Graphitron.buildSchema(b -> {})).build();
    }

    @BeforeEach
    void forget() {
        asked.clear();
        audit = new ListAppender<>();
        audit.start();
        auditLogger().addAppender(audit);
    }

    @AfterEach
    void detach() {
        auditLogger().detachAppender(audit);
    }

    private static Logger auditLogger() {
        return (Logger) LoggerFactory.getLogger("no.sikt.graphitron.voucher.audit");
    }

    private List<String> auditLines() {
        return audit.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    /** A source that records each key and refuses it, so its batch answers null and runs no SQL. */
    private ConnectionSource refusingSource() {
        return key -> {
            asked.add(key);
            throw new KeyNotPermitted("not served in this test");
        };
    }

    private ExecutionResult present(List<Map<String, Object>> representations) {
        return engine.execute(Graphitron.newOwnedExecutionInput(refusingSource())
            .query(ENTITIES)
            .variables(Map.of("representations", representations))
            .build());
    }

    private static String voucher(String typeName, String scope, Instant expires, Map<String, ?> vouchedFor,
                                  Map<String, ?> lookup) {
        return VoucherCodec.issuer(keys, UUID.randomUUID(), scope, expires).sign(typeName, vouchedFor, lookup);
    }

    private static String valid(String owner, String id) {
        return voucher(TYPE, SCOPE, Instant.now().plus(Duration.ofMinutes(5)), Map.of("eierId", owner), Map.of("id", id));
    }

    private static Map<String, Object> rep(Object voucher) {
        var rep = new LinkedHashMap<String, Object>();
        rep.put("__typename", TYPE);
        rep.put("voucher", voucher);
        return rep;
    }

    private static Map<String, Object> plain(String id) {
        return Map.of("__typename", TYPE, "id", id);
    }

    @Test
    void aValidVoucherIsReadOnAKeyCarryingItsVouchedForValue() {
        var result = present(List.of(rep(valid("olivia", "1")), rep(valid("olivia", "2"))));
        assertThat(result.getErrors()).isEmpty();
        assertThat(asked).singleElement().satisfies(key -> {
            assertThat(key.eierId()).contains("olivia");
            assertThat(key.hasVouchedFor()).isTrue();
        });
    }

    @Test
    void aPlainKeyIsReadOnTheKeyWithNoParts_besideAVouchedOne() {
        var result = present(List.of(plain("1"), rep(valid("olivia", "1"))));
        assertThat(result.getErrors()).isEmpty();
        assertThat(asked).containsExactlyInAnyOrder(ConnectionKey.defaultSource(), ConnectionKey.forVouchedFor("olivia"));
    }

    @Test
    void aVoucherJustPastItsExpiryIsAcceptedWithinTheSkew() {
        var result = present(List.of(rep(voucher(TYPE, SCOPE, Instant.now().minusSeconds(10),
            Map.of("eierId", "olivia"), Map.of("id", "1")))));
        assertThat(result.getErrors()).isEmpty();
        assertThat(asked).singleElement().satisfies(key -> assertThat(key.eierId()).contains("olivia"));
    }

    @Test
    void everyFailedCheckRejectsTheRequestBeforeAnyKeyIsAskedFor_amongPlainAndValidRepresentations() {
        var later = Instant.now().plus(Duration.ofMinutes(5));
        var owner = Map.of("eierId", "olivia");
        var lookup = Map.of("id", "1");
        var good = valid("olivia", "2");
        var tampered = good.substring(0, good.length() - 4) + "AAAA";
        var cases = new LinkedHashMap<Object, VoucherRejectedException.Reason>();
        cases.put(voucher(TYPE, SCOPE, Instant.now().minus(Duration.ofMinutes(5)), owner, lookup),
            VoucherRejectedException.Reason.EXPIRED);
        cases.put(voucher(TYPE, "another-scope", later, owner, lookup), VoucherRejectedException.Reason.SCOPE);
        cases.put(voucher("Detail", SCOPE, later, owner, lookup), VoucherRejectedException.Reason.SIGNATURE);
        cases.put(tampered, VoucherRejectedException.Reason.SIGNATURE);
        cases.put(voucher(TYPE, SCOPE, later, Map.of("owner", "olivia"), lookup),
            VoucherRejectedException.Reason.VOUCHED_FOR);
        cases.put(voucher(TYPE, SCOPE, later, Map.of(), lookup), VoucherRejectedException.Reason.VOUCHED_FOR);
        cases.put(voucher(TYPE, SCOPE, later, owner, Map.of("resultId", "1")), VoucherRejectedException.Reason.KEY);
        cases.put(voucher(TYPE, SCOPE, later, owner, Map.of("id", "one")), VoucherRejectedException.Reason.VALUE);
        cases.put(voucher(TYPE, SCOPE, later, owner, Map.of("id", "99999999999")), VoucherRejectedException.Reason.VALUE);
        cases.put("not a voucher", VoucherRejectedException.Reason.MALFORMED);
        cases.put(42, VoucherRejectedException.Reason.MALFORMED);
        cases.forEach((bad, reason) -> {
            asked.clear();
            assertThatThrownBy(() -> present(List.of(plain("4"), rep(good), rep(bad))))
                .as(reason.name())
                .isInstanceOfSatisfying(VoucherRejectedException.class, e -> {
                    assertThat(e.reason()).isEqualTo(reason);
                    assertThat(e.getMessage()).doesNotContain(String.valueOf(bad)).doesNotContain("olivia");
                });
            assertThat(asked).as(reason.name() + ": nothing is acquired after a failed check").isEmpty();
        });
    }

    @Test
    void vouchersForTwoValuesRejectTheRequestWithTheLimit_whateverTheirOrder() {
        for (var owners : List.of(List.of("olivia", "peter"), List.of("peter", "olivia"))) {
            asked.clear();
            assertThatThrownBy(() -> present(List.of(rep(valid(owners.get(0), "1")), rep(valid(owners.get(1), "3")))))
                .isInstanceOf(VouchedValueLimitExceededException.class)
                .hasMessageContaining("more than one vouched-for value");
            assertThat(asked).hasSize(1);
        }
    }

    @Test
    void everyRedemptionAndRejectionIsAuditedAtInfoByVouchIdScopeAndOutcome_neverTheTokenOrItsValues() {
        var good = valid("olivia", "1");
        present(List.of(rep(good)));
        assertThat(audit.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(ch.qos.logback.classic.Level.INFO);
            assertThat(event.getFormattedMessage()).matches("Voucher redeemed: vouch id [0-9a-f-]{36}, scope " + SCOPE);
        });
        audit.list.clear();
        var tampered = good.substring(0, good.length() - 4) + "AAAA";
        assertThatThrownBy(() -> present(List.of(rep(tampered)))).isInstanceOf(VoucherRejectedException.class);
        assertThat(auditLines()).containsExactly("Voucher rejected: vouch id unknown, scope " + SCOPE + ", reason SIGNATURE");
        audit.list.clear();
        assertThatThrownBy(() -> present(List.of(rep(voucher(TYPE, SCOPE, Instant.now().plus(Duration.ofMinutes(5)),
            Map.of("eierId", "olivia"), Map.of("id", "one"))))));
        assertThat(auditLines()).singleElement().asString()
            .matches("Voucher rejected: vouch id [0-9a-f-]{36}, scope " + SCOPE + ", reason VALUE");
        assertThat(String.join("\n", auditLines())).doesNotContain(good).doesNotContain("olivia").doesNotContain("one,");
    }
}
