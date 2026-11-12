package no.sikt.graphitron.rewrite.test.querydb;

import graphql.ExecutionResult;
import graphql.GraphQL;
import no.sikt.graphitron.generated.issuing.Graphitron;
import no.sikt.graphitron.generated.issuing.schema.SessionState;
import no.sikt.graphitron.generated.issuing.schema.VoucherCodec;
import no.sikt.graphitron.generated.issuing.schema.VoucherKeys;
import no.sikt.graphitron.generated.issuing.schema.VoucherRejectedException;
import no.sikt.graphitron.rewrite.test.SakilaDatabase;
import no.sikt.graphitron.rewrite.test.services.SakilaSessionIdentity;
import no.sikt.graphitron.rewrite.test.tier.ExecutionTier;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.lang.reflect.Proxy;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static no.sikt.graphitron.rewrite.test.jooq.public_.Tables.FILM;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The issuing side end to end, over the {@code issuing} package: every film a language returns gets a
 * voucher that the codec verifies for {@code FilmVoucher} under the field's scope and the request's
 * caller, carrying the film's title as its claim and its id as its lookup part, each in the form the
 * field's scalar serializes it; all of one operation's vouchers share one vouch id and expire the
 * field's lifetime after the operation started, and another operation mints another vouch id.
 */
@ExecutionTier
class VoucherIssueExecutionTest {

    private static final String CLAIMS = "{\"sub\":\"test-user\"}";
    private static final String QUERY = "{ languages { films { voucher } } }";

    static DSLContext dsl;
    static KeyPair pair;
    static VoucherKeys keys;
    static GraphQL graphql;

    @BeforeAll
    static void start() throws Exception {
        String url = SakilaDatabase.url();
        String user = SakilaDatabase.user();
        String password = SakilaDatabase.password();
        dsl = DSL.using(url, user, password);
        var dataSource = (DataSource) Proxy.newProxyInstance(VoucherIssueExecutionTest.class.getClassLoader(),
            new Class<?>[] {DataSource.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getConnection" -> SakilaDatabase.connect(url, user, password);
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                case "toString" -> "voucherIssueDataSource";
                default -> throw new UnsupportedOperationException(method.getName());
            });
        pair = KeyPairGenerator.getInstance(VoucherKeys.ALGORITHM).generateKeyPair();
        keys = VoucherKeys.ed25519("k1", pair.getPrivate(), Map.of("k1", pair.getPublic()));
        graphql = Graphitron.runtime(dataSource, SQLDialect.POSTGRES, keys)
            .newGraphQL(Graphitron.buildSchema(b -> {}))
            .build();
    }

    private static SessionState session(String caller) {
        return SessionState.of(caller,
            session -> SakilaSessionIdentity.mount(session.configuration(), CLAIMS),
            (session, handle) -> SakilaSessionIdentity.unmount(session.configuration(), handle));
    }

    @SuppressWarnings("unchecked")
    private static List<String> vouchers(ExecutionResult result) {
        assertThat(result.getErrors()).isEmpty();
        var languages = (List<Map<String, Object>>) ((Map<String, Object>) result.getData()).get("languages");
        return languages.stream()
            .flatMap(language -> ((List<Map<String, Object>>) language.get("films")).stream())
            .map(film -> (String) film.get("voucher"))
            .toList();
    }

    private static ExecutionResult run(String caller) {
        return graphql.execute(Graphitron.newOwnedExecutionInput(session(caller)).query(QUERY).build());
    }

    private static VoucherCodec.Token verify(VoucherCodec.Verifier verifier, String voucher) {
        return verifier.verify("FilmVoucher", voucher, "films", Set.of("title"), Set.of("filmId"));
    }

    @Test
    void everyFilmGetsAVoucherCarryingItsTitleAsTheClaimAndItsIdAsTheLookup() {
        var before = Instant.now().minusSeconds(1);
        var vouchers = vouchers(run("alice"));
        var titles = dsl.select(FILM.FILM_ID, FILM.TITLE).from(FILM).fetchMap(FILM.FILM_ID, FILM.TITLE);
        assertThat(vouchers).isNotEmpty();
        var verifier = VoucherCodec.verifier(keys, Instant.now(), "alice");
        for (var voucher : vouchers) {
            var token = verify(verifier, voucher);
            assertThat(token.caller()).isEqualTo("alice");
            assertThat(token.keyId()).isEqualTo("k1");
            var filmId = token.lookup().get("filmId");
            assertThat(filmId).as("an ID serializes as a string").isInstanceOf(String.class);
            assertThat(token.claims()).containsExactly(Map.entry("title", titles.get(Integer.valueOf((String) filmId))));
            assertThat(token.expires()).isBetween(before.plus(Duration.ofMinutes(5)).minusSeconds(1),
                Instant.now().plus(Duration.ofMinutes(5)));
        }
    }

    @Test
    void anOperationsVouchersShareOneVouchIdAndAnotherOperationMintsAnother() {
        var verifier = VoucherCodec.verifier(keys, Instant.now(), "alice");
        var first = vouchers(run("alice")).stream().map(v -> verify(verifier, v).vouchId()).distinct().toList();
        var second = vouchers(run("alice")).stream().map(v -> verify(verifier, v).vouchId()).distinct().toList();
        assertThat(first).hasSize(1);
        assertThat(second).hasSize(1);
        assertThat(first.get(0)).isNotEqualTo(second.get(0));
    }

    @Test
    void aVoucherIsBoundToTheCallerItWasIssuedTo() {
        var voucher = vouchers(run("alice")).get(0);
        assertThatThrownBy(() -> verify(VoucherCodec.verifier(keys, Instant.now(), "bob"), voucher))
            .isInstanceOfSatisfying(VoucherRejectedException.class,
                e -> assertThat(e.reason()).isEqualTo(VoucherRejectedException.Reason.CALLER));
        assertThatThrownBy(() -> VoucherCodec.verifier(keys, Instant.now(), "alice")
                .verify("Film", voucher, "films", Set.of("title"), Set.of("filmId")))
            .isInstanceOfSatisfying(VoucherRejectedException.class,
                e -> assertThat(e.reason()).isEqualTo(VoucherRejectedException.Reason.SIGNATURE));
    }
}
