package no.sikt.graphitron.rewrite.test.querydb;

import graphql.ExecutionResult;
import graphql.GraphQL;
import no.sikt.graphitron.generated.issuing.Graphitron;
import no.sikt.graphitron.generated.issuing.schema.ConnectionSource;
import no.sikt.graphitron.generated.issuing.schema.VoucherCodec;
import no.sikt.graphitron.generated.issuing.schema.VoucherKeys;
import no.sikt.graphitron.generated.issuing.schema.VoucherRejectedException;
import no.sikt.graphitron.rewrite.test.SakilaDatabase;
import no.sikt.graphitron.rewrite.test.tier.ExecutionTier;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.junit.jupiter.api.AutoClose;
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
 * voucher that the codec verifies for {@code FilmVoucher} under the field's scope, carrying the film's
 * title as vouched for and its id as its lookup part, each in the form the
 * field's scalar serializes it; all of one operation's vouchers share one vouch id and expire the
 * field's lifetime after the operation started, and another operation mints another vouch id.
 */
@ExecutionTier
class VoucherIssueExecutionTest {

    private static final String CLAIMS = "{\"sub\":\"test-user\"}";
    private static final String QUERY = "{ languages { films { internalGraphitronVoucher } } }";

    @AutoClose
    static DSLContext dsl;
    static KeyPair pair;
    static VoucherKeys keys;
    static GraphQL graphql;
    static DataSource pool;

    @BeforeAll
    static void start() throws Exception {
        String url = SakilaDatabase.url();
        String user = SakilaDatabase.user();
        String password = SakilaDatabase.password();
        dsl = SakilaDatabase.dsl();
        pool = (DataSource) Proxy.newProxyInstance(VoucherIssueExecutionTest.class.getClassLoader(),
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
        graphql = Graphitron.runtime(SQLDialect.POSTGRES, keys)
            .newGraphQL(Graphitron.buildSchema(b -> {}))
            .build();
    }

    private static ConnectionSource session() {
        return Sources.issuing(CLAIMS, pool);
    }

    @SuppressWarnings("unchecked")
    private static List<String> vouchers(ExecutionResult result) {
        assertThat(result.getErrors()).isEmpty();
        var languages = (List<Map<String, Object>>) ((Map<String, Object>) result.getData()).get("languages");
        return languages.stream()
            .flatMap(language -> ((List<Map<String, Object>>) language.get("films")).stream())
            .map(film -> (String) film.get("internalGraphitronVoucher"))
            .toList();
    }

    private static ExecutionResult run() {
        return graphql.execute(Graphitron.newOwnedExecutionInput(session()).query(QUERY).build());
    }

    private static VoucherCodec.Token verify(VoucherCodec.Verifier verifier, String voucher) {
        return verifier.verify("FilmVoucher", voucher, "films", Set.of("title"), Set.of("filmId"));
    }

    /** The last of sakila's seeded film ids, 1 to 5; other execution tests write films of their own above it. */
    private static final int LAST_SEEDED_FILM = 5;

    /**
     * Judged on the seeded films: a film another test writes can change or go between the query and
     * the titles read beside it, so its voucher is verified and its vouched-for value left unjudged.
     */
    @Test
    void everyFilmGetsAVoucherVouchingForItsTitleWithItsIdAsTheLookup() {
        var before = Instant.now().minusSeconds(1);
        var vouchers = vouchers(run());
        var titles = dsl.select(FILM.FILM_ID, FILM.TITLE).from(FILM).fetchMap(FILM.FILM_ID, FILM.TITLE);
        assertThat(vouchers).isNotEmpty();
        var verifier = VoucherCodec.verifier(keys, Instant.now());
        for (var voucher : vouchers) {
            var token = verify(verifier, voucher);
            assertThat(token.keyId()).isEqualTo("k1");
            var filmId = token.lookup().get("filmId");
            assertThat(filmId).as("an ID serializes as a string").isInstanceOf(String.class);
            if (Integer.valueOf((String) filmId) <= LAST_SEEDED_FILM) {
                assertThat(token.vouchedFor())
                    .containsExactly(Map.entry("title", titles.get(Integer.valueOf((String) filmId))));
            }
            assertThat(token.expires()).isBetween(before.plus(Duration.ofMinutes(5)).minusSeconds(1),
                Instant.now().plus(Duration.ofMinutes(5)));
        }
    }

    /**
     * A single, nullable field into an issuing type answers the referenced row, not {@code null}: the
     * type's written fields are sealed into the voucher, so no presence column could tell the row
     * absent, and the row's existence decides.
     */
    @Test
    @SuppressWarnings("unchecked")
    void aSingleFieldIntoAnIssuingTypeAnswersItsRowWithAVoucher() {
        var result = graphql.execute(Graphitron.newOwnedExecutionInput(session())
            .query("{ inventories { inventoryId film { internalGraphitronVoucher } } }").build());
        assertThat(result.getErrors()).isEmpty();
        var inventories = (List<Map<String, Object>>) ((Map<String, Object>) result.getData()).get("inventories");
        assertThat(inventories).isNotEmpty();
        var verifier = VoucherCodec.verifier(keys, Instant.now());
        for (var inventory : inventories) {
            var film = (Map<String, Object>) inventory.get("film");
            assertThat(film).as("inventory %s's film", inventory.get("inventoryId")).isNotNull();
            assertThat(verify(verifier, (String) film.get("internalGraphitronVoucher")).keyId()).isEqualTo("k1");
        }
    }

    @Test
    void anOperationsVouchersShareOneVouchIdAndAnotherOperationMintsAnother() {
        var verifier = VoucherCodec.verifier(keys, Instant.now());
        var first = vouchers(run()).stream().map(v -> verify(verifier, v).vouchId()).distinct().toList();
        var second = vouchers(run()).stream().map(v -> verify(verifier, v).vouchId()).distinct().toList();
        assertThat(first).hasSize(1);
        assertThat(second).hasSize(1);
        assertThat(first.get(0)).isNotEqualTo(second.get(0));
    }

    @Test
    void aVoucherIsBoundToItsTypeAndToNoCaller() {
        var voucher = vouchers(run()).get(0);
        assertThat(java.util.Arrays.stream(VoucherCodec.Token.class.getRecordComponents())
                .map(java.lang.reflect.RecordComponent::getName))
            .doesNotContain("caller");
        assertThatThrownBy(() -> VoucherCodec.verifier(keys, Instant.now())
                .verify("Film", voucher, "films", Set.of("title"), Set.of("filmId")))
            .isInstanceOfSatisfying(VoucherRejectedException.class,
                e -> assertThat(e.reason()).isEqualTo(VoucherRejectedException.Reason.SIGNATURE));
    }
}
